package com.nuvio.tv.watchparty

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * WatchParty account on the user's own server.
 *
 * The Android TV keeps its own sections `atv.<datastore file>` (base64 of the DataStore
 * `.preferences_pb` file). It never writes `pc.*`, `tv.*` or `ipad.*`; the PC's sections are only
 * read once by [WatchPartyDesktopImport].
 *
 * Downloaded files are staged and moved into place by [applyStagedFiles] at the next process
 * start, before any DataStore is opened.
 */
object WatchPartyAccount {
    private const val TAG = "WatchPartyAccount"
    private const val BaseUrl = "https://sync.mudworld.space"
    const val Prefix = "atv."
    const val DesktopPrefix = "pc."
    private const val PushIntervalMs = 10 * 60 * 1000L
    private const val RequestDebounceMs = 5_000L
    private const val MaxFileBytes = 1_400_000L
    private const val StagedDir = "watchparty_staged"
    private val ProfileSuffix = Regex("_p\\d+$")

    private const val KeyToken = "token"
    private const val KeyUsername = "username"
    private const val KeyPushedHashes = "pushed_hashes"
    private const val KeyInitialPullDone = "initial_pull_done"
    const val KeyPcImportDone = "pc_import_done"
    private const val KeyPcCollectionsDone = "pc_collections_done"
    private const val KeyPcCollectionsPinned = "pc_collections_pinned"
    private const val KeySidebarCollections = "sidebar_collection_ids"
    private const val KeyLastSync = "last_sync"

    /** DataStores that only make sense on this device (caches, sessions, device flags). */
    private val deviceOnlyStores = setOf(
        "app_onboarding",
        "auth_session_notice_store",
        "debug_settings",
        "device_local_player_prefs",
        "profile_lock_state",
        "sentry_settings",
        "tv_channel_prefs",
        "startup_sync_state",
        "watch_state_pending_mutations",
        "trakt_auth_store",
        "audio_delay_route_preference",
        "mdblist_playback",
        "simkl_playback",
        "simkl_sync",
        "mdblist_sync",
    )

    data class State(
        val username: String? = null,
        val busy: Boolean = false,
        val lastSyncAt: Long = 0L,
        val error: String? = null,
        val message: String? = null,
    )

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private var loopJob: Job? = null
    private var pendingRequest: Job? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _sidebarCollectionIds = MutableStateFlow<List<String>>(emptyList())

    /** Collections shown in the left sidebar, taken from the PC's sidebar choice. */
    val sidebarCollectionIds: StateFlow<List<String>> = _sidebarCollectionIds.asStateFlow()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    val isSignedIn: Boolean get() = token != null
    private val token: String? get() = prefs.getString(KeyToken, null)

