package com.metrolist.innertube.utils

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.pages.LibraryPage
import com.metrolist.innertube.pages.PlaylistPage
import timber.log.Timber
import java.security.MessageDigest

@JvmName("completedLibrary")
suspend fun Result<PlaylistPage>.completed(): Result<PlaylistPage> = runCatching {
    val page = getOrThrow()
    val songs = page.songs.toMutableList()
    var continuation = page.songsContinuation
    val seenContinuations = mutableSetOf<String>()
    var requestCount = 0
    val maxRequests = 50
    var consecutiveEmptyResponses = 0
    
    while (continuation != null && requestCount < maxRequests) {
        if (continuation in seenContinuations) {
            break
        }
        seenContinuations.add(continuation)
        requestCount++
        
        val continuationPage = YouTube.playlistContinuation(continuation).getOrNull() ?: break
        
        if (continuationPage.songs.isEmpty()) {
            consecutiveEmptyResponses++
            if (consecutiveEmptyResponses >= 2) break
        } else {
            consecutiveEmptyResponses = 0
            songs += continuationPage.songs
        }
        
        continuation = continuationPage.continuation
    }
    PlaylistPage(
        playlist = page.playlist,
        songs = songs,
        songsContinuation = null,
        continuation = page.continuation
    )
}

@JvmName("completedPlaylist")
suspend fun Result<LibraryPage>.completed(): Result<LibraryPage> = runCatching {
    val page = getOrThrow()
    val items = page.items.toMutableList()
    var continuation = page.continuation
    val seenContinuations = mutableSetOf<String>()
    var requestCount = 0
    val maxRequests = 50
    var consecutiveEmptyResponses = 0
    
    while (continuation != null && requestCount < maxRequests) {
        if (continuation in seenContinuations) {
            break
        }
        seenContinuations.add(continuation)
        requestCount++
        
        val continuationPage = YouTube.libraryContinuation(continuation).getOrNull() ?: break
        
        if (continuationPage.items.isEmpty()) {
            consecutiveEmptyResponses++
            if (consecutiveEmptyResponses >= 2) break
        } else {
            consecutiveEmptyResponses = 0
            items += continuationPage.items
        }
        
        continuation = continuationPage.continuation
    }
    LibraryPage(
        items = items,
        continuation = null
    )
}

/**
 * A drained list, and whether draining actually reached the end of it.
 *
 * [isComplete] is the entire point. [completed] reports success whether or not it got everything,
 * so a caller holding its result cannot tell a whole list from a prefix — and a caller that
 * deletes local state absent from that list will delete data the user still has.
 */
data class Drained<T>(
    val value: T,
    val isComplete: Boolean,
)

/** One page of a paged response, reduced to the two things draining cares about. */
internal data class DrainPage<T>(
    val items: List<T>,
    val continuation: String?,
)

/**
 * High enough not to be reached by any real library, since stopping early now means declining to
 * delete rather than deleting wrongly. Runaway paging is prevented by loop detection, not by this.
 */
private const val DEFAULT_MAX_DRAIN_REQUESTS = 1000

/**
 * Every page of a playlist, and whether that is genuinely all of them.
 *
 * The outer [Result] fails only when the *initial* page failed. A drain that started but stopped
 * short comes back as `isComplete = false`, carrying however much did arrive, because that prefix
 * is still useful: adding songs from it is harmless, and only removal needs the whole list.
 *
 * Callers that delete local state must check [Drained.isComplete] first. Prefer this over
 * [completed] for those.
 */
@JvmName("drainedPlaylist")
suspend fun Result<PlaylistPage>.drained(
    label: String? = null,
    maxRequests: Int = DEFAULT_MAX_DRAIN_REQUESTS,
): Result<Drained<PlaylistPage>> = runCatching {
    val page = getOrThrow()
    val songs =
        drainPages(
            initialItems = page.songs,
            initialContinuation = page.songsContinuation,
            maxRequests = maxRequests,
            label = label ?: "playlist ${page.playlist.id}",
            fetchPage = { token ->
                YouTube.playlistContinuation(token).map { DrainPage(it.songs, it.continuation) }
            },
        )

    Drained(
        value = PlaylistPage(
            playlist = page.playlist,
            songs = songs.value,
            // Nulled because paging has gone as far as it is going to. Whether that was all the
            // way is what isComplete answers.
            songsContinuation = null,
            continuation = page.continuation,
        ),
        isComplete = songs.isComplete,
    )
}

