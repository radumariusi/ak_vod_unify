package ro.ak.vodunify.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ro.ak.vodunify.core.connector.DeviceAuthConnector
import ro.ak.vodunify.core.connector.DeviceCode
import ro.ak.vodunify.core.tracking.SyncStatus
import ro.ak.vodunify.ui.components.QrCode
import ro.ak.vodunify.ui.components.layoutSpec
import ro.ak.vodunify.ui.components.openUrl
import java.text.DateFormat
import java.util.Date

/** Cardul unui cont (Trakt, SIMKL, WeTrakr): conectare cu cod, stare sincronizare, deconectare. */
@Composable
fun AccountCard(connector: DeviceAuthConnector, viewModel: AccountsViewModel) {
    val account by viewModel.accounts.getValue(connector.id).collectAsStateWithLifecycle()
    val syncState by viewModel.syncStates.getValue(connector.id).collectAsStateWithLifecycle()
    val loginMap by viewModel.login.collectAsStateWithLifecycle()
    val status by viewModel.syncStatus.collectAsStateWithLifecycle()
    val login = loginMap[connector.id] ?: LoginState.Idle
    val acc = account

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(connector.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    when {
                        !connector.isConfigured -> "Lipsesc cheile API"
                        acc != null -> "Conectat"
                        else -> "Neconectat"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (acc != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                !connector.isConfigured -> MissingKeys(connector)
                acc != null -> {
                    Text("Cont: ${acc.displayName?.takeIf { it.isNotBlank() }?.let { "$it (${acc.username})" } ?: acc.username}")
                    val last = syncState?.lastSyncAt?.takeIf { it > 0 }
                    Text(
                        when {
                            status is SyncStatus.Running -> "Se sincronizează…"
                            last != null -> "Ultima sincronizare: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))}"
                            else -> "Încă nesincronizat"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    syncState?.lastError?.let {
                        Text("Ultima eroare: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = viewModel::syncNow, enabled = status !is SyncStatus.Running) { Text("Sincronizează acum") }
                        OutlinedButton(onClick = { viewModel.signOut(connector) }) { Text("Deconectează") }
                        if (status is SyncStatus.Running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                else -> LoginFlow(connector, login, viewModel)
            }
        }
    }
}

@Composable
private fun LoginFlow(connector: DeviceAuthConnector, login: LoginState, viewModel: AccountsViewModel) {
    when (login) {
        LoginState.Idle -> {
            Text(
                "Conectează contul ca să vezi „Continuă vizionarea”, „Următorul episod” și lista „De văzut”. " +
                    "Primești un cod pe care îl introduci pe site-ul ${connector.displayName}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (connector.id == "wetrakr") {
                Text(
                    "Experimental: WeTrakr nu are încă API public, așa că folosim protocolul add-on-ului lor oficial pentru Kodi. " +
                        "Lista „De văzut” se citește, dar nu se poate modifica de aici.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { viewModel.startLogin(connector) }) { Text("Conectează ${connector.displayName}") }
        }
        LoginState.Requesting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Se cere codul…")
        }
        is LoginState.WaitingForUser -> CodePanel(connector, login.code, onCancel = { viewModel.cancelLogin(connector) })
        is LoginState.Error -> {
            Text(login.message, color = MaterialTheme.colorScheme.error)
            Button(onClick = { viewModel.startLogin(connector) }) { Text("Încearcă din nou") }
        }
    }
}

@Composable
private fun CodePanel(connector: DeviceAuthConnector, code: DeviceCode, onCancel: () -> Unit) {
    val spec = layoutSpec()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    var remaining by remember(code) { mutableLongStateOf(secondsLeft(code)) }
    LaunchedEffect(code) {
        runCatching { focus.requestFocus() }
        while (remaining > 0) {
            delay(1000)
            remaining = secondsLeft(code)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!spec.isCompact) QrCode(code.verificationUrl, size = if (spec.isTv) 170.dp else 140.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("1. Deschide ${code.verificationUrl}" + if (!spec.isCompact) " (sau scanează codul QR)" else "")
            Text("2. Loghează-te în ${connector.displayName} și introdu codul:")
            Text(
                code.userCode,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = if (spec.isTv) 44.sp else 34.sp,
                letterSpacing = 4.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "3. Apasă „Yes” / „Allow”. Aplicația se conectează singură. Expiră în ${remaining / 60}:${"%02d".format(remaining % 60)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { context.openUrl(code.verificationUrl) }, modifier = Modifier.focusRequester(focus)) { Text("Deschide pagina") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code.userCode)) }) { Text("Copiază codul") }
                TextButton(onClick = onCancel) { Text("Anulează") }
            }
        }
    }
}

private fun secondsLeft(code: DeviceCode): Long =
    ((code.expiresAt.toEpochMilli() - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

@Composable
private fun MissingKeys(connector: DeviceAuthConnector) {
    val steps = when (connector.id) {
        "trakt" ->
            "1. Pe trakt.tv/oauth/applications → New Application (Redirect uri: urn:ietf:wg:oauth:2.0:oob).\n" +
                "2. În GitHub: Settings → Secrets and variables → Actions → secretul TRAKT_CLIENT_ID (Client ID). Client secret nu mai e necesar.\n" +
                "3. Construiește un APK nou (Actions → Release APK) și actualizează aplicația."
        "simkl" ->
            "1. Pe simkl.com/settings/developer/ creează o aplicație OAuth 2.0, tip „TV, devices & command line”.\n" +
                "2. În GitHub: Settings → Secrets and variables → Actions → secretul SIMKL_CLIENT_ID (Client ID). Nu e nevoie de secret.\n" +
                "3. Construiește un APK nou (Actions → Release APK) și actualizează aplicația."
        else -> "Cheile pentru ${connector.displayName} se adaugă ca secrete în GitHub, apoi se construiește un APK nou."
    }
    Text(steps, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
