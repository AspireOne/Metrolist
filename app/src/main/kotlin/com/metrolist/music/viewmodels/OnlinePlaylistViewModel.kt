/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import com.metrolist.music.constants.SongSortType
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.Album
import javax.inject.Inject

@HiltViewModel
class OnlinePlaylistViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val database: MusicDatabase
) : ViewModel() {
    private val playlistId = savedStateHandle.get<String>("playlistId")!!

    // Check if this is a special podcast playlist (with or without VL prefix)
    private val normalizedPlaylistId = playlistId.removePrefix("VL")
    val isPodcastPlaylist = normalizedPlaylistId == "RDPN" || normalizedPlaylistId == "SE"
    private val isLikedMusicPlaylist = normalizedPlaylistId == "LM"

    /**
     * Totals for the Liked Music playlist, read from the local database instead of from the
     * remote pages.
     *
     * SyncUtils already mirrors the whole of LM into the song table, so these are available
     * immediately and offline, rather than creeping upwards as each remote page of 100 arrives.
     * They can drift from the remote figures by a few songs — a song liked locally may not have
     * been pushed yet, and a locally disliked song stays liked remotely until the un-like lands —
     * so the UI marks them as approximate.
     *
     * Null for every other playlist, which has no local mirror to count.
     */
    val likedSongsCount: StateFlow<Int?> =
        if (isLikedMusicPlaylist) {
            database.likedSongsCount().stateIn(viewModelScope, SharingStarted.Lazily, null)
        } else {
            MutableStateFlow(null)
        }

    val likedSongsTotalDuration: StateFlow<Int?> =
        if (isLikedMusicPlaylist) {
            database.likedSongsTotalDuration().stateIn(viewModelScope, SharingStarted.Lazily, null)
        } else {
            MutableStateFlow(null)
        }

    val playlist = MutableStateFlow<PlaylistItem?>(null)
    val playlistSongs = MutableStateFlow<List<SongItem>>(emptyList())
    private val rawPlaylistSongs = MutableStateFlow<List<SongItem>>(emptyList())

    private val _rawSongsLoadedCount = MutableStateFlow(0)
    val rawSongsLoadedCount = _rawSongsLoadedCount.asStateFlow()

    private val _rawSongsLoadedDuration = MutableStateFlow(0)
    val rawSongsLoadedDuration = _rawSongsLoadedDuration.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore = _isLoadingMore.asStateFlow()

    /**
     * Whether every page of the playlist has been fetched.
     *
     * Anything that acts on the playlist as a whole — bookmarking, exporting, downloading,
     * searching — has to wait for this, or it silently operates on however much happens to have
     * been scrolled into view.
     */
    private val _allSongsLoaded = MutableStateFlow(false)
    val allSongsLoaded = _allSongsLoaded.asStateFlow()

    private val _isLoadingAll = MutableStateFlow(false)
    val isLoadingAll = _isLoadingAll.asStateFlow()

    private val _loadAllError = MutableStateFlow<String?>(null)
    val loadAllError = _loadAllError.asStateFlow()

    /**
     * Incremented after a scroll-driven page succeeds and load-more admission has been released.
     *
     * The list is deduplicated and filtered, so a page can arrive without the visible list growing.
     * This gives the screen a safe completion signal for deciding whether the footer still needs
     * another page.
     */
    private val _loadMoreGeneration = MutableStateFlow(0)
    val loadMoreGeneration = _loadMoreGeneration.asStateFlow()

    val dbPlaylist = database.playlistByBrowseId(playlistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    var continuation: String? = null
        private set

    private val loadMutex = Mutex()

    /** Guards against a continuation cycle. Only touched under [loadMutex]. */
    private val seenContinuations = mutableSetOf<String>()

    init {
        fetchInitialPlaylistData()
    }

    private fun fetchInitialPlaylistData() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            _loadAllError.value = null
            _allSongsLoaded.value = false
            // Under the lock so an in-flight page cannot write its continuation back after the
            // reset and resurrect the previous load.
            loadMutex.withLock {
                continuation = null
                seenContinuations.clear()
                playlist.value = null
                rawPlaylistSongs.value = emptyList()
                playlistSongs.value = emptyList()
                _rawSongsLoadedCount.value = 0
                _rawSongsLoadedDuration.value = 0
            }

            if (isPodcastPlaylist) {
                // Use special podcast playlist APIs
                fetchPodcastPlaylist()
                // Podcast playlists arrive in a single response, so there is never more to load.
                _allSongsLoaded.value = true
            } else {
                // Use regular playlist API
                fetchRegularPlaylist()
            }
        }
    }

    private suspend fun fetchPodcastPlaylist() {
        when (normalizedPlaylistId) {
            "RDPN" -> {
                YouTube.newEpisodes()
                    .onSuccess { episodes ->
                        playlist.value = PlaylistItem(
                            id = playlistId,
                            title = "New Episodes",
                            author = null,
                            songCountText = "${episodes.size} episodes",
                            thumbnail = episodes.firstOrNull()?.thumbnail ?: "",
                            playEndpoint = null,
                            shuffleEndpoint = null,
                            radioEndpoint = null,
                        )
                        replaceRawSongs(episodes)
                        _isLoading.value = false
                    }.onFailure { throwable ->
                        _error.value = throwable.message ?: "Failed to load new episodes"
                        _isLoading.value = false
                        reportException(throwable)
                    }
            }
            "SE" -> {
                timber.log.Timber.d("[SE_LOCAL] Fetching SE playlist...")
                val result = YouTube.episodesForLater()
                val episodes = result.getOrNull() ?: emptyList()
                timber.log.Timber.d("[SE_LOCAL] YouTube API result: ${if (result.isSuccess) "success" else "failed"}, ${episodes.size} episodes")

                if (result.isSuccess && episodes.isNotEmpty()) {
                    // Use YouTube episodes
                    playlist.value = PlaylistItem(
                        id = playlistId,
                        title = "Episodes for Later",
                        author = null,
                        songCountText = "${episodes.size} episodes",
                        thumbnail = episodes.firstOrNull()?.thumbnail ?: "",
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    )
                    replaceRawSongs(episodes)
                    _isLoading.value = false
                } else {
                    // Fall back to local saved episodes when API fails or returns empty
                    timber.log.Timber.d("[SE_LOCAL] Falling back to local saved episodes")
                    loadLocalSavedEpisodes()
                }
            }
            else -> {
                _error.value = "Unknown podcast playlist"
                _isLoading.value = false
            }
        }
    }

    private suspend fun fetchRegularPlaylist() {
        YouTube.playlist(playlistId)
            .onSuccess { playlistPage ->
                playlist.value = playlistPage.playlist
                replaceRawSongs(playlistPage.songs)
                continuation = playlistPage.songsContinuation
                _allSongsLoaded.value = continuation == null
                _isLoading.value = false
            }.onFailure { throwable ->
                _error.value = throwable.message ?: "Failed to load playlist"
                _isLoading.value = false
                reportException(throwable)
            }
    }

    private suspend fun loadLocalSavedEpisodes() {
        timber.log.Timber.d("[SE_LOCAL] loadLocalSavedEpisodes called")
        val savedEpisodes = database.savedPodcastEpisodes(SongSortType.CREATE_DATE, true).firstOrNull() ?: emptyList()
        timber.log.Timber.d("[SE_LOCAL] Found ${savedEpisodes.size} saved episodes")
        savedEpisodes.forEachIndexed { index, ep ->
            timber.log.Timber.d("[SE_LOCAL] Episode $index: id=${ep.song.id}, title=${ep.song.title}, isEpisode=${ep.song.isEpisode}, inLibrary=${ep.song.inLibrary}")
        }
        if (savedEpisodes.isNotEmpty()) {
            // Convert local Song entities to SongItem format
            val songItems = savedEpisodes.map { song ->
                SongItem(
                    id = song.song.id,
                    title = song.song.title,
                    artists = song.artists.map { Artist(it.id, it.name) },
                    album = song.album?.let { com.metrolist.innertube.models.Album(it.id, it.title) },
                    duration = song.song.duration,
                    thumbnail = song.song.thumbnailUrl ?: "",
                    explicit = song.song.explicit,
                    endpoint = null,
                )
            }
            timber.log.Timber.d("[SE_LOCAL] Converted to ${songItems.size} SongItems")
            playlist.value = PlaylistItem(
                id = playlistId,
                title = "Episodes for Later",
                author = null,
                songCountText = "${songItems.size} episodes",
                thumbnail = songItems.firstOrNull()?.thumbnail ?: "",
                playEndpoint = null,
                shuffleEndpoint = null,
                radioEndpoint = null,
            )
            replaceRawSongs(songItems)
            timber.log.Timber.d("[SE_LOCAL] After filter: ${playlistSongs.value.size} episodes, setting playlistSongs")
            _isLoading.value = false
            timber.log.Timber.d("[SE_LOCAL] Done, isLoading=false")
        } else {
            timber.log.Timber.d("[SE_LOCAL] No saved episodes found")
            _error.value = "No saved episodes"
            _isLoading.value = false
        }
    }

    private enum class PageOutcome {
        /** A page arrived and more remain. */
        FETCHED,

        /** Every page has now been fetched. */
        COMPLETE,

        /** Nothing was fetched; [loadAllError] says why. The playlist is still incomplete. */
        FAILED,
    }

    /**
     * Fetches one more page.
     *
     * Never reports completion it cannot vouch for: a failed request and a looping continuation
     * both leave the playlist incomplete, because in neither case can we tell how much is missing.
     */
    private suspend fun fetchNextPage(): PageOutcome {
        val token = continuation ?: return PageOutcome.COMPLETE
        if (token in seenContinuations) {
            // YouTube handed back a token it already gave us. Following it would loop forever, and
            // there is no way to know what is missing, so this is a failure and not an end.
            _loadAllError.value = "Playlist paging looped"
            return PageOutcome.FAILED
        }
        var outcome = PageOutcome.FAILED
        YouTube.playlistContinuation(token)
            .onSuccess { page ->
                // Recorded only once it succeeds, so that a page which failed for a transient
                // reason can be retried rather than being mistaken for a loop.
                seenContinuations.add(token)
                val currentSongs = rawPlaylistSongs.value.toMutableList()
                currentSongs.addAll(page.songs)
                replaceRawSongs(currentSongs)
                continuation = page.continuation
                _allSongsLoaded.value = continuation == null
                outcome = if (continuation == null) PageOutcome.COMPLETE else PageOutcome.FETCHED
            }.onFailure { throwable ->
                reportException(throwable)
                _loadAllError.value = throwable.message ?: "Failed to load more songs"
            }
        return outcome
    }

    /** Fetches the next page as the user scrolls towards the end of the list. */
    fun loadMoreSongs() {
        if (_isLoadingAll.value || continuation == null || !_isLoadingMore.compareAndSet(false, true)) return

        viewModelScope.launch(Dispatchers.IO) {
            val outcome =
                try {
                    loadMutex.withLock {
                        _loadAllError.value = null
                        fetchNextPage()
                    }
                } finally {
                    _isLoadingMore.value = false
                }
            if (outcome == PageOutcome.FETCHED) {
                _loadMoreGeneration.value++
            }
        }
    }

    /**
     * Fetches every remaining page and returns the playlist in full.
     *
     * Fails rather than returning what it managed to get: callers use this for actions that cover
     * the whole playlist — bookmarking, exporting, downloading, queueing — where quietly acting on
     * a prefix is worse than not acting at all.
     *
     * The list returned is the same one the screen displays, so it is deduplicated and honours the
     * hide-video-songs preference.
     */
    suspend fun awaitAllSongs(): Result<List<SongItem>> =
        withContext(Dispatchers.IO) {
            // Serialised against scroll-driven paging so the two can never fetch the same
            // continuation, and so the loading flags below are only ever touched by one caller.
            loadMutex.withLock {
                if (continuation == null) {
                    return@withLock Result.success(playlistSongs.value)
                }
                _isLoadingAll.value = true
                _loadAllError.value = null
                try {
                    var result: Result<List<SongItem>>? = null
                    while (result == null) {
                        result = when (fetchNextPage()) {
                            PageOutcome.FETCHED -> null // Keep going.
                            PageOutcome.COMPLETE -> Result.success(playlistSongs.value)
                            PageOutcome.FAILED ->
                                Result.failure(
                                    IOException(_loadAllError.value ?: "Failed to load all songs"),
                                )
                        }
                    }
                    result
                } finally {
                    _isLoadingAll.value = false
                }
            }
        }

    /**
     * Starts fetching every remaining page without waiting for the result.
     *
     * A large playlist costs one request per 100 songs, so this only runs when something actually
     * needs the complete list, rather than on every visit to the screen. Progress and failure are
     * published through [isLoadingAll], [allSongsLoaded] and [loadAllError].
     */
    fun loadAllSongs() {
        if (_isLoadingAll.value || _allSongsLoaded.value) return
        viewModelScope.launch { awaitAllSongs() }
    }

    fun retry() {
        fetchInitialPlaylistData()
    }

    private fun applySongFilters(songs: List<SongItem>): List<SongItem> {
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        return songs.filterVideoSongs(hideVideoSongs)
    }

    private fun replaceRawSongs(songs: List<SongItem>) {
        val deduplicated = songs.distinctBy { it.id }
        rawPlaylistSongs.value = deduplicated
        playlistSongs.value = applySongFilters(deduplicated)
        _rawSongsLoadedCount.value = deduplicated.size
        _rawSongsLoadedDuration.value = deduplicated.sumOf { it.duration ?: 0 }
    }
}