/**
 * Every page of a library shelf, and whether that is genuinely all of them.
 *
 * See [Result.drained] for a playlist — same contract, same reason to prefer it over [completed].
 */
@JvmName("drainedLibrary")
suspend fun Result<LibraryPage>.drained(
    label: String? = null,
    maxRequests: Int = DEFAULT_MAX_DRAIN_REQUESTS,
): Result<Drained<LibraryPage>> = runCatching {
    val page = getOrThrow()
    val items =
        drainPages(
            initialItems = page.items,
            initialContinuation = page.continuation,
            maxRequests = maxRequests,
            label = label ?: "library",
            fetchPage = { token ->
                YouTube.libraryContinuation(token).map { DrainPage(it.items, it.continuation) }
            },
        )

    Drained(
        value = LibraryPage(items = items.value, continuation = null),
        isComplete = items.isComplete,
    )
}

/**
 * Follows continuation tokens until they run out, reporting whether they did.
 *
 * Takes its fetcher as a parameter so the paging rules can be exercised without a network.
 *
 * Every early stop is a failure to finish, never a quiet end: a failed request, a repeated token
 * and an exhausted page budget all leave an unknown amount unread, and none of them can be told
 * apart from "that was the last page" by looking at what arrived.
 */
internal suspend fun <T> drainPages(
    initialItems: List<T>,
    initialContinuation: String?,
    maxRequests: Int,
    label: String,
    fetchPage: suspend (String) -> Result<DrainPage<T>>,
): Drained<List<T>> {
    val items = initialItems.toMutableList()
    val seenContinuations = mutableSetOf<String>()
    var continuation = initialContinuation

    while (continuation != null) {
        if (!seenContinuations.add(continuation)) {
            Timber.w("Drain[$label] incomplete: continuation looped after ${items.size} items")
            return Drained(items, isComplete = false)
        }
        if (seenContinuations.size > maxRequests) {
            Timber.w("Drain[$label] incomplete: hit the $maxRequests page limit with ${items.size} items")
            return Drained(items, isComplete = false)
        }

        val page =
            fetchPage(continuation).getOrElse { throwable ->
                Timber.w(
                    throwable,
                    "Drain[$label] incomplete: page ${seenContinuations.size} failed after ${items.size} items",
                )
                return Drained(items, isComplete = false)
            }

        items += page.items
        continuation = page.continuation
    }

    Timber.d("Drain[$label] complete: ${items.size} items over ${seenContinuations.size + 1} pages")
    return Drained(items, isComplete = true)
}

fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

fun sha1(str: String): String = MessageDigest.getInstance("SHA-1").digest(str.toByteArray()).toHex()

fun parseCookieString(cookie: String): Map<String, String> =
    cookie.split("; ")
        .filter { it.isNotEmpty() }
        .mapNotNull { part ->
            val splitIndex = part.indexOf('=')
            if (splitIndex == -1) null
            else part.substring(0, splitIndex) to part.substring(splitIndex + 1)
        }
        .toMap()

fun String.parseTime(): Int? {
    try {
        // YouTube Music returns duration with locale-dependent separators
        // (":" en-US, "." some locales, "," EU). Accept all.
        val parts = split(Regex("[:.,]")).map { it.toInt() }
        if (parts.size == 2) {
            return parts[0] * 60 + parts[1]
        }
        if (parts.size == 3) {
            return parts[0] * 3600 + parts[1] * 60 + parts[2]
        }
    } catch (e: Exception) {
        return null
    }
    return null
}

fun isPrivateId(browseId: String): Boolean {
    return browseId.contains("privately")
}
