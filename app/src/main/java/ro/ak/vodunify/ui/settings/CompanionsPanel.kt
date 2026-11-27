package ro.ak.vodunify.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import ro.ak.vodunify.ui.components.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import ro.ak.vodunify.ui.components.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.ui.components.readable

/** „Aplicațiile AK”: instalează / actualizează aplicațiile mici din ultimul release. */
@Composable
fun CompanionsPanel(viewModel: CompanionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshInstalled() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
        Text("AK Scrobbler", fontWeight = FontWeight.Medium)
        OutlinedButton(onClick = viewModel::check, enabled = !state.loading) {
            Text(if (state.loading) "Verific…" else "Verifică AK Scrobbler")
        }
        if (state.needsPermission) {
            Text("Permite AK VOD Unify să instaleze aplicații, apoi apasă din nou.", color = MaterialTheme.colorScheme.error)
            Button(onClick = { context.startActivity(viewModel.permissionIntent()) }) { Text("Deschide permisiunea") }
        }
        state.items.forEach { item ->
            val c = item.companion
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(c.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            item.apkUrl == null -> "nu e în ultimul release"
                            !item.installed -> "neinstalată · ${c.hint}"
                            item.actionable -> "actualizare la ${item.remoteName} · ${c.hint}"
                            else -> "la zi · ${c.hint}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.readable(),
                    )
                }
                if (state.downloading == c) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.weight(0.5f))
                } else if (item.actionable) {
                    Button(onClick = { viewModel.install(item) }, enabled = state.downloading == null) {
                        Text(if (item.installed) "Actualizează" else "Instalează")
                    }
                }
            }
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.readable()) }
    }
}
