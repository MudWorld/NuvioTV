package com.nuvio.tv.watchparty.livetv

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.Xml
import com.nuvio.tv.watchparty.WatchPartyAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

// Live TV for WatchParty on Android TV, ported from the PC app (M3U playlists, XMLTV guide,
// favorites, recently watched). Channel ids use the PC's formula, so favorites and recents taken
// over from the PC keep working here.

@Serializable
data class LiveTvPlaylist(
    val url: String,
    val name: String,
    val epgUrl: String? = null,
    val epgUrlFromPlaylist: Boolean = true,
    val channelCount: Int = 0,
    val lastRefreshEpochMs: Long = 0L,
)

@Serializable
data class LiveTvChannel(
    val id: String,
    val name: String,
    val group: String,
    val logoUrl: String? = null,
    val streamUrl: String,
    val playlistUrl: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

data class LiveTvProgramme(
    val title: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val description: String? = null,
) {
    fun progress(now: Long): Float =
        ((now - startEpochMs).toFloat() / (endEpochMs - startEpochMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
}

private fun shortHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it) }

internal fun liveTvChannelId(playlistUrl: String, streamUrl: String): String = "iptv:" + shortHash("$playlistUrl|$streamUrl")

internal object M3uParser {
    data class Result(val channels: List<LiveTvChannel>, val epgUrl: String?)

    private val attributePattern = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(text: String, playlistUrl: String): Result {
        val channels = mutableListOf<LiveTvChannel>()
        val seen = mutableSetOf<String>()
        var epgUrl: String? = null
        var pendingName: String? = null
        var pendingAttributes: Map<String, String> = emptyMap()
        var pendingGroup: String? = null
        val pendingHeaders = mutableMapOf<String, String>()
        fun reset() {
            pendingName = null
            pendingAttributes = emptyMap()
            pendingGroup = null
            pendingHeaders.clear()
        }
        text.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attributes = attributes(line)
                    epgUrl = (attributes["url-tvg"] ?: attributes["x-tvg-url"])?.split(',')?.firstOrNull()?.trim()
                        ?.takeIf { it.startsWith("http", ignoreCase = true) }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    reset()
                    val comma = displayNameComma(line)
                    pendingAttributes = attributes(if (comma >= 0) line.substring(0, comma) else line)
                    pendingName = if (comma >= 0) line.substring(comma + 1).trim() else ""
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    pendingGroup = line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val value = option.substringAfter('=', "").trim()
                    if (value.isNotEmpty()) when (option.substringBefore('=').trim().lowercase()) {
                        "http-user-agent" -> pendingHeaders["User-Agent"] = value
                        "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                        "http-origin" -> pendingHeaders["Origin"] = value
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val name = pendingName
                    if (name != null && isPlayableUrl(line)) {
                        val id = liveTvChannelId(playlistUrl, line)
                        if (seen.add(id)) {
                            val a = pendingAttributes
                            channels += LiveTvChannel(
                                id = id,
                                name = name.ifBlank { a["tvg-name"].orEmpty().ifBlank { line } },
                                group = (a["group-title"] ?: pendingGroup)?.trim()?.takeIf { it.isNotEmpty() } ?: "Weitere",
                                logoUrl = a["tvg-logo"]?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) },
                                streamUrl = line,
                                playlistUrl = playlistUrl,
                                tvgId = a["tvg-id"]?.trim()?.takeIf { it.isNotEmpty() },
                                tvgName = a["tvg-name"]?.trim()?.takeIf { it.isNotEmpty() },
                                headers = pendingHeaders.toMap(),
                            )
                        }
                    }
                    reset()
                }
            }
        }
        return Result(channels, epgUrl)
    }

    private fun attributes(line: String): Map<String, String> =
        attributePattern.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    private fun displayNameComma(line: String): Int {
        var quoted = false
        var last = -1
        line.forEachIndexed { i, c ->
            if (c == '"') quoted = !quoted else if (c == ',' && !quoted) last = i
        }
        return last
    }

    private fun isPlayableUrl(url: String): Boolean {
        val lower = url.lowercase()
        val scheme = lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("rtmp") || lower.startsWith("rtsp")
        return scheme && "youtube.com/" !in lower && "youtu.be/" !in lower
    }
}

