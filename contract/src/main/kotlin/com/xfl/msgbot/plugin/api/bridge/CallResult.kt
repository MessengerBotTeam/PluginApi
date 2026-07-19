/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.bridge

import com.xfl.msgbot.plugin.api.value.Value

/**
 * What a capability call answers: a value, or why there is none. A failure returned as null would
 * be indistinguishable from an empty result; a sealed return type forces every engine to decide
 * what it does with [Err] before it compiles.
 */
sealed interface CallResult {
    data class Ok(val value: Value) : CallResult

    /** [code] is one of [Code] for scripts to branch on; [message] is for the human. */
    data class Err(val code: String, val message: String) : CallResult

    object Code {
        /** No such capability, or not granted to this project. A typo lands here. */
        const val UNKNOWN_CAPABILITY = "unknown_capability"

        /** The capability exists but not with these arguments. */
        const val BAD_ARGS = "bad_args"

        /** It should have worked and did not: no network, no permission, no disk. */
        const val FAILED = "failed"
    }

    companion object {
        fun of(value: Value): CallResult = Ok(value)

        fun failed(message: String): CallResult = Err(Code.FAILED, message)

        fun unknown(method: String): CallResult =
            Err(Code.UNKNOWN_CAPABILITY, "No capability named '$method' is available to this project")
    }
}
