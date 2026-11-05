package com.nuvio.tv.watchparty

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * WatchParty server extras on Android TV (nuvio-account/server.py, /v1/...): provider status from
 * the nightly check, "Link geht nicht" reports, opt-in error reports and app updates without adb.
 */
object WatchPartyExtras {
    private const val TAG = "WatchPartyExtras"
    private const val BaseUrl = "https://sync.mudworld.space"
    private const val UpdateChannel = "androidtv"
    private const val GermanProvidersMarker = "nuvio-german-providers"
    private const val KeyDown = "provider_down"
    private const val KeyDownChecked = "provider_down_checked"
    private const val KeyDiagnostics = "diagnostics_enabled"
    private const val KeyPendingCrash = "pending_crash"
    private const val KeyDismissedBuild = "update_dismissed_build"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val _providersDown = MutableStateFlow<Set<String>>(emptySet())
    val providersDown: StateFlow<Set<String>> = _providersDown.asStateFlow()
    private val _diagnosticsEnabled = MutableStateFlow(false)
    val diagnosticsEnabled: StateFlow<Boolean> = _diagnosticsEnabled.asStateFlow()

    data class UpdateInfo(
        val build: Int,
        val versionName: String,
        val file: String,
        val sha256: String,
        val size: Long,
        val notes: String,
    )

