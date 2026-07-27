/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.constants.AddToPlaylistSortDescendingKey
import com.metrolist.music.constants.AddToPlaylistSortTypeKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.PlaylistSortType
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.CreatePlaylistDialog
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.PlaylistListItem
import com.metrolist.music.ui.component.SortHeader
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.PlaylistsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.withContext
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.FilterChip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.FilterChipDefaults
import com.metrolist.music.LocalSyncUtils

data class PlaylistAddPayload(
    val songs: List<MediaMetadata>,
    val remoteSourcePlaylistId: String? = null,
)

internal data class PreparedPlaylistAddition(
    val songs: List<MediaMetadata>,
    val useBulkRemoteAdd: Boolean,
)

internal fun preparePlaylistAddition(
    payload: PlaylistAddPayload,
    duplicateIdsToSkip: Set<String>,
): PreparedPlaylistAddition =
    PreparedPlaylistAddition(
        songs = payload.songs.filterNot { it.id in duplicateIdsToSkip },
        useBulkRemoteAdd =
            duplicateIdsToSkip.isEmpty() && payload.remoteSourcePlaylistId != null,
    )

private sealed interface PlaylistAddState {
    data object Idle : PlaylistAddState
    data class Resolving(val target: Playlist) : PlaylistAddState
    data class AwaitingDuplicateDecision(
        val target: Playlist,
        val payload: PlaylistAddPayload,
        val duplicates: Set<String>,
    ) : PlaylistAddState
    data object Enqueued : PlaylistAddState
}

