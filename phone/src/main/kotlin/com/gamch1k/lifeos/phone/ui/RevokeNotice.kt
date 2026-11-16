package com.gamch1k.lifeos.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamch1k.lifeos.core.pairing.PairingLink
import com.gamch1k.lifeos.core.secrets.RevokeStatus
import com.gamch1k.lifeos.phone.graph
import com.gamch1k.lifeos.phone.pairing.RevokeWorker
import kotlinx.coroutines.launch

/**
 * P21-06: a device key the phone let go that Life OS has not confirmed
 * signing out yet (the app keeps trying when online), or gave up on: then
 * the person signs the phone out in Settings → Devices on the web. Shown on
 * Home, in Settings and on the first screen (after signing out).
 */
@Composable
fun RevokeNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val flow = remember { graph.tokens.revokes }
    val revokes by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    if (revokes.isEmpty()) return
    val failed = revokes.any { it.failed }
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(RevokeText.describe(revokes), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    scope.launch {
                        graph.tokens.retryFailedRevokes()
                        RevokeWorker.enqueue(context, now = true)
                    }
                }) { Text("Try again") }
                if (failed) TextButton(onClick = { scope.launch { graph.tokens.dismissFailedRevokes() } }) { Text("Done on the web") }
            }
        }
    }
}

object RevokeText {
    fun describe(revokes: List<RevokeStatus>): String {
        val servers = revokes.map { PairingLink.authorityOf(it.server) }.distinct().joinToString(" and ")
        return if (revokes.any { it.failed }) {
            "Life OS ($servers) did not confirm that this phone's old key is signed out. " +
                "Sign this phone out in Settings → Devices on the web."
        } else {
            "Life OS ($servers) has not confirmed yet that this phone's old key is signed out; the app keeps trying when it is online. " +
                "If this stays, sign this phone out in Settings → Devices on the web."
        }
    }
}
