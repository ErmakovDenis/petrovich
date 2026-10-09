"""Кэш в памяти со сроком жизни записей и одной загрузкой на ключ: параллельные запросы одного ключа ждут
одну загрузку. Ошибки не кэшируются. Отмена запроса не прерывает начатую загрузку — её результат попадёт в кэш.
"""

import asyncio
import time
from collections import OrderedDict
from collections.abc import Awaitable, Callable, Hashable
from typing import Generic, TypeVar

T = TypeVar("T")


class TtlCache(Generic[T]):
    def __init__(self, ttl_seconds: float, max_entries: int):
        self._ttl = ttl_seconds
        self._max = max_entries
        self._data: OrderedDict[Hashable, tuple[float, T]] = OrderedDict()
        self._loading: dict[Hashable, asyncio.Task[T]] = {}

    def __len__(self) -> int:
        return len(self._data)

    async def get(self, key: Hashable, load: Callable[[], Awaitable[T]]) -> T:
        entry = self._data.get(key)
        if entry is not None:
            if entry[0] > time.monotonic():
                self._data.move_to_end(key)
                return entry[1]
            del self._data[key]
        task = self._loading.get(key)
        if task is None:
            task = asyncio.ensure_future(load())
            self._loading[key] = task
            task.add_done_callback(lambda t: self._done(key, t))
        return await asyncio.shield(task)

    def put(self, key: Hashable, value: T) -> None:
        if self._ttl <= 0:
            return
        self._data[key] = (time.monotonic() + self._ttl, value)
        self._data.move_to_end(key)
        while len(self._data) > self._max:
            self._data.popitem(last=False)

    def _done(self, key: Hashable, task: asyncio.Task[T]) -> None:
        if self._loading.get(key) is task:
            del self._loading[key]
        if task.cancelled() or task.exception() is not None:  # exception() — помечает ошибку как полученную
            return
        self.put(key, task.result())
