@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.watchparty.livetv

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val Favorites = "★ Favoriten"
private const val Recent = "Zuletzt gesehen"
private const val All = "Alle Sender"

/**
 * Live TV on the TV: categories on the left, channels with what is on now and next on the right.
 * OK plays a channel, a long press adds or removes it from the favorites.
 */
@Composable
fun WatchPartyLiveTvScreen(
    onBackPress: () -> Unit,
    onPlay: (channel: LiveTvChannel, nowPlaying: String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { LiveTvRepository.ensureLoaded(context) }
    val playlists by LiveTvRepository.playlists.collectAsState()
    val channels by LiveTvRepository.channels.collectAsState()
    val favorites by LiveTvRepository.favorites.collectAsState()
    val recent by LiveTvRepository.recent.collectAsState()
    val guide by LiveTvRepository.guide.collectAsState()
    val busy by LiveTvRepository.busy.collectAsState()
    var category by remember { mutableStateOf(All) }
    var showAdd by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val groups = remember(channels) { channels.map { it.group }.distinct().sortedBy { it.lowercase() } }
    val categories = remember(groups, favorites, recent) {
        buildList {
            if (favorites.isNotEmpty()) add(Favorites)
            if (recent.isNotEmpty()) add(Recent)
            add(All)
            addAll(groups)
        }
    }
    val byId = remember(channels) { channels.associateBy { it.id } }
    val shown = remember(category, channels, favorites, recent) {
        when (category) {
            Favorites -> favorites.mapNotNull { byId[it] }
            Recent -> recent.mapNotNull { byId[it] }
            All -> channels
            else -> channels.filter { it.group == category }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(start = 48.dp, end = 32.dp, top = 28.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(
            modifier = Modifier.width(280.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Live TV", style = MaterialTheme.typography.headlineMedium, color = NuvioTheme.colors.TextPrimary, fontWeight = FontWeight.Bold)
            Text(
                text = busy ?: "${channels.size} Sender · ${playlists.size} Playlist(en)",
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(categories, key = { it }) { name ->
                    CategoryButton(name = name, selected = name == category) { category = name }
                }
                item(key = "__actions") { Spacer(Modifier.height(12.dp)) }
                item(key = "__add") { CategoryButton(name = "+ Playlist hinzufügen", selected = false) { showAdd = true } }
                item(key = "__pc") {
                    CategoryButton(name = "Vom PC übernehmen", selected = false) {
                        scope.launch {
                            val result = LiveTvRepository.importFromPc()
                            Toast.makeText(
                                context,
                                result.fold({ if (it > 0) "$it Playlist(en) vom PC übernommen" else "Keine neuen Playlisten am PC" }) { "Übernehmen fehlgeschlagen (angemeldet?)" },
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                item(key = "__refresh") { CategoryButton(name = "Aktualisieren", selected = false) { LiveTvRepository.refreshAll() } }
                if (playlists.isNotEmpty()) {
                    item(key = "__manage") { CategoryButton(name = "Playlisten verwalten", selected = false) { showPlaylists = true } }
                }
            }
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text(
                text = if (category == All) "Alle Sender" else category,
                style = MaterialTheme.typography.titleLarge,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            if (shown.isEmpty()) {
                Text(
                    text = if (channels.isEmpty()) {
                        "Noch keine Sender. Links „Vom PC übernehmen“ oder „Playlist hinzufügen“ wählen (M3U-Adresse)."
                    } else {
                        "Hier ist noch nichts. Lange auf einen Sender drücken, um ihn zu den Favoriten zu nehmen."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { channel ->
                    val current = remember(guide, now, channel.id) { LiveTvRepository.nowPlaying(channel, now) }
                    val next = remember(guide, now, channel.id) { LiveTvRepository.upNext(channel, now) }
                    ChannelRow(
                        channel = channel,
                        isFavorite = channel.id in favorites,
                        current = current,
                        next = next,
                        now = now,
                        onClick = {
                            LiveTvRepository.markWatched(channel.id)
                            onPlay(channel, current?.title)
                        },
                        onLongClick = {
                            val added = LiveTvRepository.toggleFavorite(channel.id)
                            Toast.makeText(context, if (added) "Zu Favoriten hinzugefügt" else "Aus Favoriten entfernt", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    if (showAdd) AddPlaylistDialog(onDismiss = { showAdd = false }) { url, name ->
        showAdd = false
        scope.launch {
            val result = LiveTvRepository.addPlaylist(url, name)
            Toast.makeText(
                context,
                result.fold({ "$it Sender geladen" }) { "Playlist konnte nicht geladen werden: ${it.message}" },
                Toast.LENGTH_LONG
            ).show()
        }
    }
    if (showPlaylists) ManagePlaylistsDialog(playlists = playlists, onDismiss = { showPlaylists = false })
}

@Composable
private fun CategoryButton(name: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.Secondary.copy(alpha = 0.35f) else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.Secondary
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun ChannelRow(
    channel: LiveTvChannel,
    isFavorite: Boolean,
    current: LiveTvProgramme?,
    next: LiveTvProgramme?,
    now: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard
        ),
        shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
        scale = CardDefaults.scale(focusedScale = 1.01f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.size(width = 96.dp, height = 56.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                if (channel.logoUrl != null) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp)
                    )
                } else {
                    Text(channel.name.take(3), color = NuvioTheme.colors.TextSecondary)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = (if (isFavorite) "★ " else "") + channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (current != null) {
                    Text(
                        text = "Jetzt: ${current.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box(Modifier.fillMaxWidth(0.6f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.15f))) {
                        Box(Modifier.fillMaxWidth(current.progress(now)).height(4.dp).background(NuvioTheme.colors.Secondary))
                    }
                } else {
                    Text(channel.group, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
                }
                if (next != null) {
                    Text(
                        text = "Danach: ${next.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun AddPlaylistDialog(onDismiss: () -> Unit, onAdd: (url: String, name: String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(640.dp)
                .background(NuvioTheme.colors.BackgroundElevated, RoundedCornerShape(20.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Playlist hinzufügen", style = MaterialTheme.typography.headlineSmall, color = NuvioTheme.colors.TextPrimary)
            Text(
                "Adresse einer M3U-Playlist, z. B. https://iptv-org.github.io/iptv/countries/de.m3u",
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
            InputField(value = url, onValueChange = { url = it }, placeholder = "https://…/playlist.m3u", imeAction = ImeAction.Next, modifier = Modifier.fillMaxWidth())
            InputField(value = name, onValueChange = { name = it }, placeholder = "Name (optional)", modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { if (url.isNotBlank()) onAdd(url, name) }) { Text("Hinzufügen") }
                Button(onClick = onDismiss) { Text("Abbrechen") }
            }
        }
    }
}

@Composable
private fun ManagePlaylistsDialog(playlists: List<LiveTvPlaylist>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(640.dp)
                .background(NuvioTheme.colors.BackgroundElevated, RoundedCornerShape(20.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Playlisten", style = MaterialTheme.typography.headlineSmall, color = NuvioTheme.colors.TextPrimary)
            Text("Lange drücken entfernt eine Playlist von diesem Fernseher.", style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            playlists.forEach { playlist ->
                Card(
                    onClick = {},
                    onLongClick = { LiveTvRepository.removePlaylist(playlist.url) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard, focusedContainerColor = NuvioTheme.colors.Secondary),
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(playlist.name, color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.titleSmall)
                        Text("${playlist.channelCount} Sender · ${playlist.url}", color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Button(onClick = onDismiss) { Text("Fertig") }
        }
    }
}
