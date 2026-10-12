package com.gamch1k.lifeos.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gamch1k.lifeos.core.netcheck.NetworkCheckReport
import com.gamch1k.lifeos.core.netcheck.Verdict
import com.gamch1k.lifeos.phone.ui.BackTopBar

/** P18-01: a go/no-go check of the network path to Life OS (Settings › Network check). */
@Composable
fun NetworkCheckScreen(onBack: () -> Unit, viewModel: NetworkCheckViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(topBar = { BackTopBar("Network check", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Can this phone reach Life OS with the hostname hidden (ECH)? " +
                    "Run it on the network you care about, then copy the report.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow HTTP/3 (QUIC)", modifier = Modifier.weight(1f))
                Switch(checked = state.quicAllowed, onCheckedChange = viewModel::setQuicAllowed, enabled = !state.running)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = viewModel::runCheck, enabled = !state.running) {
                    Text(if (state.result == null) "Run check" else "Run again")
                }
                OutlinedButton(
                    onClick = { viewModel.report()?.let { copyReport(context, it) } },
                    enabled = state.result != null && !state.running,
                ) { Text("Copy report") }
                if (state.running) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            }

            state.error?.let { ResultCard("Error", listOf(it), highlight = MaterialTheme.colorScheme.errorContainer) }

            state.result?.let { result ->
                val verdict = NetworkCheckReport.verdict(result)
                NetworkCheckReport.sections(result).forEachIndexed { index, section ->
                    val highlight = if (index == 0) verdictColor(verdict) else null
                    ResultCard(section.title, section.lines, highlight)
                }
            }

            ServiceTokenLine(state)
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun verdictColor(verdict: Verdict): Color = when (verdict) {
    Verdict.ECH_WORKS -> Color(0xFF2E7D32).copy(alpha = 0.25f)
    Verdict.ECH_NOT_USED -> Color(0xFFF9A825).copy(alpha = 0.3f)
    Verdict.CRONET_FAILED -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun ResultCard(title: String, lines: List<String>, highlight: Color? = null) {
    val colors = if (highlight != null) CardDefaults.cardColors(containerColor = highlight) else CardDefaults.cardColors()
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    lines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceTokenLine(state: NetworkCheckUiState) {
    val token = state.token
    val text = if (token != null) {
        "Step 4 uses the service token from Settings (Client ID ${token.clientId})."
    } else {
        "No service token is set up, so step 4 (an authenticated request) is skipped."
    }
    Text(text, style = MaterialTheme.typography.bodySmall)
}

private fun copyReport(context: Context, report: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Life OS network check", report))
    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
}