@Composable
fun AddToPlaylistDialog(
    isVisible: Boolean,
    allowSyncing: Boolean = true,
    initialTextFieldValue: String? = null,
    onResolveSongs: suspend () -> Result<PlaylistAddPayload>,
    // Cheap preview used only for highlighting. It may be incomplete and is never substituted by
    // the authoritative resolver, whose work can be expensive and must remain side-effect free.
    onPreviewSongIds: (suspend () -> List<String>)? = null,
    onDismiss: () -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val loadAllErrorText = stringResource(R.string.error_load_all_songs)
    val database = LocalDatabase.current
    val syncUtils = LocalSyncUtils.current
    val coroutineScope = rememberCoroutineScope()
    val (sortType, onSortTypeChange) = rememberEnumPreference(
        AddToPlaylistSortTypeKey,
        PlaylistSortType.NAME
    )
    val (sortDescending, onSortDescendingChange) = rememberPreference(
        AddToPlaylistSortDescendingKey,
        false
    )
    val playlists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    val (innerTubeCookie) = rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }
    var showCreatePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var addState by remember { mutableStateOf<PlaylistAddState>(PlaylistAddState.Idle) }
    var resolveJob by remember { mutableStateOf<Job?>(null) }
    var previewSongIds by remember { mutableStateOf<List<String>?>(null) }
    var playlistsContainingSong by remember {
        mutableStateOf<Set<String>>(emptySet())
    }

    fun enqueue(
        target: Playlist,
        payload: PlaylistAddPayload,
        skipDuplicates: Set<String> = emptySet(),
    ) {
        if (addState is PlaylistAddState.Enqueued) return
        addState = PlaylistAddState.Enqueued
        val prepared = preparePlaylistAddition(payload, skipDuplicates)
        syncUtils.enqueuePlaylistAddition(
            target = target,
            songs = prepared.songs,
            remoteSourcePlaylistId = payload.remoteSourcePlaylistId,
            useBulkRemoteAdd = prepared.useBulkRemoteAdd,
        )
        onDismiss()
    }

    fun dismiss() {
        if (addState is PlaylistAddState.Resolving) {
            resolveJob?.cancel()
        }
        resolveJob = null
        addState = PlaylistAddState.Idle
        onDismiss()
    }

    LaunchedEffect(isVisible, playlists.isEmpty()) {
        if (!isVisible || playlists.isEmpty()) return@LaunchedEffect
        if (previewSongIds != null || onPreviewSongIds == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            previewSongIds = onPreviewSongIds()
        }
    }
    LaunchedEffect(isVisible, previewSongIds, playlists) {
        if (!isVisible) {
            playlistsContainingSong = emptySet()
            previewSongIds = null
            resolveJob?.cancel()
            resolveJob = null
            addState = PlaylistAddState.Idle
            return@LaunchedEffect
        }
        val ids = previewSongIds ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            playlistsContainingSong = playlists
                .filter { database.playlistDuplicates(it.id, ids).isNotEmpty() }
                .map { it.id }
                .toSet()
        }
    }

    if (isVisible) {
        ListDialog(
            onDismiss = ::dismiss,
        ) {
            item {
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.7f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "buttonScale"
                )
                FilledTonalButton(
                    onClick = { showCreatePlaylistDialog = true},
                    enabled = addState is PlaylistAddState.Idle,
                    shape = RoundedCornerShape(50),
                    interactionSource = interactionSource,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.add),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.create_playlist),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (playlists.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            PlaylistSortType.entries.forEach { type ->
                                val selected = sortType == type
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSortTypeChange(type) },
                                    enabled = addState is PlaylistAddState.Idle,
                                    shape = RoundedCornerShape(50),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        borderWidth = 0.dp,
                                        selectedBorderWidth = 0.dp,
                                    ),
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                    label = {
                                        Text(
                                            text = stringResource(when (type) {
                                                PlaylistSortType.CREATE_DATE  -> R.string.sort_by_create_date
                                                PlaylistSortType.NAME         -> R.string.sort_by_name
                                                PlaylistSortType.SONG_COUNT   -> R.string.sort_by_song_count
                                                PlaylistSortType.LAST_UPDATED -> R.string.sort_by_last_updated
                                            }),
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    }
                                )
                            }
                        }

                        val arrowBg by animateColorAsState(
                            targetValue = if (sortDescending) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "arrowBg"
                        )
                        val arrowFg by animateColorAsState(
                            targetValue = if (sortDescending) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "arrowFg"
                        )
                        IconToggleButton(
                            checked = sortDescending,
                            onCheckedChange = { onSortDescendingChange(it) },
                            enabled = addState is PlaylistAddState.Idle,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(arrowBg)
                                .size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (sortDescending) R.drawable.arrow_downward else R.drawable.arrow_upward
                                ),
                                contentDescription = stringResource(
                                    if (sortDescending) R.string.sort_descending else R.string.sort_ascending
                                ),
                                tint = arrowFg,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            if (addState is PlaylistAddState.Resolving) {
                item(key = "resolving_playlist_source") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }

            items(playlists) { playlist ->
                val containsSong = playlist.id in playlistsContainingSong
                val rowBg by animateColorAsState(
                    targetValue = if (containsSong)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else Color.Transparent,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "playlistBg"
                )
                PlaylistListItem(
                    playlist = playlist,
                    modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(rowBg)
                    .clickable(enabled = addState is PlaylistAddState.Idle, onClick = click@{
                        if (addState !is PlaylistAddState.Idle) return@click
                        addState = PlaylistAddState.Resolving(playlist)
                        resolveJob =
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val payload = onResolveSongs().getOrThrow()
                                    if (payload.songs.isEmpty()) {
                                        withContext(Dispatchers.Main) {
                                            addState = PlaylistAddState.Enqueued
                                            onDismiss()
                                        }
                                        return@launch
                                    }
                                    val duplicates =
                                        database
                                            .playlistDuplicates(
                                                playlist.id,
                                                payload.songs.map { it.id },
                                            ).toSet()
                                    withContext(Dispatchers.Main) {
                                        if (duplicates.isEmpty()) {
                                            enqueue(playlist, payload)
                                        } else {
                                            addState =
                                                PlaylistAddState.AwaitingDuplicateDecision(
                                                    target = playlist,
                                                    payload = payload,
                                                    duplicates = duplicates,
                                                )
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (error: Exception) {
                                    withContext(Dispatchers.Main) {
                                        addState = PlaylistAddState.Idle
                                        Toast
                                            .makeText(
                                                context,
                                                error.message ?: loadAllErrorText,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                }
                            }
                    })
                )
            }
        }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            initialTextFieldValue = initialTextFieldValue,
            allowSyncing = allowSyncing
        )
    }

    // duplicate songs warning
        val pending = addState as? PlaylistAddState.AwaitingDuplicateDecision
        if (pending != null) {
            DefaultDialog(
                title = { Text(stringResource(R.string.duplicates)) },
                buttons = {
                    TextButton(
                        onClick = {
                            enqueue(pending.target, pending.payload, pending.duplicates)
                        }
                    ) {
                        Text(stringResource(R.string.skip_duplicates))
                    }

                    TextButton(
                        onClick = {
                            enqueue(pending.target, pending.payload)
                        }
                    ) {
                        Text(stringResource(R.string.add_anyway))
                    }

                    TextButton(
                        onClick = {
                            addState = PlaylistAddState.Idle
                        }
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
                onDismiss = {
                    addState = PlaylistAddState.Idle
                }
            ) {
                Text(
                    text = if (pending.duplicates.size == 1) {
                        stringResource(R.string.duplicates_description_single)
                    } else {
                        stringResource(R.string.duplicates_description_multiple, pending.duplicates.size)
                    },
                    textAlign = TextAlign.Start,
                    modifier = Modifier.align(Alignment.Start)
                )
            }
        }
}