internal object XmltvParser {
    fun parse(input: InputStream, wanted: Set<String>, fromMs: Long, toMs: Long): Map<String, List<LiveTvProgramme>> {
        val wantedLower = wanted.mapTo(mutableSetOf()) { it.lowercase() }
        val result = mutableMapOf<String, MutableList<LiveTvProgramme>>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(maybeGunzip(input), null)
        var channel: String? = null
        var start = 0L
        var end = 0L
        var title: String? = null
        var description: String? = null
        var inProgramme = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        channel = parser.getAttributeValue(null, "channel")?.lowercase()
                        start = parseTime(parser.getAttributeValue(null, "start"))
                        end = parseTime(parser.getAttributeValue(null, "stop"))
                        inProgramme = channel != null && channel in wantedLower && end > fromMs && start < toMs
                        title = null
                        description = null
                    }
                    "title" -> if (inProgramme && title == null) title = parser.nextText().trim()
                    "desc" -> if (inProgramme && description == null) description = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> if (parser.name == "programme") {
                    val id = channel
                    val name = title
                    if (inProgramme && id != null && !name.isNullOrBlank() && end > start) {
                        result.getOrPut(id) { mutableListOf() } += LiveTvProgramme(name, start, end, description?.takeIf { it.isNotBlank() })
                    }
                    inProgramme = false
                }
            }
            event = parser.next()
        }
        return result.mapValues { (_, list) -> list.sortedBy { it.startEpochMs } }
    }

    private fun maybeGunzip(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val a = buffered.read()
        val b = buffered.read()
        buffered.reset()
        return if (a == 0x1f && b == 0x8b) GZIPInputStream(buffered) else buffered
    }

    fun parseTime(value: String?): Long {
        val text = value?.trim().orEmpty()
        if (text.length < 14) return 0L
        return runCatching {
            if (text.length > 14) {
                SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(text.replace(Regex("\\s+"), " "))!!.time
            } else {
                SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(text)!!.time
            }
        }.getOrDefault(0L)
    }
}

