/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.call

import com.xfl.msgbot.plugin.api.value.Value

/**
 * What a call answers: a value, or why there is none. A sealed type makes every engine decide
 * what it does with [Err] before it compiles; a binding turns it into its language's error,
 * carrying [Err.code] for scripts to branch on.
 */
sealed interface CallResult {
    data class Ok(val value: Value) : CallResult

    /** [code] is one of [ErrorCode]; [message] is for the human. */
    data class Err(val code: String, val message: String) : CallResult

    /** The value, or a [CallException] carrying the error. */
    fun getOrThrow(): Value =
        when (this) {
            is Ok -> value
            is Err -> throw CallException(code, message)
        }

    companion object {
        fun ok(value: Value = Value.VNull): CallResult = Ok(value)

        fun failed(message: String): CallResult = Err(ErrorCode.FAILED, message)

        fun badArgs(message: String): CallResult = Err(ErrorCode.BAD_ARGS, message)

        fun unavailable(message: String): CallResult = Err(ErrorCode.UNAVAILABLE, message)

        fun unknownFunction(name: String): CallResult =
            Err(ErrorCode.UNKNOWN_FUNCTION, "No function named '$name' is available to this project")

        /** Runs [block], answering its value or the error it threw. */
        inline fun catching(block: () -> Value): CallResult =
            try {
                Ok(block())
            } catch (e: CallException) {
                Err(e.code, e.message ?: e.code)
            } catch (e: Exception) {
                failed(e.message ?: e.javaClass.simpleName)
            }
    }
}

object ErrorCode {
    /** No such function, or its module is not selected by this project. A typo lands here. */
    const val UNKNOWN_FUNCTION = "unknown_function"

    /** The function exists, but not with these arguments. */
    const val BAD_ARGS = "bad_args"

    /** Whoever answers this is not there right now (a plugin disconnected, access revoked); trying later may work. */
    const val UNAVAILABLE = "unavailable"

    /** It should have worked and did not. */
    const val FAILED = "failed"
}

/** Thrown by an implementation to answer with a specific [ErrorCode]; anything else thrown becomes [ErrorCode.FAILED]. */
class CallException(
    val code: String,
    message: String,
) : RuntimeException(message) {
    companion object {
        fun badArgs(message: String) = CallException(ErrorCode.BAD_ARGS, message)

        fun unavailable(message: String) = CallException(ErrorCode.UNAVAILABLE, message)

        fun failed(message: String) = CallException(ErrorCode.FAILED, message)
    }
}
