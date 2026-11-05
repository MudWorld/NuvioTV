@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.watchparty

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

/** "Update available" on the TV: downloads the new build from the WatchParty server and installs it. */
@Composable
fun WatchPartyUpdateDialog() {
    val update by WatchPartyExtras.update.collectAsState()
    val progress by WatchPartyExtras.updateProgress.collectAsState()
    val info = update ?: return
    val scope = rememberCoroutineScope()
    val installFocus = remember { FocusRequester() }
    LaunchedEffect(info.build) { runCatching { installFocus.requestFocus() } }

    Dialog(onDismissRequest = { WatchPartyExtras.postponeUpdate(info) }) {
        Column(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .background(NuvioTheme.colors.BackgroundElevated, RoundedCornerShape(20.dp))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Update verfügbar",
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "WatchParty ${info.versionName} ist da (installiert: Build ${BuildConfig.WATCHPARTY_BUILD}). " +
                    "Deine Profile, Anmeldung und Einstellungen bleiben erhalten.",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            if (info.notes.isNotBlank()) {
                Text(text = info.notes, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            }
            progress?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextPrimary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { scope.launch { WatchPartyExtras.downloadAndInstall(info) } },
                    modifier = Modifier.focusRequester(installFocus)
                ) { Text("Jetzt installieren") }
                Button(onClick = { WatchPartyExtras.postponeUpdate(info) }) { Text("Später") }
            }
        }
    }
}
