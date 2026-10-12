package com.gamch1k.lifeos.phone.watchsetup

import android.content.Context
import android.os.Build
import com.gamch1k.lifeos.core.watchsetup.AdbException
import com.gamch1k.lifeos.core.watchsetup.AdbFailure
import com.gamch1k.lifeos.core.watchsetup.WatchAdb
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * [WatchAdb] over libadb-android 3.1.1 (MuntashirAkon, used under its
 * Apache-2.0 licence): Wireless debugging's pairing (SPAKE2 over TLS 1.3) and
 * connection, `exec:` commands, and a streamed `cmd package install`.
 *
 * Every call runs on an IO thread with a time limit. A plain TCP probe with
 * its own timeout comes before libadb opens a socket (libadb's has none), so
 * "nothing listens there" and "no answer at all" are told apart quickly.
 */
class LibAdbWatchAdb(private val context: Context, private val identity: AdbIdentity) : WatchAdb {
    @Volatile
    private var manager: Manager? = null

    private class Manager(private val key: PrivateKey, private val certificate: Certificate) : AbsAdbConnectionManager() {
        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }

        override fun getPrivateKey(): PrivateKey = key
        override fun getCertificate(): Certificate = certificate

        /** What the watch lists under Wireless debugging › Paired devices. */
        override fun getDeviceName(): String = "Life OS phone"
    }

    private fun newManager(): Manager {
        val key = identity.load()
        return Manager(key.privateKey, key.certificate)
    }

    override suspend fun pair(host: String, port: Int, code: String): Unit = io(PAIR_TIMEOUT_MILLIS) {
        probe(host, port, refused = AdbFailure.PAIRING_CLOSED)
        val pairing = newManager()
        try {
            if (!pairing.pair(host, port, code)) throw AdbException(AdbFailure.WRONG_CODE, "the watch did not accept the pairing")
        } catch (e: AdbException) {
            throw e
        } catch (e: SSLException) {
            throw AdbException(AdbFailure.OTHER, "TLS with the watch failed: ${e.message}", e)
        } catch (e: ConnectException) {
            throw AdbException(AdbFailure.PAIRING_CLOSED, "the pairing port closed", e)
        } catch (e: IOException) {
            // After the TLS handshake: the watch dropped the SPAKE2 exchange, which a wrong code does.
            throw AdbException(AdbFailure.WRONG_CODE, e.message ?: e.javaClass.simpleName, e)
        } finally {
            runCatching { pairing.disconnect() }
        }
    }

    override suspend fun findConnectPort(host: String, timeoutMillis: Long): Int? =
        withContext(Dispatchers.IO) { AdbDiscovery(context).connectPort(host, timeoutMillis) }

    override suspend fun connect(host: String, port: Int): Unit = io(CONNECT_TIMEOUT_MILLIS + PROBE_TIMEOUT_MILLIS + 5_000) {
        disconnectNow()
        probe(host, port, refused = AdbFailure.REFUSED)
        val connection = newManager()
        val connected = try {
            connection.connect(host, port)
        } catch (e: AdbPairingRequiredException) {
            throw AdbException(AdbFailure.NOT_PAIRED, "the watch wants a pairing", e)
        } catch (e: ConnectException) {
            throw AdbException(AdbFailure.REFUSED, e.message ?: "connection refused", e)
        } catch (e: IOException) {
            // Mostly a TLS handshake the watch refused: it doesn't know this key.
            throw AdbException(AdbFailure.NOT_PAIRED, e.message ?: e.javaClass.simpleName, e)
        }
        if (!connected) {
            runCatching { connection.disconnect() }
            throw AdbException(AdbFailure.TIMEOUT, "the watch did not complete the connection")
        }
        manager = connection
    }

    override suspend fun run(command: String): String = io(COMMAND_TIMEOUT_MILLIS) {
        val stream = open("exec:$command")
        try {
            readAll(stream)
        } finally {
            runCatching { stream.close() }
        }
    }

    override suspend fun install(apk: File): String = io(INSTALL_TIMEOUT_MILLIS) {
        val size = apk.length()
        val stream = open("exec:cmd package install -S $size")
        try {
            val output = stream.openOutputStream()
            apk.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            output.flush()
            readAll(stream)
        } finally {
            runCatching { stream.close() }
        }
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO) { disconnectNow() }

    private fun disconnectNow() {
        manager?.let { runCatching { it.disconnect() } }
        manager = null
    }

    private fun open(destination: String): AdbStream {
        val current = manager ?: throw AdbException(AdbFailure.OTHER, "not connected to the watch")
        return try {
            current.openStream(destination)
        } catch (e: IOException) {
            throw AdbException(AdbFailure.OTHER, "the watch refused $destination: ${e.message}", e)
        }
    }

    /** Everything the command prints, until the watch closes the stream. */
    private fun readAll(stream: AdbStream): String {
        val input = stream.openInputStream()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                out.write(buffer, 0, count)
                if (out.size() > MAX_OUTPUT_BYTES) break
            }
        } catch (e: IOException) {
            // libadb signals the end of a closed stream with "Stream closed."
            if (!stream.isClosed) throw AdbException(AdbFailure.OTHER, "the watch's answer broke off: ${e.message}", e)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** A TCP connect with a timeout: refused means nothing listens; no answer means another network. */
    private fun probe(host: String, port: Int, refused: AdbFailure) {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MILLIS.toInt()) }
        } catch (e: ConnectException) {
            throw AdbException(refused, "nothing listens on $host:$port", e)
        } catch (e: SocketTimeoutException) {
            throw AdbException(AdbFailure.UNREACHABLE, "no answer from $host", e)
        } catch (e: NoRouteToHostException) {
            throw AdbException(AdbFailure.UNREACHABLE, "no route to $host", e)
        } catch (e: SocketException) {
            throw AdbException(AdbFailure.UNREACHABLE, e.message ?: "network error", e)
        } catch (e: IOException) {
            throw AdbException(AdbFailure.UNREACHABLE, e.message ?: "network error", e)
        }
    }

    /** Runs [block] on an IO thread, interruptibly, within [timeoutMillis]; every failure becomes an [AdbException]. */
    private suspend fun <T> io(timeoutMillis: Long, block: () -> T): T = try {
        withTimeout(timeoutMillis) { runInterruptible(Dispatchers.IO) { block() } }
    } catch (e: TimeoutCancellationException) {
        throw AdbException(AdbFailure.TIMEOUT, "the watch took longer than ${timeoutMillis / 1000} s", e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: AdbException) {
        throw e
    } catch (e: EOFException) {
        throw AdbException(AdbFailure.OTHER, "the watch closed the connection", e)
    } catch (e: Exception) {
        throw AdbException(AdbFailure.OTHER, e.message ?: e.javaClass.simpleName, e)
    }

    private companion object {
        const val PROBE_TIMEOUT_MILLIS = 5_000L
        const val PAIR_TIMEOUT_MILLIS = 30_000L
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
        const val COMMAND_TIMEOUT_MILLIS = 30_000L
        const val INSTALL_TIMEOUT_MILLIS = 5 * 60_000L
        const val MAX_OUTPUT_BYTES = 256 * 1024
    }
}
