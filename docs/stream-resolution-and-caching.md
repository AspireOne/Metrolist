# Stream resolution and the player cache

Playback reads go through `MusicService.createDataSourceFactory()`: a `ResolvingDataSource` wrapping
nested `CacheDataSource`s — download cache over player cache over OkHttp. The resolver runs on the
ExoPlayer loading thread for every `DataSpec` open, decides whether the read can be served from disk,
and otherwise resolves a stream URL from YouTube before the read can start.

## The URI in a MediaItem is not a URL

A `MediaItem` built by `Song.toMediaItem` carries the **videoId** as its URI, not a googlevideo URL.
The resolver is the only thing that ever turns it into one. Two consequences follow, and both are
easy to forget:

- A spec returned unmodified is only safe if the cache can satisfy the entire read. There is no
  usable upstream to fall back to.
- Stream URLs expire. `songUrlCache` holds them with an expiry derived from
  `streamExpiresInSeconds`, and an entry past its expiry must not be used.

## Partially cached songs pay a full resolve

The cache checks ask `isCached(mediaId, position, requiredLength)` where `requiredLength` is the
**entire remainder** of the song — `contentLength - position` whenever the spec length is unbounded,
which is the normal case for opening a song or seeking. A song that was never played through to the
end therefore misses the cache completely and pays a full stream resolve before the first sample,
despite most of its audio already being on disk. Measured on device: roughly 950 ms warm, several
seconds cold.

This is a known, live inefficiency. It is not a bug in the predicate — the predicate is asking the
only question it can safely act on.

## Do not bound the spec to the cached run

The obvious remedy is to serve the contiguous cached run and stop at its edge:
`getCachedLength(...)`, then hand back a spec bounded with `setLength(cachedRun)`, expecting
ExoPlayer to reopen at the edge and resolve there — behind the buffer instead of in front of it.

**This does not work, and it fails silently.** `ProgressiveMediaPeriod` cannot distinguish a bounded
read ending from the stream ending: the extractor returns `RESULT_END_OF_INPUT` in both cases, the
loadable is marked complete, and the period never reopens. Playback then keeps its clock running
against a buffer that will never be refilled.

The symptom is a song dying mid-track. Position keeps advancing, the UI looks like it is playing, and
there is no rebuffer, no error, and no log line anywhere on the player path — the decoder simply stops
consuming and the `AudioTrack` runs dry a few seconds later. Pause and resume do not recover it,
because nothing is waiting to load; only tearing down the `MediaPeriod` does, which is why skipping to
another song and back appears to fix it. It reproduces only on songs holding a partial cached run, so
it presents as an intermittent fault every dozen-or-so tracks rather than an obvious regression.

This was shipped and reverted. Do not reintroduce it in any form that relies on a bounded read being
interpreted as anything other than end of stream.

## The shape a fix has to take

A working optimisation must put a **real, unexpired URL** into the spec before the read starts, so an
upstream exists at the cache edge. The cache then serves what it has and continues over the network
without a gap, which is the behaviour the bounded-spec approach was trying to fake.

That means resolving off the critical path — speculatively, while the previous song is still playing
or when the item is queued — and populating `songUrlCache`. A partially cached song then hits the
`songUrlCache` branch of the resolver and never reaches the blocking resolve at all. Nothing about
the cache predicates needs to change.

## Other resolver constraints

- The resolver blocks the loading thread. Everything it does is on the critical path for the first
  sample, including a Room query per open. Keep work out of it rather than adding to it.
- `DownloadUtil` runs a second, parallel resolver for downloads. Changes to resolution semantics
  usually need to be mirrored there.
