package ru.petrovich.telemetry.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * Как [runCatching], но не перехватывает [CancellationException]: отмена корутины
 * (смена машины/периода, закрытие экрана, остановка воркера) должна пробрасываться дальше,
 * а не превращаться в «ошибку загрузки».
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
