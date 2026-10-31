@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.FolderCard
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.watchparty.WatchPartyDesktopImport
import dagger.hilt.android.EntryPointAccessors

/**
 * WatchParty: one collection opened from the sidebar, its folders as a grid (as on the PC and
 * Apple TV). A folder opens the existing folder detail screen.
 */
@Composable
fun WatchPartyCollectionScreen(
    collectionId: String,
    onFolderClick: (collectionId: String, folderId: String) -> Unit
) {
    val context = LocalContext.current
    val store = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, WatchPartyDesktopImport.Deps::class.java)
            .collectionsDataStore()
    }
    val collections by store.collections.collectAsState(initial = null)
    val collection = collections?.firstOrNull { it.id == collectionId }

    if (collection == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (collections != null) {
                Text(
                    text = "Diese Sammlung gibt es auf dem Fernseher nicht.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = NuvioTheme.spacing.xxxl, top = NuvioTheme.spacing.xxl)
    ) {
        Text(
            text = collection.title,
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 300.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = NuvioTheme.spacing.lg, end = NuvioTheme.spacing.xxxl, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            itemsIndexed(collection.folders, key = { _, folder -> folder.id }) { _, folder ->
                FolderCard(
                    folder = folder,
                    collection = collection,
                    posterCardStyle = PosterCardDefaults.Style,
                    onClick = { onFolderClick(collection.id, folder.id) },
                    onFocused = {}
                )
            }
        }
    }
}
