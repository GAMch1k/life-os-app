package com.gamch1k.lifeos.core.watchsetup

import com.gamch1k.lifeos.core.wearlink.WatchInfo
import com.gamch1k.lifeos.core.wearlink.WearLink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bootstrap's state machine with the ADB layer, the download, the Data Layer and the Wi-Fi faked. */
class WatchSetupTest {
    private class FakeAdb : WatchAdb {
        val calls = mutableListOf<String>()
        var pairFailure: AdbFailure? = null
        var discovered: Int? = 41235
        val connectFailures = ArrayDeque<AdbFailure>()
        var abilist = "arm64-v8a,armeabi-v7a,armeabi\n"
        var installOutput = "Success\n"
        var appOpsMode = "allow"
        var failOn: String? = null
        var connected = false

        override suspend fun pair(host: String, port: Int, code: String) {
            calls += "pair $host:$port $code"
            pairFailure?.let { throw AdbException(it, "pairing failed") }
        }

        override suspend fun findConnectPort(host: String, timeoutMillis: Long): Int? {
            calls += "discover $host"
            return discovered
        }

        override suspend fun connect(host: String, port: Int) {
            calls += "connect $host:$port"
            connectFailures.removeFirstOrNull()?.let { throw AdbException(it, "connect failed") }
            connected = true
        }

        override suspend fun run(command: String): String {
            check(connected) { "not connected" }
            calls += "run $command"
            if (failOn != null && command.startsWith(failOn!!)) throw AdbException(AdbFailure.OTHER, "stream closed")
            return when {
                command == "getprop ro.product.cpu.abilist" -> abilist
                command.startsWith("appops get") -> "REQUEST_INSTALL_PACKAGES: $appOpsMode; time=+1s ago\n"
                else -> ""
            }
        }

        override suspend fun install(apk: File): String {
            check(connected) { "not connected" }
            calls += "install ${apk.name}"
            if (failOn == "install") throw AdbException(AdbFailure.OTHER, "stream closed")
            return installOutput
        }

        override suspend fun disconnect() {
            calls += "disconnect"
            connected = false
        }
    }

    private class Memory : SetupMemory {
        var host: String? = null
        var abis: List<String> = emptyList()
        override suspend fun pairedHost() = host
        override suspend fun rememberPaired(host: String) {
            this.host = host
        }
        override suspend fun rememberAbis(abis: List<String>) {
            this.abis = abis
        }
    }

    private val apk = PreparedApk(File("lifeos-wear-5.apk"), versionCode = 5, versionName = "0.4.0")
    private val watchInfo = WatchInfo(versionCode = 5, versionName = "0.4.0", sdkInt = 36, abis = listOf("arm64-v8a"), capabilities = listOf(WearLink.WATCH_CAN_UPDATE), canInstallPackages = true)

    private val adb = FakeAdb()
    private val memory = Memory()
    private var wifi: WifiAddress? = WifiAddress("192.168.1.10", 24)
    private var answer: WatchInfo? = watchInfo
    private var downloadFails = false
    private val pings = mutableListOf<Long>()

    private val setup = WatchSetup(
        adb = adb,
        apkSource = { progress ->
            if (downloadFails) throw IllegalStateException("the download stopped: offline")
            progress(0.5f)
            apk
        },
        ping = { min, _ ->
            pings += min
            answer
        },
        wifi = { wifi },
        memory = memory,
    )

    private inline fun <reified T : SetupState> state(): T {
        val current = setup.state.value
        assertTrue("expected ${T::class.simpleName}, got $current", current is T)
        return current as T
    }

