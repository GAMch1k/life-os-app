package com.gamch1k.lifeos.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real preference DataStores in a temporary folder, one scope per test. */
class TestDataStores : ExternalResource() {
    private val folder = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private var count = 0

    val root: File get() = folder.root

    override fun before() {
        folder.create()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    override fun after() {
        scope.cancel()
        folder.delete()
    }

    fun create(): DataStore<Preferences> {
        val file = File(folder.root, "store-${count++}.preferences_pb")
        return PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    fun newFile(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): File = File(folder.root, name).apply { writeBytes(bytes) }
}
