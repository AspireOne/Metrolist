/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime

@Immutable
@Entity(
    tableName = "song",
    indices = [
        Index(
            value = ["albumId"],
        ),
    ],
)
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val duration: Int = -1, // in seconds
    val thumbnailUrl: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    @ColumnInfo(defaultValue = "0")
    val explicit: Boolean = false,
    val year: Int? = null,
    val date: LocalDateTime? = null, // ID3 tag property
    val dateModified: LocalDateTime? = null, // file property
    val liked: Boolean = false,
    val likedDate: LocalDateTime? = null,
    val totalPlayTime: Long = 0, // in milliseconds
    val inLibrary: LocalDateTime? = null,
    val dateDownload: LocalDateTime? = null,
    @ColumnInfo(name = "isLocal", defaultValue = false.toString())
    val isLocal: Boolean = false,
    val libraryAddToken: String? = null,
    val libraryRemoveToken: String? = null,
    @ColumnInfo(defaultValue = "0")
    val lyricsOffset: Int = 0,
    @ColumnInfo(defaultValue = true.toString())
    val romanizeLyrics: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val isDownloaded: Boolean = false,
    @ColumnInfo(name = "isUploaded", defaultValue = false.toString())
    val isUploaded: Boolean = false,
    @ColumnInfo(name = "isVideo", defaultValue = false.toString())
    val isVideo: Boolean = false,
    @ColumnInfo(name = "isEpisode", defaultValue = false.toString())
    val isEpisode: Boolean = false,
    @ColumnInfo(name = "playbackPosition", defaultValue = "NULL")
    val playbackPosition: Long? = null,
    @ColumnInfo(name = "uploadEntityId", defaultValue = "NULL")
    val uploadEntityId: String? = null,
    @ColumnInfo(name = "isCached", defaultValue = "0")
    val isCached: Boolean = false,
    @ColumnInfo(name = "disliked", defaultValue = "0")
    val disliked: Boolean = false,
    @ColumnInfo(name = "dislikedDate", defaultValue = "NULL")
    val dislikedDate: LocalDateTime? = null,
) {
    fun localToggleLike() =
        copy(
            liked = !liked,
            likedDate = if (!liked) LocalDateTime.now() else null,
        )

    fun toggleLike() =
        copy(
            liked = !liked,
            likedDate = if (!liked) LocalDateTime.now() else null,
            inLibrary = if (!liked) inLibrary ?: LocalDateTime.now() else inLibrary,
            // Liked and disliked are opposite poles of one axis: becoming liked clears a dislike.
            disliked = if (!liked) false else disliked,
            dislikedDate = if (!liked) null else dislikedDate,
        ).also {
            CoroutineScope(Dispatchers.IO).launch {
                YouTube.likeVideo(id, !liked)
            }
        }

    /**
     * Dislike is a local-only signal: YouTube's API exposes no dislike/rate endpoint, and the flag
     * exists to keep songs out of radio queues rather than to express anything to YouTube.
     *
     * Deliberately has no `.also { ... }` remote call, unlike [toggleLike]. When a dislike clears an
     * existing like, that un-like still has to reach YouTube or the next liked-songs sync restores
     * it - but that push has to go through `SyncUtils` (retry, login guard, Last.fm love clearing),
     * which an entity cannot reach. Call sites are responsible for it.
     */
    fun localToggleDislike() =
        copy(
            disliked = !disliked,
            dislikedDate = if (!disliked) LocalDateTime.now() else null,
            liked = if (!disliked) false else liked,
            likedDate = if (!disliked) null else likedDate,
        )

    fun toggleDislike() = localToggleDislike()

    fun toggleLibrary(syncToYouTube: Boolean = true) =
        copy(
            liked = if (inLibrary == null) liked else false,
            inLibrary = if (inLibrary == null) LocalDateTime.now() else null,
            likedDate = if (inLibrary == null) likedDate else null,
        ).also {
            if (syncToYouTube) {
                CoroutineScope(Dispatchers.IO).launch {
                    // Use the new reliable method that fetches fresh tokens
                    val addToLibrary = inLibrary == null
                    YouTube.toggleSongLibrary(id, addToLibrary)
                }
            }
        }

    fun toggleUploaded() =
        copy(
            isUploaded = !isUploaded,
        )
}
