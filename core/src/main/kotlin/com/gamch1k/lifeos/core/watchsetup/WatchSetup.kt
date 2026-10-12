package com.gamch1k.lifeos.core.watchsetup

import com.gamch1k.lifeos.core.update.WatchAbi
import com.gamch1k.lifeos.core.wearlink.WatchInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Why an ADB call failed, as far as the phone can tell. */
enum class AdbFailure {
    /** The pairing exchange failed after the TLS handshake: almost always a wrong code. */
    WRONG_CODE,

    /** Nothing listens on the pairing port: the pairing screen closed (the code with it), or the port is another. */
    PAIRING_CLOSED,

    /** Nothing listens on the connection port: Wireless debugging restarted on a new port, or it is off. */
    REFUSED,

    /** No answer at all from that address: another network, or the watch's Wi-Fi is asleep or off. */
    UNREACHABLE,

    /** The watch doesn't trust this phone's key: never paired, or the pairing was removed. */
    NOT_PAIRED,

    TIMEOUT,

    /** Anything else (TLS, the protocol, a stream that closed). */
    OTHER,
}

class AdbException(val failure: AdbFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The ADB client the phone uses once, to put the watch app on the watch:
 * libadb-android on the phone, a fake in the tests. Every call may throw
 * [AdbException].
 */
interface WatchAdb {
    /** Wireless debugging › Pair new device: SPAKE2 with the six-digit code; the watch then trusts this phone's key. */
    suspend fun pair(host: String, port: Int, code: String)

    /** The connection port [host] announces over mDNS (`_adb-tls-connect._tcp`), or null when none came in time. */
    suspend fun findConnectPort(host: String, timeoutMillis: Long): Int?

    suspend fun connect(host: String, port: Int)

    /** Runs [command] on the watch (`exec:`) and returns what it printed. */
    suspend fun run(command: String): String

    /** Streams [apk] into `cmd package install -S <size>` and returns its answer. */
    suspend fun install(apk: File): String

    suspend fun disconnect()
}

/** The wear APK from Life OS (`apps.wear`), downloaded and checked like the phone's own update. */
data class PreparedApk(val file: File, val versionCode: Long, val versionName: String)

/** Downloads and checks the newest wear APK; throws with a message the owner can read. */
fun interface WearApkSource {
    suspend fun prepare(onProgress: (Float) -> Unit): PreparedApk
}

/** Asks the watch app over the Data Layer, until it answers with at least [minVersionCode] or [timeoutMillis] pass. */
fun interface WatchPing {
    suspend fun ping(minVersionCode: Long, timeoutMillis: Long): WatchInfo?
}

/** The phone's Wi-Fi address now, or null when it is not on Wi-Fi. */
fun interface PhoneWifi {
    fun current(): WifiAddress?
}

/** What the setup keeps for later: the watch it paired with, and the watch's ABIs. */
interface SetupMemory {
    suspend fun pairedHost(): String?

    suspend fun rememberPaired(host: String)

    suspend fun rememberAbis(abis: List<String>)
}

enum class SetupStep { PREPARING, PAIRING, CONNECTING, READING, INSTALLING, GRANTING, STARTING, CHECKING }

enum class SetupProblem {
    NOT_ON_WIFI,
    BAD_ADDRESS,
    BAD_CODE,
    WRONG_CODE,
    PAIRING_EXPIRED,
    WRONG_NETWORK,
    UNREACHABLE,
    PORT_CHANGED,
    NOT_PAIRED,
    CONNECTION_LOST,
    DOWNLOAD_FAILED,
    INSTALL_FAILED,
    GRANT_FAILED,
    NO_ANSWER,
}

data class SetupIssue(val problem: SetupProblem, val message: String)

sealed interface SetupState {
    data object Intro : SetupState

    data class Preparing(val fraction: Float?) : SetupState

    /**
     * The APK is ready: the owner types the pairing code and address, or
     * connects with an earlier pairing ([pairedHost], the watch it paired with).
     */
    data class Pair(val apk: PreparedApk, val pairedHost: String?, val issue: SetupIssue? = null) : SetupState

    /** mDNS gave no connection port: the owner types the one on the main Wireless debugging screen. */
    data class NeedPort(val apk: PreparedApk, val host: String, val issue: SetupIssue? = null) : SetupState

    data class Working(val step: SetupStep) : SetupState

    /** A step after pairing failed; [retry][WatchSetup.retry] goes on from there. */
    data class Failed(val step: SetupStep, val issue: SetupIssue) : SetupState

    /** Installed, allowed to update itself, and answering. Now Wireless debugging should go off again. */
    data class Done(val versionName: String, val watch: WatchInfo, val abis: List<String>, val notes: List<String>) : SetupState
}

/**
 * Settings › Watch › "Set up the watch app" (P19-06): the one-time bootstrap
 * that puts the watch app on the watch without Play and without a computer.
 *
 * 1. Download and check the newest wear APK (before the pairing code, which
 *    expires when the watch's pairing screen closes).
 * 2. Pair with the watch's Wireless debugging (code and IP:port), or reuse an
 *    earlier pairing.
 * 3. Find the connection port over mDNS, else ask for it; connect.
 * 4. Read `ro.product.cpu.abilist`; install the APK; allow "Install unknown
 *    apps" for Life OS (`appops set … REQUEST_INSTALL_PACKAGES allow`), which
 *    One UI Watch has no screen for; allow its notifications; start it once.
 * 5. Disconnect, and wait until the watch app answers over the Data Layer.
 *
 * From then on the watch updates itself with the APKs the phone sends it, and
 * Wireless debugging can stay off.
 */
class WatchSetup(
    private val adb: WatchAdb,
    private val apkSource: WearApkSource,
    private val ping: WatchPing,
    private val wifi: PhoneWifi,
    private val memory: SetupMemory,
    private val packageName: String = "com.gamch1k.lifeos",
    private val launchActivity: String = "com.gamch1k.lifeos.wear.ui.MainActivity",
    private val discoveryMillis: Long = 8_000,
    private val pingMillis: Long = 60_000,
) {
    private val mutableState = MutableStateFlow<SetupState>(SetupState.Intro)
    val state: StateFlow<SetupState> = mutableState.asStateFlow()

    private val mutex = Mutex()
    private var apk: PreparedApk? = null
    private var host: String? = null
    private var port: Int? = null
    private var abis: List<String> = emptyList()
    private val notes = mutableListOf<String>()

    private var current: SetupState
        get() = mutableState.value
        set(value) {
            mutableState.value = value
        }

    /** Step 1: the wear APK. */
    suspend fun prepare() = mutex.withLock { prepareLocked() }

    /** Steps 2–5 with a new pairing: [addressInput] is the IP:port under the pairing code. */
    suspend fun pairAndInstall(addressInput: String, codeInput: String) = mutex.withLock {
        val apk = apk ?: return@withLock
        val address = WatchAddress.parse(addressInput)?.takeIf { it.port != null }
        if (address == null) {
            current = SetupState.Pair(apk, memory.pairedHost(), SetupIssue(SetupProblem.BAD_ADDRESS, "Type the IP address and port shown under the pairing code, such as 192.168.1.23:37421."))
            return@withLock
        }
        val code = PairingCode.parse(codeInput)
        if (code == null) {
            current = SetupState.Pair(apk, memory.pairedHost(), SetupIssue(SetupProblem.BAD_CODE, "The Wi-Fi pairing code has six digits."))
            return@withLock
        }
        val ownWifi = wifi.current()
        if (ownWifi == null) {
            current = SetupState.Pair(apk, memory.pairedHost(), notOnWifi())
            return@withLock
        }
        current = SetupState.Working(SetupStep.PAIRING)
        try {
            adb.pair(address.host, address.port!!, code)
        } catch (e: AdbException) {
            val issue = when (e.failure) {
                AdbFailure.WRONG_CODE -> SetupIssue(
                    SetupProblem.WRONG_CODE,
                    "The watch refused the pairing code. Check the six digits; if the pairing screen closed, tap Pair new device again and type the new code and port.",
                )
                AdbFailure.PAIRING_CLOSED, AdbFailure.REFUSED -> SetupIssue(
                    SetupProblem.PAIRING_EXPIRED,
                    "Nothing answers on port ${address.port}: the pairing screen closed (its code expired with it), or the port is another. On the watch, tap Pair new device again and type the new code and port.",
                )
                AdbFailure.UNREACHABLE, AdbFailure.TIMEOUT -> networkIssue(ownWifi, address.host)
                else -> SetupIssue(SetupProblem.WRONG_CODE, "Pairing failed (${e.message}). Tap Pair new device on the watch again and type the new code and port.")
            }
            current = SetupState.Pair(apk, memory.pairedHost(), issue)
            return@withLock
        }
        host = address.host
        memory.rememberPaired(address.host)
        connectAndContinue(apk, address.host, explicitPort = null)
    }

    /**
     * Steps 3–5 with an earlier pairing (a rescue): [addressInput] is the IP
     * address, and the port if the owner has it, from the Wireless debugging
     * screen.
     */
    suspend fun connectAndInstall(addressInput: String) = mutex.withLock {
        val apk = apk ?: return@withLock
        val address = WatchAddress.parse(addressInput)
        if (address == null) {
            current = SetupState.Pair(apk, memory.pairedHost(), SetupIssue(SetupProblem.BAD_ADDRESS, "Type the watch's IP address from the Wireless debugging screen, such as 192.168.1.23 (the port is optional)."))
            return@withLock
        }
        if (wifi.current() == null) {
            current = SetupState.Pair(apk, memory.pairedHost(), notOnWifi())
            return@withLock
        }
        host = address.host
        connectAndContinue(apk, address.host, address.port)
    }

    /** The port from the main Wireless debugging screen, when mDNS found none. */
    suspend fun usePort(portInput: String) = mutex.withLock {
        val asked = current as? SetupState.NeedPort ?: return@withLock
        val digits = portInput.trim().substringAfterLast(':')
        val port = digits.toIntOrNull()?.takeIf { it in 1..65535 }
        if (port == null) {
            current = asked.copy(issue = SetupIssue(SetupProblem.BAD_ADDRESS, "The port is the number after the colon under \"IP address & Port\", such as 41235."))
            return@withLock
        }
        connectAndContinue(asked.apk, asked.host, port)
    }

    /** "Try again" after [SetupState.Failed]: from the step that failed. */
    suspend fun retry() = mutex.withLock {
        val failed = current as? SetupState.Failed ?: return@withLock
        val apk = apk
        if (failed.step == SetupStep.PREPARING || apk == null) {
            prepareLocked()
            return@withLock
        }
        if (failed.step == SetupStep.CHECKING) {
            check(apk)
            return@withLock
        }
        val knownHost = host
        if (knownHost == null) current = SetupState.Pair(apk, memory.pairedHost()) else connectAndContinue(apk, knownHost, port)
    }

    /** Back to the pairing form (from a failure or the port question). */
    suspend fun backToPairing() = mutex.withLock {
        val apk = apk ?: return@withLock prepareLocked()
        current = SetupState.Pair(apk, memory.pairedHost())
    }

    private suspend fun prepareLocked() {
        current = SetupState.Preparing(null)
        val prepared = try {
            apkSource.prepare { fraction -> current = SetupState.Preparing(fraction) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            current = SetupState.Failed(SetupStep.PREPARING, SetupIssue(SetupProblem.DOWNLOAD_FAILED, "The watch app could not be downloaded from Life OS: ${e.message ?: e.javaClass.simpleName}"))
            return
        }
        apk = prepared
        notes.clear()
        current = SetupState.Pair(prepared, memory.pairedHost())
    }

    private suspend fun connectAndContinue(apk: PreparedApk, host: String, explicitPort: Int?) {
        current = SetupState.Working(SetupStep.CONNECTING)
        val port = explicitPort ?: try {
            adb.findConnectPort(host, discoveryMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (port == null) {
            current = SetupState.NeedPort(apk, host)
            return
        }
        try {
            adb.connect(host, port)
        } catch (e: AdbException) {
            attempt { adb.disconnect() }
            current = when (e.failure) {
                AdbFailure.REFUSED, AdbFailure.PAIRING_CLOSED -> SetupState.NeedPort(
                    apk,
                    host,
                    SetupIssue(
                        SetupProblem.PORT_CHANGED,
                        "Nothing answers on port $port. Wireless debugging picks a new port each time it is turned on: type the port it shows now (the number after the colon under \"IP address & Port\").",
                    ),
                )
                AdbFailure.NOT_PAIRED -> SetupState.Pair(
                    apk,
                    memory.pairedHost(),
                    SetupIssue(SetupProblem.NOT_PAIRED, "The watch doesn't know this phone (any more). Pair it: Wireless debugging › Pair new device."),
                )
                AdbFailure.UNREACHABLE, AdbFailure.TIMEOUT -> SetupState.Pair(apk, memory.pairedHost(), wifi.current()?.let { networkIssue(it, host) } ?: notOnWifi())
                else -> SetupState.Pair(apk, memory.pairedHost(), SetupIssue(SetupProblem.CONNECTION_LOST, "The watch closed the connection (${e.message}). Check that Wireless debugging is on, then try again."))
            }
            return
        }
        this.port = port
        installOverAdb(apk)
    }

    private suspend fun installOverAdb(apk: PreparedApk) {
        var step = SetupStep.READING
        try {
            current = SetupState.Working(step)
            val abilist = attempt { adb.run("getprop ro.product.cpu.abilist") }
            abis = abilist?.let(WatchAbi::parse).orEmpty()
            if (abis.isNotEmpty()) memory.rememberAbis(abis)

            step = SetupStep.INSTALLING
            current = SetupState.Working(step)
            when (val result = ShellOutput.install(adb.install(apk.file))) {
                ShellOutput.Install.Success -> Unit
                is ShellOutput.Install.Failure -> {
                    if (result.code == "INSTALL_FAILED_VERSION_DOWNGRADE") {
                        notes += "The watch already had a newer Life OS; it was kept."
                    } else {
                        current = SetupState.Failed(step, SetupIssue(SetupProblem.INSTALL_FAILED, "The watch refused the install (${result.code}): ${ShellOutput.explain(result)}."))
                        return
                    }
                }
            }

            step = SetupStep.GRANTING
            current = SetupState.Working(step)
            adb.run("appops set $packageName REQUEST_INSTALL_PACKAGES allow")
            val mode = ShellOutput.appOpMode(adb.run("appops get $packageName REQUEST_INSTALL_PACKAGES"), "REQUEST_INSTALL_PACKAGES")
            if (mode != "allow") {
                current = SetupState.Failed(step, SetupIssue(SetupProblem.GRANT_FAILED, "The watch did not allow Life OS to install its updates (it says \"${mode ?: "nothing"}\")."))
                return
            }
            // Optional: the update's one-tap confirmation and the recording notice are notifications.
            attempt { adb.run("pm grant $packageName android.permission.POST_NOTIFICATIONS") }

            step = SetupStep.STARTING
            current = SetupState.Working(step)
            // Starting it once takes it out of the "stopped" state and lets it fetch the token from the phone.
            attempt { adb.run("am start -n $packageName/$launchActivity") }
        } catch (e: AdbException) {
            current = SetupState.Failed(step, SetupIssue(SetupProblem.CONNECTION_LOST, "The connection to the watch broke (${e.message}). Keep Wireless debugging on and try again."))
            return
        } finally {
            attempt { adb.disconnect() }
        }
        check(apk)
    }

    private suspend fun check(apk: PreparedApk) {
        current = SetupState.Working(SetupStep.CHECKING)
        val info = try {
            ping.ping(apk.versionCode, pingMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (info == null) {
            current = SetupState.Failed(
                SetupStep.CHECKING,
                SetupIssue(
                    SetupProblem.NO_ANSWER,
                    "Life OS is installed, but the watch app hasn't answered over Bluetooth yet. Open Life OS on the watch, keep the phone close, and tap Try again.",
                ),
            )
            return
        }
        val doneNotes = notes.toMutableList()
        if (!info.canInstallPackages) doneNotes += "The watch doesn't report \"Install unknown apps\" as allowed, so updates may ask on the watch."
        current = SetupState.Done(apk.versionName, info, abis.ifEmpty { info.abis }, doneNotes)
    }

    private fun notOnWifi() = SetupIssue(SetupProblem.NOT_ON_WIFI, "Connect this phone to Wi-Fi: the same network as the watch.")

    private fun networkIssue(own: WifiAddress, host: String): SetupIssue =
        if (Subnet.contains(own, host) == false) {
            SetupIssue(
                SetupProblem.WRONG_NETWORK,
                "The watch ($host) is not on this phone's Wi-Fi network (${own.address}/${own.prefixLength}). Connect both to the same Wi-Fi.",
            )
        } else {
            SetupIssue(
                SetupProblem.UNREACHABLE,
                "No answer from $host. Keep the watch's screen on, check that it is on the same Wi-Fi as the phone and that Wireless debugging is still on.",
            )
        }

    /** Runs [block], turning any failure but cancellation into null. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