    /** A newer build on the server that the user has not put off. */
    private val _update = MutableStateFlow<UpdateInfo?>(null)
    val update: StateFlow<UpdateInfo?> = _update.asStateFlow()
    private val _updateProgress = MutableStateFlow<String?>(null)
    val updateProgress: StateFlow<String?> = _updateProgress.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("watchparty_extras", Context.MODE_PRIVATE)
        _providersDown.value = prefs.getString(KeyDown, "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        _diagnosticsEnabled.value = prefs.getBoolean(KeyDiagnostics, false)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                if (_diagnosticsEnabled.value) {
                    val text = "CRASH ${error::class.java.name}: ${error.message}\n" +
                        Log.getStackTraceString(error).take(24_000) + "\n--- logcat ---\n" + ownLog(250)
                    prefs.edit().putString(KeyPendingCrash, text.take(60_000)).commit()
                }
            }
            previous?.uncaughtException(thread, error)
        }
        scope.launch {
            delay(8_000)
            sendPendingCrash()
            refreshProviderStatus()
            checkForUpdate(manual = false)
        }
    }

    // ---- provider status ----------------------------------------------------

    suspend fun refreshProviderStatus(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force && System.currentTimeMillis() - prefs.getLong(KeyDownChecked, 0L) < 6 * 3_600_000L) return@withContext
        runCatching {
            val providers = JSONObject(request("GET", "/v1/plugin-status", null, auth = false)).optJSONObject("providers")
                ?: return@runCatching
            val down = providers.keys().asSequence()
                .filter { providers.optJSONObject(it)?.optString("status") == "down" }
                .toSet()
            _providersDown.value = down
            prefs.edit().putString(KeyDown, down.joinToString(",")).putLong(KeyDownChecked, System.currentTimeMillis()).apply()
        }.onFailure { Log.i(TAG, "provider status not loaded: ${it.message}") }
    }

    /** German Providers that failed two nightly checks in a row; the stream search skips them. */
    fun isProviderDown(scraperId: String, repositoryUrl: String): Boolean =
        repositoryUrl.contains(GermanProvidersMarker, ignoreCase = true) && scraperId in _providersDown.value

    // ---- "Link geht nicht" --------------------------------------------------

    suspend fun reportBrokenStream(
        provider: String,
        addonId: String?,
        streamName: String,
        title: String,
        contentId: String?,
        contentType: String?,
        season: Int?,
        episode: Int?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("platform", "androidtv")
                .put("appVersion", "Build ${BuildConfig.WATCHPARTY_BUILD}")
                .put("provider", provider)
                .put("addon", addonId)
                .put("streamName", streamName.take(300))
                .put("title", title)
                .put("contentId", contentId)
                .put("contentType", contentType)
                .put("season", season)
                .put("episode", episode)
                .put("error", ownLog(12).takeLast(1800))
            request("POST", "/v1/reports", body.toString())
            Unit
        }
    }

    /** What the stream screen currently shows; long-pressing a stream reports it with this. */
    data class ReportTarget(
        val title: String,
        val contentId: String?,
        val contentType: String?,
        val season: Int?,
        val episode: Int?,
    )

    @Volatile
    var reportTarget: ReportTarget? = null

    /** Long press on a stream card: report it and say so with a toast. */
    fun reportFromUi(stream: com.nuvio.tv.domain.model.Stream) {
        val target = reportTarget
        scope.launch {
            val result = reportBrokenStream(
                provider = stream.addonName,
                addonId = null,
                streamName = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" · "),
                title = target?.title.orEmpty(),
                contentId = target?.contentId,
                contentType = target?.contentType,
                season = target?.season,
                episode = target?.episode,
            )
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    appContext,
                    if (result.isSuccess) "Danke, der Link ist gemeldet. Er wird bei der nächsten Wartung geprüft."
                    else "Die Meldung konnte nicht gesendet werden. Angemeldet und online?",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---- error reports ------------------------------------------------------

    fun setDiagnosticsEnabled(enabled: Boolean) {
        _diagnosticsEnabled.value = enabled
        val editor = prefs.edit().putBoolean(KeyDiagnostics, enabled)
        if (!enabled) editor.remove(KeyPendingCrash)
        editor.apply()
    }

    suspend fun sendReportNow(): Result<Unit> = withContext(Dispatchers.IO) {
        upload("log", "Report from Android TV ${Build.MODEL}, WatchParty Build ${BuildConfig.WATCHPARTY_BUILD}\n" + ownLog(600))
    }

    private suspend fun sendPendingCrash() {
        if (!_diagnosticsEnabled.value) return
        val text = prefs.getString(KeyPendingCrash, null) ?: return
        upload("crash", text).onSuccess { prefs.edit().remove(KeyPendingCrash).apply() }
    }

    private fun upload(kind: String, text: String): Result<Unit> = runCatching {
        val body = JSONObject()
            .put("platform", "androidtv")
            .put("appVersion", "Build ${BuildConfig.WATCHPARTY_BUILD}")
            .put("device", "Android TV ${Build.MODEL}")
            .put("kind", kind)
            .put("text", text.take(250_000))
        request("POST", "/v1/diagnostics", body.toString())
        Unit
    }

    /** This app's own log lines (an app may read its own logcat). The server strips links and keys. */
    private fun ownLog(lines: Int): String = runCatching {
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-t", lines.toString(), "--pid=${android.os.Process.myPid()}")
        )
        process.inputStream.bufferedReader().use { it.readText() }.also { process.destroy() }
    }.getOrDefault("")

    // ---- updates --------------------------------------------------------------

    /** Asks the server for a newer build. A manual check also shows builds the user put off. */
    suspend fun checkForUpdate(manual: Boolean): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(request("GET", "/v1/updates/$UpdateChannel/latest.json", null))
            val info = UpdateInfo(
                build = o.getInt("build"),
                versionName = o.optString("versionName", "Build ${o.getInt("build")}"),
                file = o.getString("file"),
                sha256 = o.optString("sha256").lowercase(),
                size = o.optLong("size"),
                notes = o.optString("notes"),
            )
            val newer = info.build > BuildConfig.WATCHPARTY_BUILD
            val putOff = prefs.getInt(KeyDismissedBuild, 0) >= info.build
            _update.value = if (newer && (manual || !putOff)) info else null
            if (newer) info else null
        }
    }

    fun postponeUpdate(info: UpdateInfo) {
        prefs.edit().putInt(KeyDismissedBuild, info.build).apply()
        _update.value = null
    }

    /** Downloads the APK (checked against its SHA-256) and hands it to the system installer. */
    suspend fun downloadAndInstall(info: UpdateInfo): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()) {
                _updateProgress.value = "Bitte WatchParty erlauben, Apps zu installieren, dann noch einmal „Installieren“."
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { appContext.startActivity(intent) }.onFailure {
                    _updateProgress.value = "Bitte in den Einstellungen unter Sicherheit „Unbekannte Quellen“ für WatchParty erlauben."
                }
                return@runCatching
            }
            val dir = File(appContext.filesDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "watchparty-build-${info.build}.apk")
            val token = WatchPartyAccount.deviceToken() ?: throw WatchPartyException("unauthorized")
            val request = Request.Builder().url("$BaseUrl/v1/updates/$UpdateChannel/${info.file}")
                .header("Authorization", "Bearer $token").get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw WatchPartyException("http_${response.code}")
                val body = response.body ?: throw WatchPartyException("empty")
                val total = body.contentLength().takeIf { it > 0 } ?: info.size
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastShown = -1L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            val percent = if (total > 0) done * 100 / total else -1
                            if (percent != lastShown) {
                                lastShown = percent
                                _updateProgress.value = if (percent >= 0) "Wird geladen … $percent %" else "Wird geladen …"
                            }
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (info.sha256.isNotBlank() && hash != info.sha256) {
                    target.delete()
                    throw WatchPartyException("checksum")
                }
            }
            _updateProgress.value = "Installation wird gestartet …"
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", target)
            val install = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(install)
            Unit
        }.onFailure { error ->
            _updateProgress.value = "Update fehlgeschlagen (${(error as? WatchPartyException)?.code ?: error.message})"
        }
    }

    // ---- HTTP -------------------------------------------------------------------

    private fun request(method: String, path: String, body: String?, auth: Boolean = true): String {
        val builder = Request.Builder().url(BaseUrl + path).header("User-Agent", "WatchParty-AndroidTV")
        if (auth) {
            val token = WatchPartyAccount.deviceToken() ?: throw WatchPartyException("unauthorized")
            builder.header("Authorization", "Bearer $token")
        }
        if (method == "GET") builder.get() else builder.method(method, (body ?: "{}").toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val code = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw WatchPartyException(code?.takeIf { it.isNotBlank() } ?: "http_${response.code}")
            }
            return text
        }
    }
}