    @Test
    fun `the whole bootstrap - download, pair, discover, install, allow, start, answer`() = runTest {
        assertEquals(SetupState.Intro, setup.state.value)
        setup.prepare()
        assertEquals(apk, state<SetupState.Pair>().apk)
        assertNull(state<SetupState.Pair>().pairedHost)

        setup.pairAndInstall(" 192.168.1.23 : 37421 ", "123 456")

        val done = state<SetupState.Done>()
        assertEquals("0.4.0", done.versionName)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), done.abis)
        assertEquals(watchInfo, done.watch)
        assertTrue(done.notes.isEmpty())
        assertEquals(
            listOf(
                "pair 192.168.1.23:37421 123456",
                "discover 192.168.1.23",
                "connect 192.168.1.23:41235",
                "run getprop ro.product.cpu.abilist",
                "install lifeos-wear-5.apk",
                "run appops set com.gamch1k.lifeos REQUEST_INSTALL_PACKAGES allow",
                "run appops get com.gamch1k.lifeos REQUEST_INSTALL_PACKAGES",
                "run pm grant com.gamch1k.lifeos android.permission.POST_NOTIFICATIONS",
                "run cmd deviceidle whitelist +com.gamch1k.lifeos",
                "run am start -n com.gamch1k.lifeos/com.gamch1k.lifeos.wear.ui.MainActivity",
                "disconnect",
            ),
            adb.calls,
        )
        assertEquals("the pairing is remembered for a rescue", "192.168.1.23", memory.host)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), memory.abis)
        assertEquals("the watch must answer with the new version", listOf(5L), pings)
    }

    @Test
    fun `a failed download can be retried`() = runTest {
        downloadFails = true
        setup.prepare()
        val failed = state<SetupState.Failed>()
        assertEquals(SetupStep.PREPARING, failed.step)
        assertEquals(SetupProblem.DOWNLOAD_FAILED, failed.issue.problem)
        assertTrue(failed.issue.message, "offline" in failed.issue.message)
        downloadFails = false
        setup.retry()
        state<SetupState.Pair>()
    }

    @Test
    fun `typos are caught before anything is sent`() = runTest {
        setup.prepare()
        setup.pairAndInstall("192.168.1.23", "123456")
        assertEquals(SetupProblem.BAD_ADDRESS, state<SetupState.Pair>().issue?.problem)
        setup.pairAndInstall("192.168.1.300:37421", "123456")
        assertEquals(SetupProblem.BAD_ADDRESS, state<SetupState.Pair>().issue?.problem)
        setup.pairAndInstall("192.168.1.23:37421", "12345")
        assertEquals(SetupProblem.BAD_CODE, state<SetupState.Pair>().issue?.problem)
        assertTrue(adb.calls.isEmpty())
    }

    @Test
    fun `not on Wi-Fi is said before pairing`() = runTest {
        setup.prepare()
        wifi = null
        setup.pairAndInstall("192.168.1.23:37421", "123456")
        assertEquals(SetupProblem.NOT_ON_WIFI, state<SetupState.Pair>().issue?.problem)
        assertTrue(adb.calls.isEmpty())
    }

    @Test
    fun `a wrong code and a closed pairing screen are told apart`() = runTest {
        setup.prepare()
        adb.pairFailure = AdbFailure.WRONG_CODE
        setup.pairAndInstall("192.168.1.23:37421", "123456")
        assertEquals(SetupProblem.WRONG_CODE, state<SetupState.Pair>().issue?.problem)
        adb.pairFailure = AdbFailure.PAIRING_CLOSED
        setup.pairAndInstall("192.168.1.23:37421", "123456")
        val issue = state<SetupState.Pair>().issue
        assertEquals(SetupProblem.PAIRING_EXPIRED, issue?.problem)
        assertTrue(issue!!.message, "Pair new device again" in issue.message)
        assertNull("a failed pairing is not remembered", memory.host)
    }

    @Test
    fun `no answer from an address outside the phone's subnet is the wrong network`() = runTest {
        setup.prepare()
        adb.pairFailure = AdbFailure.UNREACHABLE
        setup.pairAndInstall("10.0.0.5:37421", "123456")
        assertEquals(SetupProblem.WRONG_NETWORK, state<SetupState.Pair>().issue?.problem)
        setup.pairAndInstall("192.168.1.23:37421", "123456")
        assertEquals("same subnet: the watch is asleep or off Wi-Fi", SetupProblem.UNREACHABLE, state<SetupState.Pair>().issue?.problem)
    }

    @Test
    fun `without mDNS the owner types the connection port`() = runTest {
        setup.prepare()
        adb.discovered = null
        setup.pairAndInstall("192.168.1.23:37421", "123456")
        val asked = state<SetupState.NeedPort>()
        assertEquals("192.168.1.23", asked.host)
        assertNull(asked.issue)
        setup.usePort("abc")
        assertEquals(SetupProblem.BAD_ADDRESS, state<SetupState.NeedPort>().issue?.problem)
        setup.usePort("192.168.1.23:40001")
        state<SetupState.Done>()
        assertTrue("connect 192.168.1.23:40001" in adb.calls)
    }

    @Test
    fun `a port that changed asks for the new one`() = runTest {
        setup.prepare()
        adb.connectFailures += AdbFailure.REFUSED
        setup.pairAndInstall("192.168.1.23:37421", "123456")
        val asked = state<SetupState.NeedPort>()
        assertEquals(SetupProblem.PORT_CHANGED, asked.issue?.problem)
        setup.usePort("40002")
        state<SetupState.Done>()
    }

    @Test
    fun `a watch that no longer trusts the phone asks for a new pairing`() = runTest {
        setup.prepare()
        adb.connectFailures += AdbFailure.NOT_PAIRED
        setup.connectAndInstall("192.168.1.23")
        assertEquals(SetupProblem.NOT_PAIRED, state<SetupState.Pair>().issue?.problem)
        assertTrue("no pairing was tried", adb.calls.none { it.startsWith("pair") })
    }

    @Test
    fun `a rescue reuses the pairing, with or without the port`() = runTest {
        memory.host = "192.168.1.23"
        setup.prepare()
        assertEquals("192.168.1.23", state<SetupState.Pair>().pairedHost)
        setup.connectAndInstall("192.168.1.23:45555")
        state<SetupState.Done>()
        assertEquals("connect 192.168.1.23:45555", adb.calls.first())
        assertTrue(adb.calls.none { it.startsWith("pair") || it.startsWith("discover") })
    }

    @Test
    fun `an install the watch refuses says why, and Try again reconnects`() = runTest {
        setup.prepare()
        adb.installOutput = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.gamch1k.lifeos signatures do not match]\n"
        setup.pairAndInstall("192.168.1.23:37421", "123456")
        val failed = state<SetupState.Failed>()
        assertEquals(SetupStep.INSTALLING, failed.step)
        assertEquals(SetupProblem.INSTALL_FAILED, failed.issue.problem)
        assertTrue(failed.issue.message, "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in failed.issue.message && "uninstall" in failed.issue.message)
        assertEquals("the connection is closed after a failure", "disconnect", adb.calls.last())

        adb.installOutput = "Success\n"
        adb.calls.clear()
        setup.retry()
        state<SetupState.Done>()
        assertEquals("connect 192.168.1.23:41235", adb.calls.first())
    }

    @Test
    fun `a watch with a newer version keeps it and the setup goes on`() = runTest {
        setup.prepare()
        adb.installOutput = "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]\n"
        setup.pairAndInstall("192.168.1.23:37421", "123456")
        assertEquals(1, state<SetupState.Done>().notes.size)
    }

    @Test
    fun `the install permission must really be allowed`() = runTest {
        setup.prepare()
        adb.appOpsMode = "default"
        setup.pairAndInstall("192.168.1.23:37421", "123456")
        val failed = state<SetupState.Failed>()
        assertEquals(SetupStep.GRANTING, failed.step)
        assertEquals(SetupProblem.GRANT_FAILED, failed.issue.problem)
    }

    @Test
    fun `optional steps may fail, a broken connection may not`() = runTest {
        setup.prepare()
        adb.failOn = "pm grant"
        adb.abilist = ""
        setup.pairAndInstall("192.168.1.23:37421", "123456")
        assertEquals("ABIs fall back to what the watch app reports", listOf("arm64-v8a"), state<SetupState.Done>().abis)

        adb.failOn = "install"
        adb.calls.clear()
        setup.backToPairing()
        setup.pairAndInstall("192.168.1.23:37421", "123456")
        val failed = state<SetupState.Failed>()
        assertEquals(SetupProblem.CONNECTION_LOST, failed.issue.problem)
        assertEquals(SetupStep.INSTALLING, failed.step)
    }

    @Test
    fun `a watch app that doesn't answer is retried by pinging only`() = runTest {
        setup.prepare()
        answer = null
        setup.pairAndInstall("192.168.1.23:37421", "123456")
        val failed = state<SetupState.Failed>()
        assertEquals(SetupStep.CHECKING, failed.step)
        assertEquals(SetupProblem.NO_ANSWER, failed.issue.problem)
        answer = watchInfo.copy(canInstallPackages = false)
        adb.calls.clear()
        setup.retry()
        val done = state<SetupState.Done>()
        assertTrue("no ADB again", adb.calls.isEmpty())
        assertEquals(1, done.notes.size)
    }

    // ---- What the owner types, and what the watch's shell says

    @Test
    fun `addresses and codes are parsed leniently but strictly`() {
        assertEquals(WatchAddress("192.168.1.23", 37421), WatchAddress.parse("192.168.1.23:37421"))
        assertEquals(WatchAddress("192.168.1.23", 37421), WatchAddress.parse(" 192.168.1.23 ：37421 "))
        assertEquals(WatchAddress("192.168.1.23", null), WatchAddress.parse("192.168.1.23"))
        assertEquals(WatchAddress("fe80::1", 5555), WatchAddress.parse("[fe80::1]:5555"))
        assertEquals(WatchAddress("watch.local", 5555), WatchAddress.parse("watch.local:5555"))
        assertNull(WatchAddress.parse(""))
        assertNull(WatchAddress.parse("192.168.1.23:0"))
        assertNull(WatchAddress.parse("192.168.1.23:70000"))
        assertNull(WatchAddress.parse("192.168.1.23:port"))
        assertNull(WatchAddress.parse("256.1.1.1:5555"))
        assertNull(WatchAddress.parse("fe80::1"))
        assertNull(WatchAddress.parse("a;b:5555"))
        assertEquals("123456", PairingCode.parse(" 123 456 "))
        assertNull(PairingCode.parse("12345"))
        assertNull(PairingCode.parse("1234567"))
        assertNull(PairingCode.parse("12a456"))
    }

    @Test
    fun `the subnet check`() {
        val wifi = WifiAddress("192.168.1.10", 24)
        assertEquals(true, Subnet.contains(wifi, "192.168.1.200"))
        assertEquals(false, Subnet.contains(wifi, "192.168.2.1"))
        assertEquals(true, Subnet.contains(WifiAddress("10.1.2.3", 8), "10.200.0.1"))
        assertNull(Subnet.contains(wifi, "fe80::1"))
        assertNull(Subnet.contains(wifi, "watch.local"))
    }

    @Test
    fun `the shell's answers`() {
        assertEquals(ShellOutput.Install.Success, ShellOutput.install("Success\n"))
        assertEquals(
            ShellOutput.Install.Failure("INSTALL_FAILED_INSUFFICIENT_STORAGE", "Failed to override installation location"),
            ShellOutput.install("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE: Failed to override installation location]"),
        )
        assertEquals(ShellOutput.Install.Failure("INSTALL_FAILED_VERSION_DOWNGRADE", ""), ShellOutput.install("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"))
        assertEquals("UNKNOWN", (ShellOutput.install("cmd: Can't find service: package") as ShellOutput.Install.Failure).code)
        assertEquals("allow", ShellOutput.appOpMode("REQUEST_INSTALL_PACKAGES: allow; time=+5s ago\n", "REQUEST_INSTALL_PACKAGES"))
        assertEquals("default", ShellOutput.appOpMode("Uid mode: x\nREQUEST_INSTALL_PACKAGES: default\n", "REQUEST_INSTALL_PACKAGES"))
        assertNull(ShellOutput.appOpMode("", "REQUEST_INSTALL_PACKAGES"))
    }

    @Test
    fun `an install refused for the ABI names the one the watch app is built for`() {
        val failure = ShellOutput.install("Failure [INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries, res=-113]") as ShellOutput.Install.Failure
        assertEquals("INSTALL_FAILED_NO_MATCHING_ABIS", failure.code)
        assertEquals("the watch doesn't run the ABI the watch app is built for (armeabi-v7a)", ShellOutput.explain(failure))
    }
}
