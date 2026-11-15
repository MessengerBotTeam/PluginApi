/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.guard

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** `android.graphics.BitmapFactory`; [decodeFile] reads through the host and the rest is the real class. */
class GuardBitmapFactory {
    companion object {
        @JvmStatic
        fun decodeFile(
            pathName: String?,
            opts: BitmapFactory.Options?,
        ): Bitmap? {
            validate(opts)
            // Like the real class: any failure to open or decode is logged and gives null.
            return try {
                GuardFileInputStream(pathName ?: throw NullPointerException()).use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (e: Exception) {
                Log.e("BitmapFactory", "Unable to decode stream: $e")
                null
            }
        }

        @JvmStatic
        fun decodeFile(pathName: String?): Bitmap? = decodeFile(pathName, null)

        @JvmStatic
        fun decodeResourceStream(
            res: Resources?,
            value: TypedValue?,
            `is`: InputStream?,
            pad: Rect?,
            opts: BitmapFactory.Options?,
        ): Bitmap? = BitmapFactory.decodeResourceStream(res, value, `is`, pad, opts)

        @JvmStatic
        fun decodeResource(
            res: Resources?,
            id: Int,
            opts: BitmapFactory.Options?,
        ): Bitmap? = BitmapFactory.decodeResource(res, id, opts)

        @JvmStatic
        fun decodeResource(
            res: Resources?,
            id: Int,
        ): Bitmap? = BitmapFactory.decodeResource(res, id)

        @JvmStatic
        fun decodeByteArray(
            data: ByteArray?,
            offset: Int,
            length: Int,
            opts: BitmapFactory.Options?,
        ): Bitmap? = BitmapFactory.decodeByteArray(data, offset, length, opts)

        @JvmStatic
        fun decodeByteArray(
            data: ByteArray?,
            offset: Int,
            length: Int,
        ): Bitmap? = BitmapFactory.decodeByteArray(data, offset, length)

        @JvmStatic
        fun decodeStream(
            `is`: InputStream?,
            outPadding: Rect?,
            opts: BitmapFactory.Options?,
        ): Bitmap? = BitmapFactory.decodeStream(`is`, outPadding, opts)

        @JvmStatic
        fun decodeStream(`is`: InputStream?): Bitmap? = BitmapFactory.decodeStream(`is`)

        @JvmStatic
        fun decodeFileDescriptor(
            fd: FileDescriptor?,
            outPadding: Rect?,
            opts: BitmapFactory.Options?,
        ): Bitmap? = BitmapFactory.decodeFileDescriptor(fd, outPadding, opts)

        @JvmStatic
        fun decodeFileDescriptor(fd: FileDescriptor?): Bitmap? = BitmapFactory.decodeFileDescriptor(fd)

        private fun validate(opts: BitmapFactory.Options?) {
            if (opts != null && opts.inMutable && opts.inPreferredConfig == Bitmap.Config.HARDWARE) {
                throw IllegalArgumentException("Bitmaps with Config.HARDWARE are always immutable")
            }
        }
    }
}

/** `android.media.MediaMetadataRetriever`; a file path or `file:` address is opened through the host. */
class GuardMediaMetadataRetriever : MediaMetadataRetriever() {
    override fun setDataSource(path: String?) {
        if (path == null) throw IllegalArgumentException("null path")
        val uri = Uri.parse(path)
        var file = path
        when (uri.scheme) {
            null -> Unit
            "file" -> file = uri.path ?: path
            else -> return setDataSource(path, HashMap<String, String>())
        }
        try {
            GuardFileInputStream(file).use { setDataSource(it.fd, 0, 0x7ffffffffffffffL) }
        } catch (e: FileNotFoundException) {
            throw IllegalArgumentException("$file does not exist")
        } catch (e: IOException) {
            throw IllegalArgumentException("couldn't open $file")
        }
    }
}
