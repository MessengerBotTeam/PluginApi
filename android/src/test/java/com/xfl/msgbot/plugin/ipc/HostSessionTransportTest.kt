package com.xfl.msgbot.plugin.ipc

import android.os.IBinder
import android.os.SharedMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HostSessionTransportTest {
    private class Service(var fingerprint: String = BinderContract.FINGERPRINT, var id: Long = 1) : IPluginService.Stub() {
        var opens = 0

        override fun apiFingerprint() = fingerprint

        override fun open(
            role: String,
            component: String,
            callback: IPluginCallback,
        ): Long {
            assertEquals(BinderContract.FINGERPRINT, callback.apiFingerprint())
            opens++
            return id
        }

        override fun send(
            session: Long,
            frame: ByteArray,
        ) = Unit

        override fun sendShared(
            session: Long,
            transferId: Long,
            region: SharedMemory,
        ) = Unit

        override fun close(session: Long) = Unit
    }

    @Test
    fun aMatchingInterfaceOpensTheSession() {
        val service = Service()
        val transport = HostSessionTransport(service)
        try {
            transport.open("provider", "source")
        } finally {
            transport.close()
        }
        assertEquals(1, service.opens)
    }

    @Test
    fun mismatchedInterfacesFailBeforeCallingOpen() {
        val service = Service(fingerprint = "old-interface")
        val error = runCatching { HostSessionTransport(service).open("provider", "source") }.exceptionOrNull()
        assertTrue(error is IllegalStateException && error.message!!.contains("Rebuild the app and plugin"))
        assertEquals(0, service.opens)
    }

    @Test
    fun zeroSessionIdsExplainTheVersionMismatch() {
        val error = runCatching { HostSessionTransport(Service(id = 0)).open("provider", "source") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException && error.message!!.contains("same PluginApi Android version"))
    }

    @Test
    fun aMissingFingerprintMethodExplainsTheVersionMismatch() {
        val error = runCatching { BinderContract.verify { throw android.os.RemoteException("unimplemented") } }.exceptionOrNull()
        assertTrue(error is IllegalStateException && error.message!!.contains("Rebuild the app and plugin"))
    }

    @Test
    fun transactionNumbersRemainFixed() {
        fun code(
            type: Class<*>,
            name: String,
        ): Int = type.getDeclaredField("TRANSACTION_$name").apply { isAccessible = true }.getInt(null)
        val first = IBinder.FIRST_CALL_TRANSACTION
        assertEquals(first, code(IPluginService.Stub::class.java, "open"))
        assertEquals(first + 1, code(IPluginService.Stub::class.java, "send"))
        assertEquals(first + 2, code(IPluginService.Stub::class.java, "sendShared"))
        assertEquals(first + 3, code(IPluginService.Stub::class.java, "close"))
        assertEquals(first + 100, code(IPluginService.Stub::class.java, "apiFingerprint"))
        assertEquals(first, code(IPluginCallback.Stub::class.java, "onFrame"))
        assertEquals(first + 1, code(IPluginCallback.Stub::class.java, "onShared"))
        assertEquals(first + 100, code(IPluginCallback.Stub::class.java, "apiFingerprint"))
    }

    @Test
    fun theFingerprintDescribesBothAidlInterfaces() {
        val base = listOf(File("src/main/aidl"), File("android/src/main/aidl")).first { it.exists() }
        val canonical =
            listOf("IPluginService", "IPluginCallback").joinToString("") {
                File(base, "com/xfl/msgbot/plugin/ipc/$it.aidl").readText().replace(Regex("//[^\\n]*"), "").replace(Regex("\\s+"), "")
            }
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(BinderContract.FINGERPRINT, hash)
    }
}
