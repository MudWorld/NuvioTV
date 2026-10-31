package com.nuvio.tv.watchparty

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.nuvio.tv.BuildConfig
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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WatchParty account on the user's own server.
 *
 * One sign-in covers two things: the WatchParty account (reads the PC's `pc.*` sections, e.g. which
 * collections the PC shows in its sidebar) and the app's own Nuvio sync, which the same server
 * answers. Nuvio sync keeps profiles, continue watching, watched items, library, addons, plugins and
 * collections per profile on every WatchParty device.
 */
object WatchPartyAccount {
    private const val TAG = "WatchPartyAccount"
    private const val BaseUrl = "https://sync.mudworld.space"
    const val DesktopPrefix = "pc."

    /** Nuvio sync signs in with an e-mail; WatchParty accounts are user names. */
    private const val AccountEmailDomain = "watchparty.local"

    private const val KeyToken = "token"
    private const val KeyUsername = "username"
    private const val KeyNuvioReady = "nuvio_sync_signed_in"
    private const val KeySidebarCollections = "sidebar_collection_ids"
    private const val KeyModernSidebarSet = "modern_sidebar_set"
    private const val KeyLastSync = "last_sync"

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
    private var startJob: Job? = null
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

    /**
     * Signed in to WatchParty before Nuvio sync came along: the password is not stored, so the
     * sign-in screen asks once more to sign in to the sync as well.
     */
    val needsSyncSignIn: Boolean get() = isSignedIn && !prefs.getBoolean(KeyNuvioReady, false)

    /** Called first thing in Application.onCreate, before Hilt builds the sync client. */
    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("watchparty_account", Context.MODE_PRIVATE)
        useWatchPartyServer()
        _sidebarCollectionIds.value = prefs.getString(KeySidebarCollections, null)
            ?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        _state.value = State(username = prefs.getString(KeyUsername, null), lastSyncAt = prefs.getLong(KeyLastSync, 0L))
    }

    /**
     * Points the app's sync at the WatchParty server as a self-hosted Nuvio backend with e-mail
     * sign-in (the app's ServerConfigurationStore reads these preferences).
     */
    private fun useWatchPartyServer() {
        val config = appContext.getSharedPreferences("server_configuration", Context.MODE_PRIVATE)
        if (config.getBoolean("custom_enabled", false) &&
            config.getString("backend_url", null) == BuildConfig.SUPABASE_URL &&
            config.getString("publishable_key", null) == BuildConfig.SUPABASE_ANON_KEY
        ) return
        config.edit()
            .putBoolean("custom_enabled", true)
            .putString("backend_url", BuildConfig.SUPABASE_URL)
            .putString("publishable_key", BuildConfig.SUPABASE_ANON_KEY)
            .putBoolean("email_password_auth", true)
            .putBoolean("tv_login", false)
            .putBoolean("watch_party", false)
            .putString("discovery_url", "${BuildConfig.SUPABASE_URL}/.well-known/nuvio")
            .commit()
    }

    /** Applies the PC's sidebar collections and, once, the Apple TV style sidebar. */
    fun start() {
        if (startJob != null) return
        startJob = scope.launch {
            if (takeModernSidebarDefault()) {
                runCatching { WatchPartyDesktopImport.useModernSidebar(appContext) }
                    .onFailure { Log.w(TAG, "modern sidebar", it) }
            }
            delay(5_000)
            if (isSignedIn) {
                runCatching { WatchPartyDesktopImport.followPcCollectionLayout(appContext) }
                    .onFailure { Log.w(TAG, "collection layout from PC failed", it) }
            }
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
            val name = response.optString("username", username.trim())
            WatchPartyDesktopImport.signInSync(appContext, "${name.lowercase()}@$AccountEmailDomain", password)
                .getOrElse { throw WatchPartyException("sync_sign_in_failed") }
            prefs.edit()
                .putString(KeyToken, response.getString("token"))
                .putString(KeyUsername, name)
                .putBoolean(KeyNuvioReady, true)
                .commit()
            _state.update { it.copy(username = name) }
            Unit
        }.onFailure { error -> _state.update { it.copy(error = describe(error)) } }
            .also { _state.update { it.copy(busy = false) } }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        token?.let { runCatching { request("POST", "/v1/logout", "{}", it) } }
        runCatching { WatchPartyDesktopImport.signOutSync(appContext) }
        prefs.edit().remove(KeyToken).remove(KeyUsername).remove(KeyNuvioReady).commit()
        _state.value = State()
    }

    /** Asks the app's sync to pull and push everything now. */
    suspend fun syncNow(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            WatchPartyDesktopImport.requestSync(appContext)
            val now = System.currentTimeMillis()
            prefs.edit().putLong(KeyLastSync, now).apply()
            _state.update { it.copy(lastSyncAt = now, error = null) }
            Unit
        }
    }

    // ---- shared helpers (also used by the PC import) -----------------------

    fun readSection(section: String): String? {
        val authToken = token ?: return null
        return JSONObject(request("GET", "/v1/data/$section", null, authToken)).opt("data")?.toString()
    }

    fun setSidebarCollectionIds(ids: List<String>) {
        prefs.edit().putString(KeySidebarCollections, ids.joinToString(",")).apply()
        _sidebarCollectionIds.value = ids
    }

    /** True once: the Apple TV style sidebar is switched on a single time, later the user decides. */
    fun takeModernSidebarDefault(): Boolean {
        if (prefs.getBoolean(KeyModernSidebarSet, false)) return false
        prefs.edit().putBoolean(KeyModernSidebarSet, true).apply()
        return true
    }

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
        "sync_sign_in_failed" -> "Anmeldung für den Profil-Sync fehlgeschlagen, bitte erneut versuchen"
        null -> "Keine Verbindung zum Server"
        else -> "Fehler: ${(error as WatchPartyException).code}"
    }
}

class WatchPartyException(val code: String) : Exception(code)
