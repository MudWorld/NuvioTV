package com.nuvio.tv.watchparty

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.data.local.CollectionsDataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.StringReader
import java.util.Properties

/**
 * WatchParty glue to the app: the Nuvio sync sign-in, and what the TV takes over from the PC's
 * sections (`pc.*`, java .properties text, only read): which collections the sidebar lists.
 */
object WatchPartyDesktopImport {
    private const val TAG = "WatchPartyImport"
    private const val PrimaryProfile = "1"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun authManager(): AuthManager
        fun startupSyncService(): StartupSyncService
        fun collectionsDataStore(): CollectionsDataStore
        fun tmdbCollectionSourceResolver(): com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
        fun layoutPreferenceDataStore(): com.nuvio.tv.data.local.LayoutPreferenceDataStore
    }

    private fun deps(context: Context) =
        EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)

    suspend fun signInSync(context: Context, email: String, password: String): Result<Unit> =
        deps(context).authManager().signInWithEmail(email, password)

    suspend fun signOutSync(context: Context) = deps(context).authManager().signOut()

    /** Signs the app's sync in with the session the server issued for an approved TV code. */
    suspend fun importSyncSession(context: Context, tokenResponseJson: String): Result<Unit> =
        deps(context).authManager().importWatchPartySession(tokenResponseJson)

    fun requestSync(context: Context) = deps(context).startupSyncService().requestSyncNow()

    /** The sidebar shows the collections the PC has in its sidebar (read at every start). */
    suspend fun followPcCollectionLayout(context: Context) = withContext(Dispatchers.IO) {
        val settings = readProperties("nuvio_collection_mobile_settings")
            ?.getProperty("collection_mobile_settings_$PrimaryProfile")
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
        val sidebar = settings?.optJSONArray("sidebar_collection_ids") ?: return@withContext
        WatchPartyAccount.setSidebarCollectionIds((0 until sidebar.length()).mapNotNull { index ->
            sidebar.optString(index).takeIf { it.isNotBlank() }
        })
    }

    /** The floating sidebar that looks like the Apple TV one (with "Sammlungen"). */
    suspend fun useModernSidebar(context: Context) = withContext(Dispatchers.IO) {
        deps(context).layoutPreferenceDataStore().setModernSidebarEnabled(true)
    }

    private fun readProperties(store: String): Properties? {
        val text = runCatching { WatchPartyAccount.readSection("${WatchPartyAccount.DesktopPrefix}$store") }
            .onFailure { Log.w(TAG, "read $store", it) }
            .getOrNull() ?: return null
        return Properties().apply { load(StringReader(text)) }
    }
}
