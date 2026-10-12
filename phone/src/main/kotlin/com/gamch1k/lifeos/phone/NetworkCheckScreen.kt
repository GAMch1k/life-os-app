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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gamch1k.lifeos.core.netcheck.NetworkCheckReport
import com.gamch1k.lifeos.core.netcheck.Verdict

/** P18-01: the first screen, a go/no-go check of the network path to Life OS. */
@Composable
fun NetworkCheckScreen(viewModel: NetworkCheckViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Network check", style = MaterialTheme.typography.headlineMedium)
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

            ServiceTokenCard(state, viewModel)
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
private fun ServiceTokenCard(state: NetworkCheckUiState, viewModel: NetworkCheckViewModel) {
    // Plain remember, not rememberSaveable: the secret must not land in saved instance state.
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Service token (optional)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "A Cloudflare Access service token lets the check call the Life OS API (step 4). " +
                    "It is stored encrypted with the Android Keystore and never shown or reported.",
                style = MaterialTheme.typography.bodySmall,
            )
            val token = state.token
            if (token != null) {
                Text("Client ID: ${token.clientId}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("Client Secret: ${token.clientSecret}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = viewModel::forgetToken) { Text("Forget token") }
            } else {
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("CF-Access-Client-Id") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = clientSecret,
                    onValueChange = { clientSecret = it },
                    label = { Text("CF-Access-Client-Secret") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    viewModel.saveToken(clientId, clientSecret)
                    clientId = ""
                    clientSecret = ""
                }) { Text("Save token") }
            }
            state.tokenMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun copyReport(context: Context, report: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Life OS network check", report))
    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
}
