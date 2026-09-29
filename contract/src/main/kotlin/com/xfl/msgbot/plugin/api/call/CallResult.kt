/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.call

import com.xfl.msgbot.plugin.api.value.Value

/** Result of a host call. Bindings turn [Err] into a language error that carries [Err.code]. */
sealed interface CallResult {
    data class Ok(val value: Value) : CallResult

    /** [code] is one of [ErrorCode]. */
    data class Err(val code: String, val message: String) : CallResult

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

        /** Non-[CallException] errors become [ErrorCode.FAILED]. */
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
    /** No such function, or its module is not selected by this project. */
    const val UNKNOWN_FUNCTION = "unknown_function"

    /** The function exists, but not with these arguments. */
    const val BAD_ARGS = "bad_args"

    /** The implementation is temporarily unreachable (plugin disconnected, access revoked); retry may work. */
    const val UNAVAILABLE = "unavailable"

    const val FAILED = "failed"
}

/** Throw to fail a call with a specific [ErrorCode]; other exceptions become [ErrorCode.FAILED]. */
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
