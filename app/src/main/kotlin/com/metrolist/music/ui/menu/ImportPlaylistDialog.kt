/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.widget.Toast
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalContext
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.TextFieldDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal suspend fun resolvePlaylistImport(
    resolveSongs: suspend () -> Result<List<MediaMetadata>>,
    commit: suspend (List<MediaMetadata>) -> Unit,
): Result<Unit> =
    try {
        commit(resolveSongs().getOrThrow())
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

@Composable
fun ImportPlaylistDialog(
    isVisible: Boolean,
    onResolveSongs: suspend () -> Result<List<MediaMetadata>>,
    playlistTitle: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val loadAllErrorText = stringResource(R.string.error_load_all_songs)
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()

    val textFieldValue by remember { mutableStateOf(TextFieldValue(text = playlistTitle)) }
    var isBusy by remember { mutableStateOf(false) }
    var isCommitting by remember { mutableStateOf(false) }
    var importJob by remember { mutableStateOf<Job?>(null) }

    fun dismiss() {
        if (isCommitting) return
        importJob?.cancel()
        importJob = null
        isBusy = false
        onDismiss()
    }

    LaunchedEffect(isVisible) {
        if (!isVisible) {
            importJob?.cancel()
            importJob = null
            isBusy = false
            isCommitting = false
        }
    }

    if (isVisible) {
        TextFieldDialog(
            icon = { Icon(painter = painterResource(R.drawable.add), contentDescription = null) },
            title = { Text(text = stringResource(R.string.import_playlist)) },
            initialTextFieldValue = textFieldValue,
            autoFocus = false,
            onDismiss = ::dismiss,
            autoDismiss = false,
            isBusy = isBusy,
            dismissEnabled = !isCommitting,
            onDone = { finalName ->
                if (isBusy) return@TextFieldDialog
                isBusy = true
                importJob =
                    coroutineScope.launch(Dispatchers.IO) {
                        resolvePlaylistImport(onResolveSongs) { songs ->
                            withContext(Dispatchers.Main) {
                                isCommitting = true
                            }
                            val newPlaylist = PlaylistEntity(name = finalName)
                            database.withTransaction {
                                insert(newPlaylist)
                                songs.forEach(::insert)
                                val playlist =
                                    playlistBlocking(newPlaylist.id)
                                        ?: throw IllegalStateException("Failed to create imported playlist")
                                addSongsToPlaylist(
                                    playlist,
                                    songs.map { it.id to it.setVideoId },
                                )
                            }
                        }.onSuccess {
                            withContext(Dispatchers.Main) {
                                isBusy = false
                                isCommitting = false
                                importJob = null
                                onDismiss()
                            }
                        }.onFailure { error ->
                            withContext(Dispatchers.Main) {
                                isBusy = false
                                isCommitting = false
                                importJob = null
                                Toast
                                    .makeText(
                                        context,
                                        error.message ?: loadAllErrorText,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }
                        }
                    }
            }
        )
    }
}
