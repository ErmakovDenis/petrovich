import asyncio

import pytest

from fleet_service.limits import (
    AdmissionLimiter,
    Overloaded,
    RateLimited,
    TokenBuckets,
    UpstreamLimiter,
    background_priority,
)


class Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


def test_token_bucket_burst_then_rate():
    clock = Clock()
    buckets = TokenBuckets(rate_per_minute=10, burst=3, max_keys=100, clock=clock)
    for _ in range(3):
        buckets.take("u")
    with pytest.raises(RateLimited) as e:
        buckets.take("u")
    # 10 в минуту — один запрос в 6 с.
    assert e.value.retry_after == pytest.approx(6)
    assert "повторите через 6 с" in str(e.value)
    # Другой пользователь не затронут.
    buckets.take("other")
    clock.now += 6
    buckets.take("u")
    with pytest.raises(RateLimited):
        buckets.take("u")
    # За долгий перерыв ведро наполняется только до burst.
    clock.now += 3600
    for _ in range(3):
        buckets.take("u")
    with pytest.raises(RateLimited):
        buckets.take("u")


def test_token_bucket_keys_are_bounded():
    buckets = TokenBuckets(rate_per_minute=1, burst=1, max_keys=2, clock=Clock())
    buckets.take("a")
    buckets.take("b")
    buckets.take("c")  # «a» вытеснен — его ведро снова полное
    buckets.take("a")


@pytest.mark.anyio
async def test_admission_rejects_when_queue_is_full():
    limiter = AdmissionLimiter("chat", concurrency=1, queue_max=1)
    release = asyncio.Event()
    order = []

    async def job(name: str) -> None:
        async with limiter.slot():
            order.append(name)
            await release.wait()

    first = asyncio.create_task(job("first"))
    await asyncio.sleep(0)
    second = asyncio.create_task(job("second"))
    await asyncio.sleep(0)
    assert limiter.active == 1 and limiter.waiting == 1
    with pytest.raises(Overloaded):
        async with limiter.slot():
            pass
    release.set()
    await asyncio.gather(first, second)
    assert order == ["first", "second"] and limiter.active == 0 and limiter.waiting == 0


@pytest.mark.anyio
async def test_upstream_limit_and_background_yields():
    limiter = UpstreamLimiter("autograph", concurrency=2)
    order: list[str] = []
    gates: dict[str, asyncio.Event] = {}

    async def call(name: str, background: bool = False) -> None:
        gates[name] = asyncio.Event()
        if background:
            with background_priority():
                async with limiter.slot():
                    order.append(name)
                    await gates[name].wait()
        else:
            async with limiter.slot():
                order.append(name)
                await gates[name].wait()

    tasks = [asyncio.create_task(call("u1")), asyncio.create_task(call("u2"))]
    await asyncio.sleep(0)
    tasks.append(asyncio.create_task(call("bg", background=True)))
    await asyncio.sleep(0)
    tasks.append(asyncio.create_task(call("u3")))
    await asyncio.sleep(0)
    assert order == ["u1", "u2"] and limiter.waiting == 2
    # Освободилось место: пользователь u3 пришёл позже фоновой задачи, но проходит первым.
    gates["u1"].set()
    await asyncio.sleep(0.01)
    assert order == ["u1", "u2", "u3"]
    gates["u2"].set()
    await asyncio.sleep(0.01)
    assert order == ["u1", "u2", "u3", "bg"]
    for g in gates.values():
        g.set()
    await asyncio.gather(*tasks)
    assert limiter.active == 0 and limiter.background_active == 0


@pytest.mark.anyio
async def test_background_takes_at_most_half():
    limiter = UpstreamLimiter("autograph", concurrency=4)
    gate = asyncio.Event()
    running = []

    async def background_call(n: int) -> None:
        with background_priority():
            async with limiter.slot():
                running.append(n)
                await gate.wait()

    tasks = [asyncio.create_task(background_call(n)) for n in range(4)]
    await asyncio.sleep(0.01)
    assert len(running) == 2 and limiter.background_active == 2
    # Пользователь получает свободное место сразу, хотя фоновые ждут.
    async with limiter.slot():
        assert limiter.active == 3
    gate.set()
    await asyncio.gather(*tasks)
    assert running == [0, 1, 2, 3]


def test_token_refund():
    buckets = TokenBuckets(rate_per_minute=1, burst=1, clock=Clock())
    buckets.take("u")
    buckets.refund("u")
    buckets.take("u")
    with pytest.raises(RateLimited):
        buckets.take("u")


@pytest.mark.anyio
async def test_cancelled_waiter_popped_by_release_raises_cancelled():
    """Отмена ждущего и освобождение места до того, как он проснулся: CancelledError, а не ValueError."""
    for limiter in (UpstreamLimiter("autograph", 1), AdmissionLimiter("chat", 1, 5)):
        held = asyncio.Event()
        stop = asyncio.Event()

        async def holder() -> None:
            async with limiter.slot():
                held.set()
                await stop.wait()

        first = asyncio.create_task(holder())
        await held.wait()
        waiter = asyncio.create_task(_enter(limiter))
        await asyncio.sleep(0)
        waiter.cancel()
        stop.set()  # место освобождается, пока отменённый ждущий ещё не проснулся
        await first
        with pytest.raises(asyncio.CancelledError):
            await waiter
        assert limiter.active == 0 and limiter.waiting == 0
        await _enter(limiter)


async def _enter(limiter) -> None:
    async with limiter.slot():
        pass


@pytest.mark.anyio
async def test_admission_hands_the_slot_over_without_false_rejects():
    limiter = AdmissionLimiter("chat", concurrency=1, queue_max=1)
    first = limiter.slot()
    await first.__aenter__()
    second = asyncio.create_task(_enter(limiter))
    await asyncio.sleep(0)
    assert limiter.waiting == 1
    await first.__aexit__(None, None, None)
    # Место передано второму, тот ещё не проснулся: очередь уже пуста — новый запрос встаёт в неё, а не получает 503.
    assert limiter.active == 1 and limiter.waiting == 0
    third = asyncio.create_task(_enter(limiter))
    await asyncio.gather(second, third)
    assert limiter.active == 0


@pytest.mark.anyio
async def test_cancelled_waiter_does_not_leak_a_slot():
    limiter = UpstreamLimiter("predictive", concurrency=1)
    held = await limiter.acquire()
    waiter = asyncio.create_task(limiter.acquire())
    await asyncio.sleep(0)
    waiter.cancel()
    with pytest.raises(asyncio.CancelledError):
        await waiter
    held.release()
    held.release()  # повторное освобождение ничего не меняет
    assert limiter.active == 0 and limiter.waiting == 0
    (await limiter.acquire()).release()
