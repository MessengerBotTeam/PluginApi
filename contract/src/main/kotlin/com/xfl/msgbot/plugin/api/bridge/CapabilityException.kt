/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.bridge

/**
 * How a capability implementation names the kind of failure it throws; the dispatcher turns it
 * into [CallResult.Err]. Anything else thrown becomes [CallResult.Code.FAILED].
 */
class CapabilityException(
    val code: String,
    message: String,
) : RuntimeException(message) {
    companion object {
        /** The capability exists, but not with these arguments. */
        fun badArgs(message: String) = CapabilityException(CallResult.Code.BAD_ARGS, message)
    }
}