    /** Called first thing in Application.onCreate, before Hilt opens any DataStore. */
    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("watchparty_account", Context.MODE_PRIVATE)
        applyStagedFiles()
        _sidebarCollectionIds.value = prefs.getString(KeySidebarCollections, null)
            ?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        _state.value = State(username = prefs.getString(KeyUsername, null), lastSyncAt = prefs.getLong(KeyLastSync, 0L))
    }

    /** Starts the 10-minute loop; the first round runs shortly after launch. */
    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch {
            delay(5_000)
            if (needsPcCollections) {
                runCatching { WatchPartyDesktopImport.runCollections(appContext) }
                    .onFailure { Log.w(TAG, "collections import failed", it) }
            }
            if (isSignedIn) {
                runCatching { WatchPartyDesktopImport.followPcCollectionLayout(appContext) }
                    .onFailure { Log.w(TAG, "collection layout from PC failed", it) }
            }
            delay(15_000)
            while (true) {
                if (isSignedIn) runCatching { syncNow() }
                delay(PushIntervalMs)
            }
        }
    }

    /** Push soon (pause, player closed, episode switch). Calls within a few seconds are merged. */
    fun requestSync() {
        if (!::prefs.isInitialized || !isSignedIn) return
        pendingRequest?.cancel()
        pendingRequest = scope.launch {
            delay(RequestDebounceMs)
            runCatching { syncNow() }
        }
    }

    // ---- account ----------------------------------------------------------

    suspend fun login(username: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            _state.update { it.copy(busy = true, error = null, message = null) }
            val body = JSONObject()
                .put("username", username.trim())
                .put("password", password)
                .put("device", "Android TV ${Build.MODEL}".take(64))
            val response = JSONObject(request("POST", "/v1/login", body.toString(), null))
            prefs.edit()
                .putString(KeyToken, response.getString("token"))
                .putString(KeyUsername, response.optString("username", username.trim()))
                .putBoolean(KeyInitialPullDone, false)
                .putBoolean(KeyPcImportDone, false)
                .putBoolean(KeyPcCollectionsDone, false)
                .remove(KeyPushedHashes)
                .commit()
            _state.update { it.copy(username = prefs.getString(KeyUsername, null)) }
            Unit
        }.onFailure { error -> _state.update { it.copy(error = describe(error)) } }
            .also { _state.update { it.copy(busy = false) } }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        token?.let { runCatching { request("POST", "/v1/logout", "{}", it) } }
        prefs.edit().clear().commit()
        _state.value = State()
    }

    // ---- sync -------------------------------------------------------------

    /**
     * After login: if this account already holds Android TV data, stage it and return true (the
     * caller restarts the app). Otherwise return false; the caller then copies the PC's data.
     */
    suspend fun pullAfterLogin(): Boolean = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val authToken = token ?: return@withLock false
            val remote = listSections(authToken).filterKeys { it.startsWith(Prefix) }
            if (remote.isEmpty()) {
                prefs.edit().putBoolean(KeyInitialPullDone, true).commit()
                return@withLock false
            }
            val staged = File(appContext.filesDir, StagedDir).apply { mkdirs() }
            val hashes = JSONObject()
            for (section in remote.keys) {
                val content = JSONObject(request("GET", "/v1/data/$section", null, authToken))
                    .optJSONObject("data")?.optString("b64").orEmpty()
                if (content.isEmpty()) continue
                val bytes = Base64.decode(content, Base64.NO_WRAP)
                val name = section.removePrefix(Prefix)
                File(staged, "$name.preferences_pb").writeBytes(bytes)
                hashes.put(name, sha256(bytes))
            }
            prefs.edit()
                .putBoolean(KeyInitialPullDone, true)
                .putBoolean(KeyPcImportDone, true)
                .putBoolean(KeyPcCollectionsDone, true)
                .putString(KeyPushedHashes, hashes.toString())
                .commit()
            true
        }
    }

    /** Uploads every DataStore file that changed since the last upload. */
    suspend fun syncNow(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            syncMutex.withLock {
                val authToken = token ?: return@withLock 0
                if (!prefs.getBoolean(KeyInitialPullDone, false)) return@withLock 0
                _state.update { it.copy(busy = true, error = null) }
                val pushed = JSONObject(prefs.getString(KeyPushedHashes, null) ?: "{}")
                var count = 0
                for (file in syncableFiles()) {
                    val name = file.name.removeSuffix(".preferences_pb")
                    val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
                    val hash = sha256(bytes)
                    if (pushed.optString(name) == hash) continue
                    val body = JSONObject().put(
                        "data",
                        JSONObject().put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    )
                    request("PUT", "/v1/data/$Prefix$name", body.toString(), authToken)
                    pushed.put(name, hash)
                    count++
                }
                val now = System.currentTimeMillis()
                prefs.edit().putString(KeyPushedHashes, pushed.toString()).putLong(KeyLastSync, now).apply()
                _state.update { it.copy(lastSyncAt = now) }
                count
            }
        }.onFailure { error ->
            Log.w(TAG, "sync failed", error)
            _state.update { it.copy(error = describe(error)) }
        }.also { _state.update { it.copy(busy = false) } }
    }

    private fun syncableFiles(): List<File> {
        val dir = File(appContext.filesDir, "datastore")
        return dir.listFiles().orEmpty().filter { file ->
            val name = file.name.removeSuffix(".preferences_pb")
            file.isFile && file.name.endsWith(".preferences_pb") &&
                file.length() in 1..MaxFileBytes &&
                name.matches(Regex("[a-z0-9][a-z0-9_.-]{0,59}")) &&
                "cache" !in name &&
                name.replace(ProfileSuffix, "") !in deviceOnlyStores
        }
    }

    private fun applyStagedFiles() {
        val staged = File(appContext.filesDir, StagedDir)
        val files = staged.listFiles().orEmpty()
        if (files.isEmpty()) return
        val target = File(appContext.filesDir, "datastore").apply { mkdirs() }
        for (file in files) {
            val destination = File(target, file.name)
            if (!file.renameTo(destination)) {
                runCatching { file.copyTo(destination, overwrite = true); file.delete() }
            }
        }
        staged.delete()
        Log.i(TAG, "applied ${files.size} staged files")
    }

    fun restartSoon(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            delay(1_500)
            restartApp(appContext)
        }
    }

    /** Restarts the app so staged files are applied before anything reads them. */
    fun restartApp(context: Context) {
        // A TV-only app has no regular launcher entry, only the leanback one.
        val launch = (context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return
        val pending = PendingIntent.getActivity(
            context, 4711, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        )
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.set(AlarmManager.RTC, System.currentTimeMillis() + 800, pending)
        Process.killProcess(Process.myPid())
    }

    // ---- shared helpers (also used by the PC import) -----------------------

    fun listSections(authToken: String): Map<String, Long> {
        val sections = JSONObject(request("GET", "/v1/data", null, authToken)).getJSONObject("sections")
        return sections.keys().asSequence().associateWith { sections.optLong(it) }
    }

    fun readSection(section: String): String? {
        val authToken = token ?: return null
        return JSONObject(request("GET", "/v1/data/$section", null, authToken)).opt("data")?.toString()
    }

    fun currentToken(): String? = token

    fun markPcImportDone() {
        prefs.edit().putBoolean(KeyPcImportDone, true).commit()
    }

    val pcImportDone: Boolean get() = prefs.getBoolean(KeyPcImportDone, false)

    fun setSidebarCollectionIds(ids: List<String>) {
        prefs.edit().putString(KeySidebarCollections, ids.joinToString(",")).apply()
        _sidebarCollectionIds.value = ids
    }

    val pcCollectionsPinned: Boolean get() = prefs.getBoolean(KeyPcCollectionsPinned, false)

    fun markPcCollectionsPinned() {
        prefs.edit().putBoolean(KeyPcCollectionsPinned, true).apply()
    }

    fun markPcCollectionsDone() {
        prefs.edit().putBoolean(KeyPcCollectionsDone, true).commit()
    }

    /** Signed in, first import done, but collections not copied yet (TVs set up before they were). */
    val needsPcCollections: Boolean
        get() = isSignedIn && prefs.getBoolean(KeyPcImportDone, false) && !prefs.getBoolean(KeyPcCollectionsDone, false)

    fun setMessage(message: String?) = _state.update { it.copy(message = message) }

    private fun request(method: String, path: String, body: String?, authToken: String?): String {
        val builder = Request.Builder().url(BaseUrl + path)
        authToken?.let { builder.header("Authorization", "Bearer $it") }
        val json = "application/json".toMediaType()
        when (method) {
            "GET" -> builder.get()
            else -> builder.method(method, (body ?: "{}").toRequestBody(json))
        }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 && authToken != null && path != "/v1/logout") {
                prefs.edit().remove(KeyToken).apply()
                _state.update { it.copy(username = null) }
            }
            if (!response.isSuccessful) {
                val code = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw WatchPartyException(code?.takeIf { it.isNotBlank() } ?: "http_${response.code}")
            }
            return text
        }
    }

    private fun describe(error: Throwable): String = when ((error as? WatchPartyException)?.code) {
        "invalid_credentials" -> "Benutzername oder Passwort falsch"
        "too_many_attempts" -> "Zu viele Versuche, bitte später erneut"
        "unauthorized" -> "Abgemeldet, bitte neu anmelden"
        null -> "Keine Verbindung zum Server"
        else -> "Fehler: ${(error as WatchPartyException).code}"
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

class WatchPartyException(val code: String) : Exception(code)
