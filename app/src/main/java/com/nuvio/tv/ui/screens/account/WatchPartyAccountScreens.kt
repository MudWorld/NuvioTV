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

/**
 * WatchParty sign-in: user name and password of the user's own account. The same sign-in starts
 * the app's profile sync, so profiles, continue watching and library come from the account.
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
            WatchPartyAccount.setMessage("Angemeldet, Profile und Daten werden geladen …")
            runCatching { WatchPartyDesktopImport.followPcCollectionLayout(context) }
            delay(1_500)
            WatchPartyAccount.setMessage(null)
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
                text = if (WatchPartyAccount.needsSyncSignIn) {
                    "Neu: Profile wie bei Netflix auf allen Geräten. Bitte melde dich dafür einmal neu an."
                } else {
                    "Mit deinem WatchParty-Konto kommen Profile, Weiterschauen, Addons und Plugins auf diesen Fernseher."
                },
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
                    text = "Melde dich mit deinem WatchParty-Konto an. Dann sind Profile, Weiterschauen, " +
                        "Bibliothek, Addons und Plugins auf allen deinen Geräten gleich.",
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
            Text(
                text = "Angemeldet als ${state.username} · Profile und Verlauf werden automatisch abgeglichen",
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
                title = "Jetzt synchronisieren",
                subtitle = "Sonst automatisch, z. B. beim Pausieren und Schließen eines Videos",
                onClick = {
                    scope.launch {
                        WatchPartyAccount.syncNow()
                            .onSuccess { WatchPartyAccount.setMessage("Synchronisierung gestartet") }
                            .onFailure { WatchPartyAccount.setMessage("Synchronisierung fehlgeschlagen") }
                    }
                },
                modifier = firstModifier
            )
        }
        item(key = "wp_logout") {
            SettingsActionButton(
                icon = Icons.AutoMirrored.Filled.Logout,
                title = "Abmelden",
                subtitle = "Meldet auch den Profil-Sync auf diesem Fernseher ab",
                onClick = { scope.launch { WatchPartyAccount.logout() } }
            )
        }
        item(key = "wp_spacer") { Spacer(modifier = Modifier.height(NuvioTheme.spacing.md)) }
    }
}
