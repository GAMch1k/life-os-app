package com.gamch1k.lifeos.wear.update

import com.gamch1k.lifeos.core.update.WatchAbi
import com.gamch1k.lifeos.wear.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG-45: the phone decides by [WatchAbi.APK_ABIS] whether a watch can run
 * the wear APK; `ndk.abiFilters` in wear/build.gradle.kts decides what the APK
 * carries (BuildConfig.APK_ABIS). They must agree.
 */
class WearApkAbiTest {
    @Test
    fun `the APK's ABIs are the ones the phone checks the watch against`() {
        assertEquals(WatchAbi.APK_ABIS, BuildConfig.APK_ABIS.split(','))
    }

    @Test
    fun `the APK runs on the Galaxy Watch 8, which runs 32-bit apps only`() {
        assertTrue(WatchAbi.runs(listOf("armeabi-v7a", "armeabi")))
        assertTrue("armeabi-v7a" in BuildConfig.APK_ABIS.split(','))
    }
}
