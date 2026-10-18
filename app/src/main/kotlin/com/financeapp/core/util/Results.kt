package com.financeapp.core.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching] but never swallows coroutine cancellation, so a screen that is closed
 * mid-save doesn't report a bogus "save failed" and structured concurrency keeps working.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
