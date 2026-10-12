package com.gamch1k.lifeos.wear.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.gamch1k.lifeos.wear.LifeOsWear
import kotlinx.coroutines.launch

/** PackageInstaller's verdict on the watch's own update. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = LifeOsWear.graph(context)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val versionCode = intent.getLongExtra(EXTRA_VERSION_CODE, 0)
        val versionName = intent.getStringExtra(EXTRA_VERSION_NAME).orEmpty()
        val pending = goAsync()
        graph.scope.launch {
            try {
                graph.selfUpdater.onInstallStatus(status, message, confirm, versionCode, versionName)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_VERSION_CODE = "com.gamch1k.lifeos.wear.VERSION_CODE"
        const val EXTRA_VERSION_NAME = "com.gamch1k.lifeos.wear.VERSION_NAME"
    }
}

/**
 * Android starts the new version after an update with MY_PACKAGE_REPLACED:
 * the app tells the phone at once (LifeOsWear's start does the work).
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = LifeOsWear.graph(context)
        val pending = goAsync()
        graph.scope.launch {
            try {
                graph.selfUpdater.onAppStart()
            } finally {
                pending.finish()
            }
        }
    }
}
