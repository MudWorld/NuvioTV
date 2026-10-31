package com.nuvio.tv.ui.screens.home

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.watchparty.WatchPartyDesktopImport
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.lifecycle.viewModelScope

/**
 * WatchParty: on Home every folder of a collection is a category of its own ("Netflix" with its
 * titles below), like the catalog rows, instead of one row of folder tiles.
 *
 * The rows are synthetic catalog rows (addonId [WatchPartyFolderAddonId], catalogId
 * "<collectionId>|<folderId>"); "see all" opens the folder.
 */
internal const val WatchPartyFolderAddonId = "wp_folder"
private const val FolderRowItems = 30
private const val SourcesPerFolder = 2

internal fun watchPartyFolderRowKey(collectionId: String, folderId: String) = "$collectionId|$folderId"

/** Loaded rows of a collection's folders, in folder order; folders still loading are left out. */
internal fun HomeViewModel.watchPartyFolderRows(collection: Collection): List<CatalogRow> =
    collection.folders.mapNotNull { folder ->
        watchPartyFolderRowCache[watchPartyFolderRowKey(collection.id, folder.id)]?.takeIf { it.items.isNotEmpty() }
    }

/** Loads the first titles of every folder not loaded yet (four folders at a time). */
internal fun HomeViewModel.loadWatchPartyFolderRows(collections: List<Collection>) {
    val missing = collections.flatMap { collection ->
        collection.folders
            .filter { !watchPartyFolderRowCache.containsKey(watchPartyFolderRowKey(collection.id, it.id)) }
            .map { collection to it }
    }
    if (missing.isEmpty()) return
    watchPartyFolderJob?.cancel()
    watchPartyFolderJob = viewModelScope.launch(Dispatchers.IO) {
        val resolver = EntryPointAccessors
            .fromApplication(appContext, WatchPartyDesktopImport.Deps::class.java)
            .tmdbCollectionSourceResolver()
        val permits = Semaphore(4)
        missing.map { (collection, folder) ->
            async {
                permits.withPermit {
                    val results = folder.sources.filterIsInstance<TmdbCollectionSource>().take(SourcesPerFolder).mapNotNull { source ->
                        runCatching {
                            (resolver.resolve(source, 1).first { it !is NetworkResult.Loading } as? NetworkResult.Success)?.data
                        }.onFailure { Log.w("WatchPartyHome", "folder ${folder.title}", it) }.getOrNull()
                    }
                    val first = results.firstOrNull() ?: return@withPermit
                    val row = first.copy(
                        addonId = WatchPartyFolderAddonId,
                        addonName = collection.title,
                        catalogId = watchPartyFolderRowKey(collection.id, folder.id),
                        catalogName = folder.title,
                        items = interleave(results.map { it.items }).take(FolderRowItems),
                        hasMore = false,
                        isLoading = false
                    )
                    watchPartyFolderRowCache[watchPartyFolderRowKey(collection.id, folder.id)] = row
                    scheduleUpdateCatalogRows()
                }
            }
        }.awaitAll()
    }
}

/**
 * Collection categories go right below the first two catalog rows ("Popular - Movies" and
 * "Popular - Series"), not after every catalog of every addon.
 */
internal fun watchPartyPlaceFolderRows(rows: List<HomeRow>): List<HomeRow> {
    val isFolderRow = { row: HomeRow -> row is HomeRow.Catalog && row.row.addonId == WatchPartyFolderAddonId }
    val folderRows = rows.filter(isFolderRow)
    if (folderRows.isEmpty()) return rows
    val others = rows.filterNot(isFolderRow)
    var catalogsSeen = 0
    var insertAt = others.size
    for ((index, row) in others.withIndex()) {
        if (row is HomeRow.Catalog && ++catalogsSeen == 2) {
            insertAt = index + 1
            break
        }
    }
    return others.take(insertAt) + folderRows + others.drop(insertAt)
}

/** Movies and series alternately, each title once. */
private fun interleave(lists: List<List<MetaPreview>>): List<MetaPreview> {
    val seen = HashSet<String>()
    val out = ArrayList<MetaPreview>()
    val longest = lists.maxOfOrNull { it.size } ?: 0
    for (index in 0 until longest) {
        for (list in lists) {
            val item = list.getOrNull(index) ?: continue
            if (seen.add("${item.apiType}:${item.id}")) out += item
        }
    }
    return out
}