object LiveTvRepository {
    private const val TAG = "LiveTv"
    private const val RecentLimit = 15
    private const val EpgMaxAgeMs = 12L * 3_600_000
    private const val MaxPlaylistBytes = 40L * 1024 * 1024
    private const val MaxEpgBytes = 120L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }
    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File

    private val _playlists = MutableStateFlow<List<LiveTvPlaylist>>(emptyList())
    val playlists: StateFlow<List<LiveTvPlaylist>> = _playlists.asStateFlow()
    private val _channels = MutableStateFlow<List<LiveTvChannel>>(emptyList())
    val channels: StateFlow<List<LiveTvChannel>> = _channels.asStateFlow()
    private val _favorites = MutableStateFlow<List<String>>(emptyList())
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()
    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()
    private val _guide = MutableStateFlow<Map<String, List<LiveTvProgramme>>>(emptyMap())
    val guide: StateFlow<Map<String, List<LiveTvProgramme>>> = _guide.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        prefs = context.applicationContext.getSharedPreferences("watchparty_livetv", Context.MODE_PRIVATE)
        dir = File(context.applicationContext.filesDir, "livetv").apply { mkdirs() }
        _playlists.value = decode(prefs.getString("playlists", null), ListSerializer(LiveTvPlaylist.serializer()))
        _favorites.value = decode(prefs.getString("favorites", null), ListSerializer(String.serializer()))
        _recent.value = decode(prefs.getString("recent", null), ListSerializer(String.serializer()))
        _channels.value = _playlists.value.flatMap { readChannels(it.url) }
        scope.launch {
            if (_playlists.value.isEmpty()) importFromPc()
            loadGuide(force = false)
        }
    }

    /** Takes over the PC's Live TV (playlists, favorites, recently watched) from the account. */
    suspend fun importFromPc(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            _busy.value = "Playlisten vom PC werden übernommen …"
            val text = WatchPartyAccount.readSection("pc.nuvio_livetv") ?: return@runCatching 0
            val props = Properties().apply { load(StringReader(unwrap(text))) }
            val pcPlaylists = decode(props.getProperty("playlists"), ListSerializer(LiveTvPlaylist.serializer()))
            var added = 0
            pcPlaylists.forEach { playlist ->
                if (_playlists.value.none { it.url == playlist.url }) {
                    _playlists.update { it + playlist.copy(channelCount = 0, lastRefreshEpochMs = 0L) }
                    added++
                }
            }
            val pcFavorites = decode(props.getProperty("favorites"), ListSerializer(String.serializer()))
            if (pcFavorites.isNotEmpty()) _favorites.update { (it + pcFavorites).distinct() }
            val pcRecent = decode(props.getProperty("recent"), ListSerializer(String.serializer()))
            if (_recent.value.isEmpty() && pcRecent.isNotEmpty()) _recent.value = pcRecent.take(RecentLimit)
            persist()
            _playlists.value.filter { it.channelCount == 0 }.forEach { refreshInternal(it.url) }
            added
        }.also { _busy.value = null }
    }

    /** Section content is either the raw .properties text or a JSON string holding it. */
    private fun unwrap(text: String): String =
        if (text.startsWith("\"")) runCatching { json.decodeFromString(String.serializer(), text) }.getOrDefault(text) else text

    suspend fun addPlaylist(url: String, name: String): Result<Int> = withContext(Dispatchers.IO) {
        val clean = url.trim()
        if (!clean.startsWith("http", ignoreCase = true)) return@withContext Result.failure(IllegalArgumentException("Bitte eine http(s)-Adresse eingeben"))
        if (_playlists.value.none { it.url == clean }) {
            _playlists.update { it + LiveTvPlaylist(url = clean, name = name.trim().ifBlank { clean.substringAfterLast('/').ifBlank { "Playlist" } }) }
            persist()
        }
        refreshInternal(clean).also { loadGuide(force = true) }
    }

    fun removePlaylist(url: String) {
        _playlists.update { list -> list.filterNot { it.url == url } }
        _channels.update { list -> list.filterNot { it.playlistUrl == url } }
        File(dir, "channels-${shortHash(url)}.json").delete()
        persist()
    }

    fun refreshAll() {
        scope.launch {
            _playlists.value.forEach { refreshInternal(it.url) }
            loadGuide(force = true)
        }
    }

    private suspend fun refreshInternal(url: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            _busy.value = "Playlist wird geladen …"
            val text = download(url, MaxPlaylistBytes).use { it.readBytes().toString(Charsets.UTF_8) }
            val parsed = M3uParser.parse(text, url)
            File(dir, "channels-${shortHash(url)}.json")
                .writeText(json.encodeToString(ListSerializer(LiveTvChannel.serializer()), parsed.channels))
            _playlists.update { list ->
                list.map {
                    if (it.url != url) it else it.copy(
                        channelCount = parsed.channels.size,
                        lastRefreshEpochMs = System.currentTimeMillis(),
                        epgUrl = if (it.epgUrlFromPlaylist) parsed.epgUrl ?: it.epgUrl else it.epgUrl,
                    )
                }
            }
            _channels.update { list -> list.filterNot { it.playlistUrl == url } + parsed.channels }
            persist()
            parsed.channels.size
        }.onFailure { Log.w(TAG, "playlist $url failed: ${it.message}") }
            .also { _busy.value = null }
    }

    fun toggleFavorite(id: String): Boolean {
        val now = id !in _favorites.value
        _favorites.update { if (now) it + id else it - id }
        persist()
        return now
    }

    fun markWatched(id: String) {
        _recent.update { (listOf(id) + it.filterNot { r -> r == id }).take(RecentLimit) }
        persist()
    }

    fun nowPlaying(channel: LiveTvChannel, now: Long = System.currentTimeMillis()): LiveTvProgramme? {
        val key = (channel.tvgId ?: channel.tvgName ?: return null).lowercase()
        return _guide.value[key]?.firstOrNull { now in it.startEpochMs until it.endEpochMs }
    }

    fun upNext(channel: LiveTvChannel, now: Long = System.currentTimeMillis()): LiveTvProgramme? {
        val key = (channel.tvgId ?: channel.tvgName ?: return null).lowercase()
        return _guide.value[key]?.firstOrNull { it.startEpochMs >= now }
    }

    private suspend fun loadGuide(force: Boolean) = withContext(Dispatchers.IO) {
        val wanted = _channels.value.mapNotNull { it.tvgId ?: it.tvgName }.toSet()
        if (wanted.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val merged = mutableMapOf<String, List<LiveTvProgramme>>()
        _playlists.value.mapNotNull { it.epgUrl }.distinct().forEach { epgUrl ->
            runCatching {
                val cache = File(dir, "epg-${shortHash(epgUrl)}.xml")
                if (force || !cache.exists() || now - cache.lastModified() > EpgMaxAgeMs) {
                    _busy.value = "Programmführer wird geladen …"
                    download(epgUrl, MaxEpgBytes).use { input -> cache.outputStream().use { input.copyTo(it) } }
                }
                cache.inputStream().use { merged += XmltvParser.parse(it, wanted, now - 3 * 3_600_000L, now + 24 * 3_600_000L) }
            }.onFailure { Log.w(TAG, "guide $epgUrl failed: ${it.message}") }
        }
        _busy.value = null
        if (merged.isNotEmpty()) _guide.value = merged
    }

    private fun download(url: String, maxBytes: Long): InputStream {
        val response = http.newCall(Request.Builder().url(url).header("User-Agent", "WatchParty-AndroidTV").build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IllegalStateException("HTTP ${response.code}")
        }
        val body = response.body ?: throw IllegalStateException("leer")
        if (body.contentLength() > maxBytes) {
            response.close()
            throw IllegalStateException("zu groß")
        }
        return body.byteStream()
    }

    private fun readChannels(url: String): List<LiveTvChannel> = runCatching {
        json.decodeFromString(ListSerializer(LiveTvChannel.serializer()), File(dir, "channels-${shortHash(url)}.json").readText())
    }.getOrDefault(emptyList())

    private fun persist() {
        prefs.edit()
            .putString("playlists", json.encodeToString(ListSerializer(LiveTvPlaylist.serializer()), _playlists.value))
            .putString("favorites", json.encodeToString(ListSerializer(String.serializer()), _favorites.value))
            .putString("recent", json.encodeToString(ListSerializer(String.serializer()), _recent.value))
            .apply()
    }

    private fun <T> decode(text: String?, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> =
        text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
}
