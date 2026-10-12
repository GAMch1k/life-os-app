package com.gamch1k.lifeos.phone.watchsetup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamch1k.lifeos.core.watchsetup.PhoneWifi
import com.gamch1k.lifeos.core.watchsetup.SetupMemory
import com.gamch1k.lifeos.core.watchsetup.WifiAddress
import com.gamch1k.lifeos.phone.watch.WatchUpdateManager
import kotlinx.coroutines.flow.first
import java.net.Inet4Address

/** The phone's Wi-Fi IPv4 address and prefix, from the default network when it is Wi-Fi. */
class AndroidPhoneWifi(context: Context) : PhoneWifi {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun current(): WifiAddress? {
        val manager = connectivity ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val link = manager.getLinkProperties(network) ?: return null
        val address = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return WifiAddress(address.address.hostAddress ?: return null, address.prefixLength)
    }
}

/** The watch the setup paired with (for a rescue), and the ABIs it read. */
class StoredSetupMemory(private val settings: DataStore<Preferences>, private val watchUpdates: WatchUpdateManager) : SetupMemory {
    override suspend fun pairedHost(): String? = settings.data.first()[PAIRED_HOST]

    override suspend fun rememberPaired(host: String) {
        settings.edit { it[PAIRED_HOST] = host }
    }

    override suspend fun rememberAbis(abis: List<String>) = watchUpdates.rememberAbis(abis)

    private companion object {
        val PAIRED_HOST = stringPreferencesKey("watch-adb-paired-host")
    }
}
