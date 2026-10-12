package com.gamch1k.lifeos.phone

import android.app.Application
import android.content.Context
import com.gamch1k.lifeos.phone.update.UpdateWorker
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

class LifeOsApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        UpdateWorker.schedule(this)
        graph.scope.launch {
            // The 0.1 network check kept the token in SharedPreferences.
            graph.tokens.migrateFrom(this@LifeOsApp)
            // Captures whose work was lost (force stop, restore) go back to WorkManager.
            graph.queue.resumeAll()
            graph.captures.pruneSent(Instant.now(), Duration.ofDays(7))
        }
    }
}

val Context.graph: AppGraph get() = (applicationContext as LifeOsApp).graph
