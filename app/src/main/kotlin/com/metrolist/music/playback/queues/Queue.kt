/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.metadata
import com.metrolist.music.models.MediaMetadata

interface Queue {
    val preloadItem: MediaMetadata?

    /**
     * True when this queue is algorithmically generated (a radio / automix) rather than something
     * the user picked track by track. Only radio queues drop disliked songs.
     *
     * Album radios report true even though [getInitialStatus] returns the deliberately-chosen album:
     * their mix only ever arrives through [nextPage], so filtering continuations alone leaves the
     * album itself untouched.
     */
    val isRadio: Boolean get() = false

    /**
     * True when [getInitialStatus] itself returns generated recommendations rather than something
     * the user chose, which is what decides whether the opening batch is filtered.
     *
     * Separate from [isRadio] because the album radios are both at once: their opening batch is the
     * album the user picked, and only what follows is generated.
     */
    val hasGeneratedInitialItems: Boolean get() = false

    suspend fun getInitialStatus(): Status

    fun hasNextPage(): Boolean

    suspend fun nextPage(): List<MediaItem>

    data class Status(
        val title: String?,
        val items: List<MediaItem>,
        val mediaItemIndex: Int,
        val position: Long = 0L,
    ) {
        fun filterExplicit(enabled: Boolean = true) =
            if (enabled) {
                copy(
                    items = items.filterExplicit(),
                )
            } else {
                this
            }

        fun filterVideoSongs(disableVideos: Boolean = false) =
            if (disableVideos) {
                copy(
                    items = items.filterVideoSongs(true),
                )
            } else {
                this
            }

        /**
         * Unlike the other two filters this has to move [mediaItemIndex], since dropping an item
         * before the starting position would otherwise silently start playback on the wrong track.
         *
         * The item at [mediaItemIndex] is always kept: starting a radio from a song is a deliberate
         * request to hear that song, even one previously disliked.
         */
        fun filterDisliked(dislikedIds: Set<String>): Status {
            if (dislikedIds.isEmpty()) return this

            val kept = ArrayList<MediaItem>(items.size)
            var startIndex = mediaItemIndex
            items.forEachIndexed { index, item ->
                if (index == mediaItemIndex || item.mediaId !in dislikedIds) {
                    kept.add(item)
                } else if (index < mediaItemIndex) {
                    startIndex--
                }
            }
            return copy(items = kept, mediaItemIndex = startIndex)
        }
    }
}

fun List<MediaItem>.filterExplicit(enabled: Boolean = true) =
    if (enabled) {
        filterNot {
            it.metadata?.explicit == true
        }
    } else {
        this
    }

fun List<MediaItem>.filterVideoSongs(disableVideos: Boolean = false) =
    if (disableVideos) {
        filterNot { it.metadata?.isVideoSong == true }
    } else {
        this
    }

/**
 * Takes the ids rather than a DAO so this file stays free of a database dependency.
 */
fun List<MediaItem>.filterDisliked(dislikedIds: Set<String>) =
    if (dislikedIds.isEmpty()) {
        this
    } else {
        filterNot { it.mediaId in dislikedIds }
    }
