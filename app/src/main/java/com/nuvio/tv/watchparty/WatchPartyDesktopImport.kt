package com.nuvio.tv.watchparty

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.util.Properties

/**
 * One-time copy of the PC's addons and plugin repositories onto the TV.
 *
 * Reads the PC's sections (`pc.*`, java .properties text) and installs through the app's own
 * repositories, so the TV stores them in its normal format. The PC sections are only read.
 */
object WatchPartyDesktopImport {
    private const val TAG = "WatchPartyImport"
    private const val PrimaryProfile = "1"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun addonRepository(): AddonRepository
        fun addonPreferences(): AddonPreferences
        fun pluginManager(): PluginManager
        fun collectionsDataStore(): CollectionsDataStore
    }

    data class Result(val addons: Int, val repositories: Int, val collections: Int)

    suspend fun run(context: Context): Result = withContext(Dispatchers.IO) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        val addons = importAddons(deps)
        val repositories = importPlugins(deps)
        val collections = importCollections(deps)
        WatchPartyAccount.markPcImportDone()
        WatchPartyAccount.markPcCollectionsDone()
        Log.i(TAG, "imported $addons addons, $repositories plugin repositories, $collections collections")
        Result(addons, repositories, collections)
    }

    /** Collections only, for TVs that ran the first import before collections were copied. */
    suspend fun runCollections(context: Context): Int = withContext(Dispatchers.IO) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        importCollections(deps).also {
            WatchPartyAccount.markPcCollectionsDone()
            Log.i(TAG, "imported $it collections")
        }
    }

    /** True when this TV already has addons the user installed (beyond nothing at all). */
    suspend fun hasAddons(context: Context): Boolean = withContext(Dispatchers.IO) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        deps.addonPreferences().installedAddonUrls.first().isNotEmpty()
    }

    private suspend fun importAddons(deps: Deps): Int {
        val properties = readProperties("nuvio_addons") ?: return 0
        val urls = properties.getProperty("installed_addon_urls_$PrimaryProfile")
            ?.let { raw -> runCatching { JSONArray(raw) }.getOrNull() }
            ?: return 0
        val states = properties.getProperty("addon_enabled_states_$PrimaryProfile")
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
        val installed = deps.addonPreferences().installedAddonUrls.first().toSet()
        var count = 0
        for (index in 0 until urls.length()) {
            val url = urls.optString(index).takeIf { it.isNotBlank() } ?: continue
            if (url !in installed) {
                runCatching { deps.addonRepository().addAddon(url) }
                    .onSuccess { count++ }
                    .onFailure { Log.w(TAG, "addon $url", it) }
            }
            if (states?.has(url) == true && !states.optBoolean(url, true)) {
                runCatching { deps.addonRepository().setAddonEnabled(url, false) }
            }
        }
        return count
    }

    private suspend fun importPlugins(deps: Deps): Int {
        val properties = readProperties("nuvio_plugins") ?: return 0
        val state = properties.getProperty("plugins_state_$PrimaryProfile")
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?: return 0
        val repositories = state.optJSONArray("repositories") ?: JSONArray()
        val existing = deps.pluginManager().repositories.first().map { it.url }.toSet()
        var count = 0
        for (index in 0 until repositories.length()) {
            val url = repositories.optJSONObject(index)?.optString("manifestUrl")
                ?.takeIf { it.isNotBlank() } ?: continue
            if (url in existing) continue
            deps.pluginManager().addRepository(url)
                .onSuccess { count++ }
                .onFailure { Log.w(TAG, "plugin repository $url", it) }
        }
        if (state.has("pluginsEnabled")) {
            deps.pluginManager().setPluginsEnabled(state.optBoolean("pluginsEnabled", true))
        }
        return count
    }

    /** The PC stores collections in the same JSON as the TV; ones the TV already has are kept. */
    private suspend fun importCollections(deps: Deps): Int {
        val json = readProperties("nuvio_collections")?.getProperty("collections_$PrimaryProfile") ?: return 0
        val store = deps.collectionsDataStore()
        val incoming = store.importFromJson(json)
        if (incoming.isEmpty()) return 0
        val current = store.getCurrentCollections()
        val existingIds = current.map { it.id }.toSet()
        val added = incoming.filter { it.id !in existingIds }
        if (added.isNotEmpty()) store.setCollections(current + added)
        return added.size
    }

    private fun readProperties(store: String): Properties? {
        val text = runCatching { WatchPartyAccount.readSection("${WatchPartyAccount.DesktopPrefix}$store") }
            .onFailure { Log.w(TAG, "read $store", it) }
            .getOrNull() ?: return null
        return Properties().apply { load(StringReader(text)) }
    }
}
