@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.SystemUpdate
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
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
import com.nuvio.tv.watchparty.WatchPartyException
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
    var useCode by remember { mutableStateOf(false) }
    var modeChosen by remember { mutableStateOf(false) }
    var codeLogin by remember { mutableStateOf<WatchPartyAccount.CodeLogin?>(null) }
    val firstField = remember { FocusRequester() }
    val modeButtonFocus = remember { FocusRequester() }

    fun finish() {
        scope.launch {
            WatchPartyAccount.setMessage("Angemeldet, Profile und Daten werden geladen …")
            runCatching { WatchPartyDesktopImport.followPcCollectionLayout(context) }
            delay(1_500)
            WatchPartyAccount.setMessage(null)
            working = false
            onDone()
        }
    }

    fun submit() {
        if (working || username.isBlank() || password.isEmpty()) return
        working = true
        scope.launch {
            WatchPartyAccount.setMessage(null)
            if (WatchPartyAccount.login(username, password).isFailure) {
                working = false
                return@launch
            }
            finish()
        }
    }

    // A code is fetched right away: it tells whether an iPad/iPhone of this household is signed in,
    // and then the code is the suggested way in.
    LaunchedEffect(Unit) {
        val first = WatchPartyAccount.startCodeLogin().getOrNull()
        codeLogin = first
        if (!modeChosen) {
            modeChosen = true
            useCode = first?.suggestCode == true
        }
        runCatching { if (useCode) modeButtonFocus.requestFocus() else firstField.requestFocus() }
    }

    LaunchedEffect(useCode) {
        if (!useCode) return@LaunchedEffect
        while (useCode && !working) {
            val current = codeLogin
            if (current == null || System.currentTimeMillis() >= current.expiresAtMs) {
                codeLogin = WatchPartyAccount.startCodeLogin().getOrNull()
                if (codeLogin == null) delay(5_000)
                continue
            }
            delay(current.intervalMs)
            if (!useCode) break
            WatchPartyAccount.pollCodeLogin(current)
                .onSuccess { signedIn ->
                    if (signedIn) {
                        working = true
                        finish()
                    }
                }
                .onFailure { error ->
                    val code = (error as? WatchPartyException)?.code
                    if (code == "code_expired" || code == "sync_sign_in_failed") codeLogin = null
                    if (code == "sync_sign_in_failed") {
                        WatchPartyAccount.setMessage("Anmeldung für den Profil-Sync fehlgeschlagen, neuer Code folgt")
                    }
                }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.62f)
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
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                Box(Modifier.weight(1f).focusRequester(modeButtonFocus)) {
                    WatchPartyButton(text = "Mit Code", onClick = { modeChosen = true; useCode = true }, secondary = !useCode)
                }
                Box(Modifier.weight(1f)) {
                    WatchPartyButton(text = "Mit Passwort", onClick = { modeChosen = true; useCode = false }, secondary = useCode)
                }
            }
            if (useCode) {
                val current = codeLogin
                if (current == null) {
                    Text(text = "Code wird geladen …", color = NuvioTheme.colors.TextSecondary)
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
                    ) {
                        val qr = remember(current.url) {
                            runCatching { com.nuvio.tv.core.qr.QrCodeGenerator.generate(current.url, 360).asImageBitmap() }.getOrNull()
                        }
                        if (qr != null && current.url.isNotBlank()) {
                            Image(
                                bitmap = qr,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(170.dp)
                                    .background(Color.White, RoundedCornerShape(12.dp))
                                    .padding(8.dp)
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                            Text(
                                text = current.display,
                                style = MaterialTheme.typography.displaySmall,
                                color = NuvioTheme.colors.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Auf iPad, iPhone oder PC: WatchParty → Einstellungen → Konto → „Fernseher anmelden“ und diesen Code eingeben.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary
                            )
                            Text(
                                text = "Oder den QR-Code mit dem Handy scannen und dort mit Benutzername und Passwort bestätigen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                    }
                }
            } else {
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
            }
            val status = state.error ?: state.message
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.error != null) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
            if (!useCode) {
                WatchPartyButton(text = if (working) "Bitte warten …" else "Anmelden", onClick = { submit() })
            }
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
    val diagnosticsEnabled by com.nuvio.tv.watchparty.WatchPartyExtras.diagnosticsEnabled.collectAsState()
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var reportStatus by remember { mutableStateOf<String?>(null) }
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
                    subtitle = "Mit Code vom iPad, iPhone oder PC, oder mit Benutzername und Passwort",
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
        item(key = "wp_update") {
            SettingsActionButton(
                icon = Icons.Default.SystemUpdate,
                title = "Nach Updates suchen",
                subtitle = updateStatus ?: "WatchParty Build ${com.nuvio.tv.BuildConfig.WATCHPARTY_BUILD} · neue Versionen kommen von deinem Server",
                onClick = {
                    updateStatus = "Wird geprüft …"
                    scope.launch {
                        com.nuvio.tv.watchparty.WatchPartyExtras.checkForUpdate(manual = true)
                            .onSuccess { info ->
                                updateStatus = if (info == null) "Du hast die neueste Version." else "Build ${info.build} ist da."
                            }
                            .onFailure { updateStatus = "Prüfen fehlgeschlagen" }
                    }
                }
            )
        }
        item(key = "wp_diagnostics") {
            SettingsActionButton(
                icon = Icons.Default.BugReport,
                title = if (diagnosticsEnabled) "Fehlerberichte: an" else "Fehlerberichte: aus",
                subtitle = "Abstürze und letzte Fehler an deinen Server senden, ohne Stream-Links, Schlüssel und Passwörter",
                onClick = { com.nuvio.tv.watchparty.WatchPartyExtras.setDiagnosticsEnabled(!diagnosticsEnabled) }
            )
        }
        if (diagnosticsEnabled) {
            item(key = "wp_diagnostics_send") {
                SettingsActionButton(
                    icon = Icons.Default.Send,
                    title = "Fehlerbericht jetzt senden",
                    subtitle = reportStatus ?: "Schickt das Protokoll dieser App an deinen Server",
                    onClick = {
                        reportStatus = "Wird gesendet …"
                        scope.launch {
                            reportStatus = if (com.nuvio.tv.watchparty.WatchPartyExtras.sendReportNow().isSuccess) {
                                "Bericht gesendet"
                            } else {
                                "Senden fehlgeschlagen"
                            }
                        }
                    }
                )
            }
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
