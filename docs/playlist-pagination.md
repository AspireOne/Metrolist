# Online playlist pagination

Online playlist screens deliberately load only the first YouTube page on entry. Large playlists can
contain thousands of songs, so eagerly draining every continuation makes opening the screen slow and
causes unnecessary network traffic.

## Display state

`OnlinePlaylistViewModel` owns two song lists:

- a raw, ID-deduplicated list used for loaded counts and duration;
- a visible list derived from the raw list after applying the hide-video preference.

The raw list is required because a successful page can add no visible rows. A continuation still
means the playlist is incomplete in that case. The screen keeps the paging footer available and may
advance through consecutive invisible pages. It shows the final empty state only after the
continuation has ended.

Counts shown before completion are explicitly labelled as loaded progress and omit duration. Once
the continuation ends, count and duration come from the complete raw list. Liked Music is the
exception: it continues to use its approximate database-backed totals.

Scroll paging and explicit load-all calls share one mutex and continuation history. A failed request
or repeated token leaves the playlist incomplete and retryable; neither condition may be represented
as successful completion.

## Whole-playlist operations

Anything whose meaning is "the whole playlist" must call `resolveAllSongs`/`awaitAllSongs` and treat
failure as no action. Examples include bookmarking, importing, exporting, downloading, queueing, and
adding one online playlist to another. A loaded prefix must never be silently accepted.

The hide-video preference still applies to playlist-wide actions initiated from the screen. Keeping
raw rows for truthful progress does not change which songs those actions operate on.

## Resolver boundary

Resolvers obtain and prepare source `MediaMetadata`; they do not write to a destination playlist or
launch remote playlist edits. Resolution is cancellable. Cancellation or failure before acceptance
must produce no local or remote mutation.

`AddToPlaylistDialog` admits one destination at a time and binds the destination, resolved payload,
and duplicate set in one immutable pending operation. Once the user accepts that operation it is
handed to `SyncUtils`'s application-owned scope, so dismissing the composable cannot interrupt it.
Local metadata and mappings commit in one transaction. The remote side then uses either one
playlist-level bulk request or per-song requests, never both.

Per-song remote edits isolate failures. One song's rejected edit — a deleted, private, or
region-blocked video, or a server error, none of which the InnerTube layer retries — must not
prevent the songs behind it from being attempted, because every song in the batch was already
committed locally and `executeSyncPlaylist` rebuilds the local list from the remote one. A song that
was never attempted is a song the next sync deletes. Every song is therefore attempted, the residue
is retried once as a whole second pass (skipped when the device is offline, where it would only
double a hopeless batch), and whatever still fails is logged and reported as one aggregate. The
bulk path has nothing to isolate and reports the whole batch on failure. Cancellation propagates
instead of being recorded as a failure, so it is never reported as one.

Failed remote edits do not roll back the local rows, per the optimistic local-first policy below.

`ImportPlaylistDialog` also resolves first. Playlist creation, metadata insertion, and mappings are
one transaction, so continuation failure cannot leave an empty ghost playlist. A successfully
resolved genuinely empty source may intentionally create an empty playlist.

## Bookmark serialization

Header and menu bookmark controls call the same `SyncUtils.setOnlinePlaylistBookmarked` operation.
Its active browse-ID set is process-wide, so dismissing and reopening a menu cannot reset the guard.
Requests specify the desired state explicitly; a second request for an active browse ID is ignored
instead of being queued as a later toggle.

Saving resolves every page before writing. Unbookmarking does not fetch songs. Immediately before
the local transaction, the operation re-queries by browse ID under the full-sync execution mutex and
reuses an existing row if one appeared. The mutex remains held through the corresponding YouTube
call so full sync cannot observe a local bookmark before the remote update has been attempted.

Remote failures preserve the existing optimistic local-first policy: the local state is retained and
the failure is logged/reported. There is intentionally no unique `browseId` schema migration; runtime
serialization and the transactional re-query prevent new duplicates without making assumptions
about historical duplicate rows.
