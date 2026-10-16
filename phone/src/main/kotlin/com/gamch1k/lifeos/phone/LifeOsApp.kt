package com.gamch1k.lifeos.phone

import android.app.Application
import android.content.Context
import com.gamch1k.lifeos.core.wearlink.WearLinkHost
import com.gamch1k.lifeos.core.wearlink.WearRelay
import com.gamch1k.lifeos.core.wearlink.WearTokenSource
import com.gamch1k.lifeos.phone.push.LifeOsNotifications
import com.gamch1k.lifeos.phone.today.TodayRefreshWorker
import com.gamch1k.lifeos.phone.update.UpdateWorker
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * The app. It is the watch link's [WearLinkHost] (docs/wear.md): the phone's
 * Data Layer listener takes the token source and the relay from here.
 */
class LifeOsApp : Application(), WearLinkHost {
    lateinit var graph: AppGraph
        private set

    override val wearTokenSource: WearTokenSource get() = graph.wearLink.wearTokenSource
    override val wearRelay: WearRelay get() = graph.wearLink.wearRelay

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        LifeOsNotifications.createChannels(this)
        UpdateWorker.schedule(this)
        TodayRefreshWorker.schedule(this)
        graph.scope.launch {
            // The 0.1 network check kept the token in SharedPreferences.
            if (graph.tokens.migrateFrom(this@LifeOsApp)) graph.credentialsChanged()
            // Captures whose work was lost (force stop, restore) go back to WorkManager.
            graph.queue.resumeAll()
            graph.captures.pruneSent(Instant.now(), Duration.ofDays(7))
            graph.queue.pruneOrphans()
        }
        // Snoozed notifications: late ones (after a force stop) come back, the alarm is set again.
        graph.scope.launch { graph.snoozes.wake() }
    }
}

val Context.graph: AppGraph get() = (applicationContext as LifeOsApp).graph
