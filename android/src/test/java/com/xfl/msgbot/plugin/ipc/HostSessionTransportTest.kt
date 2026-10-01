package com.xfl.msgbot.plugin.ipc

import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.SharedMemory
import com.xfl.msgbot.plugin.api.rpc.TransportClosedException
import com.xfl.msgbot.plugin.api.serialization.MalformedFrameException
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
    private class Service(
        var version: Int = BinderContract.VERSION,
        var id: Long = 1,
    ) : IPluginService.Stub() {
        var opens = 0
        var callback: IPluginCallback? = null

        override fun binderVersion() = version

        override fun open(
            role: String,
            component: String,
            callback: IPluginCallback,
        ): Long {
            assertEquals(BinderContract.VERSION, callback.binderVersion())
            this.callback = callback
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
    fun aCompatiblePeerOpensTheSession() {
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
    fun aNewerPeerIsAccepted() {
        val service = Service(version = BinderContract.VERSION + 1)
        HostSessionTransport(service).open("provider", "source")
        assertEquals(1, service.opens)
    }

    @Test
    fun aPeerOlderThanTheMinimumFailsBeforeCallingOpen() {
        // A peer built before binderVersion existed answers the default, 0.
        val service = Service(version = 0)
        val error = runCatching { HostSessionTransport(service).open("provider", "source") }.exceptionOrNull()
        assertTrue(error is IllegalStateException && error.message!!.contains("Rebuild the app or plugin"))
        assertEquals(0, service.opens)
    }

    @Test
    fun aFailingVersionCallExplainsTheMismatch() {
        val error = runCatching { BinderContract.verify { throw android.os.RemoteException("unimplemented") } }.exceptionOrNull()
        assertTrue(error is IllegalStateException && error.message!!.contains("Rebuild the app or plugin"))
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
        assertEquals(first + 101, code(IPluginService.Stub::class.java, "binderVersion"))
        assertEquals(first, code(IPluginCallback.Stub::class.java, "onFrame"))
        assertEquals(first + 1, code(IPluginCallback.Stub::class.java, "onShared"))
        assertEquals(first + 101, code(IPluginCallback.Stub::class.java, "binderVersion"))
    }

    /** Fails when the AIDL changes: raise [BinderContract.VERSION], then record the new version and hash here. */
    @Test
    fun changingTheAidlRaisesTheBinderVersion() {
        val base = listOf(File("src/main/aidl"), File("android/src/main/aidl")).first { it.exists() }
        val canonical =
            listOf("IPluginService", "IPluginCallback").joinToString("") {
                File(base, "com/xfl/msgbot/plugin/ipc/$it.aidl").readText().replace(Regex("//[^\\n]*"), "").replace(Regex("\\s+"), "")
            }
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(AIDL_V1 to 1, hash to BinderContract.VERSION)
    }

    @Test
    fun aDeadPluginClosesTheTransportInsteadOfLookingBusy() {
        val dead = DeadPlugin()
        val transport = HostSessionTransport(dead)
        transport.open("provider", "source")
        val error = runCatching { transport.send(byteArrayOf(1)) }.exceptionOrNull()
        assertTrue("$error", error is TransportClosedException)
    }

    @Test
    fun sendingAfterCloseIsAClosedTransport() {
        val transport = HostSessionTransport(Service())
        transport.open("provider", "source")
        transport.close()
        assertTrue(runCatching { transport.send(byteArrayOf(1)) }.exceptionOrNull() is TransportClosedException)
    }

    @Test
    fun aRegionThatArrivesAfterCloseIsLetGo() {
        val service = Service()
        val transport = HostSessionTransport(service)
        transport.open("provider", "source")
        transport.close()
        service.callback!!.onShared(1, SharedMemory.create("late", 16))
        assertTrue(runCatching { transport.bytes.resolve(1, 16) }.exceptionOrNull() is MalformedFrameException)
    }

    /** Answers like a live plugin until asked to carry a frame, then like a dead process. */
    private class DeadPlugin : IPluginService {
        private val binder =
            object : Binder() {
                override fun isBinderAlive() = false
            }

        override fun asBinder(): IBinder = binder

        override fun binderVersion() = BinderContract.VERSION

        override fun open(
            role: String,
            component: String,
            callback: IPluginCallback,
        ) = 1L

        override fun send(
            session: Long,
            frame: ByteArray,
        ): Unit = throw DeadObjectException()

        override fun sendShared(
            session: Long,
            transferId: Long,
            region: SharedMemory,
        ): Unit = throw DeadObjectException()

        override fun close(session: Long) = Unit
    }

    private companion object {
        const val AIDL_V1 = "7a477998382ec2e59c2fbd0e78082ca1f26ac948b85c55a8ab281e8c7085af08"
    }
}
