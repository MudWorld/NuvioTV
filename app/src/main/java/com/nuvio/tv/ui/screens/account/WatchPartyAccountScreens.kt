@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.watchparty.WatchPartyAccount
import com.nuvio.tv.watchparty.WatchPartyDesktopImport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * WatchParty sign-in: user name and password of the user's own account. After sign-in the TV
 * either restores its own data from the account (and restarts) or copies addons and plugins from
 * the PC once.
 */
@Composable
fun WatchPartyLoginScreen(
    onBackPress: () -> Unit,
    onDone: () -> Unit,
    allowSkip: Boolean = false
) {
    BackHandler { onBackPress() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by WatchPartyAccount.state.collectAsState()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val firstField = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }

    fun submit() {
        if (working || username.isBlank() || password.isEmpty()) return
        working = true
        scope.launch {
            WatchPartyAccount.setMessage(null)
            if (WatchPartyAccount.login(username, password).isFailure) {
                working = false
                return@launch
            }
            WatchPartyAccount.setMessage("Angemeldet, Daten werden geladen …")
            val restored = runCatching { WatchPartyAccount.pullAfterLogin() }.getOrDefault(false)
            if (restored) {
                WatchPartyAccount.setMessage("Deine Daten sind da. WatchParty startet neu …")
                // The restart runs outside this screen's scope; onDone() marks the first-launch
                // sign-in as seen and may remove this screen right away.
                WatchPartyAccount.restartSoon(context)
                onDone()
                return@launch
            }
            WatchPartyAccount.setMessage("Addons und Plugins werden vom PC übernommen …")
            val imported = runCatching { WatchPartyDesktopImport.run(context) }.getOrNull()
            WatchPartyAccount.setMessage(
                imported?.let { "Vom PC übernommen: ${it.addons} Addons, ${it.repositories} Plugin-Quellen" }
            )
            WatchPartyAccount.requestSync()
            delay(1_500)
            working = false
            onDone()
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .background(color = NuvioTheme.colors.BackgroundElevated, shape = RoundedCornerShape(20.dp))
                .padding(NuvioTheme.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Text(
                text = "Bei WatchParty anmelden",
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Mit deinem WatchParty-Konto kommen Addons, Plugins und Einstellungen auf diesen Fernseher.",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center
            )
            InputField(
                value = username,
                onValueChange = { username = it },
                placeholder = "Benutzername",
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth().focusRequester(firstField)
            )
            InputField(
                value = password,
                onValueChange = { password = it },
                placeholder = "Passwort",
                isPassword = true,
                onImeAction = { submit() },
                modifier = Modifier.fillMaxWidth()
            )
            val status = state.error ?: state.message
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.error != null) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
            WatchPartyButton(text = if (working) "Bitte warten …" else "Anmelden", onClick = { submit() })
            if (allowSkip) {
                WatchPartyButton(text = "Ohne Konto fortfahren", onClick = { if (!working) onDone() }, secondary = true)
            }
        }
    }
}

@Composable
private fun WatchPartyButton(text: String, onClick: () -> Unit, secondary: Boolean = false) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = if (secondary) NuvioTheme.colors.BackgroundCard else NuvioTheme.colors.Secondary,
            focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
            contentColor = if (secondary) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.OnSecondary,
            focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = text, modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs), fontWeight = FontWeight.Medium)
    }
}

/** Account section in Settings. */
@Composable
fun WatchPartyAccountPanel(
    onOpenLogin: () -> Unit,
    initialFocusRequester: FocusRequester?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by WatchPartyAccount.state.collectAsState()
    val firstModifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (state.username == null) {
            item(key = "wp_info") {
                Text(
                    text = "Melde dich mit deinem WatchParty-Konto an. Dann werden Addons, Plugins, " +
                        "Bibliothek und Fortschritt alle 10 Minuten und beim Pausieren gesichert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            item(key = "wp_login") {
                SettingsActionButton(
                    icon = Icons.Default.VpnKey,
                    title = "Anmelden",
                    subtitle = "Benutzername und Passwort",
                    onClick = onOpenLogin,
                    modifier = firstModifier
                )
            }
            return@LazyColumn
        }
        item(key = "wp_user") {
            val last = state.lastSyncAt.takeIf { it > 0 }
                ?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
            Text(
                text = "Angemeldet als ${state.username}" + (last?.let { " · zuletzt gesichert $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary
            )
        }
        (state.error ?: state.message)?.let { status ->
            item(key = "wp_status") {
                Text(text = status, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            }
        }
        item(key = "wp_sync") {
            SettingsActionButton(
                icon = Icons.Default.Sync,
                title = if (state.busy) "Wird gesichert …" else "Jetzt sichern",
                subtitle = "Sonst automatisch alle 10 Minuten und beim Pausieren",
                onClick = {
                    scope.launch {
                        WatchPartyAccount.syncNow().onSuccess { count ->
                            WatchPartyAccount.setMessage(if (count == 0) "Alles aktuell" else "$count Bereiche gesichert")
                        }
                    }
                },
                modifier = firstModifier
            )
        }
        item(key = "wp_import") {
            SettingsActionButton(
                icon = Icons.Default.CloudDownload,
                title = "Addons und Plugins vom PC übernehmen",
                subtitle = "Fügt fehlende Addons und Plugin-Quellen vom PC hinzu, ändert am PC nichts",
                onClick = {
                    scope.launch {
                        WatchPartyAccount.setMessage("Wird übernommen …")
                        val result = runCatching { WatchPartyDesktopImport.run(context) }.getOrNull()
                        WatchPartyAccount.setMessage(
                            result?.let { "Vom PC übernommen: ${it.addons} Addons, ${it.repositories} Plugin-Quellen" }
                                ?: "Übernahme fehlgeschlagen"
                        )
                        WatchPartyAccount.requestSync()
                    }
                }
            )
        }
        item(key = "wp_logout") {
            SettingsActionButton(
                icon = Icons.AutoMirrored.Filled.Logout,
                title = "Abmelden",
                subtitle = "Daten auf diesem Fernseher bleiben erhalten",
                onClick = { scope.launch { WatchPartyAccount.logout() } }
            )
        }
        item(key = "wp_spacer") { Spacer(modifier = Modifier.height(NuvioTheme.spacing.md)) }
    }
}
