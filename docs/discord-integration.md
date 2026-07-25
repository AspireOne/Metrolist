# Discord Rich Presence

Everything lives in `app/src/main/kotlin/com/metrolist/music/discord/`, driven by
`playback/MusicService.kt`. The filenames are self-explanatory; what follows is only the part
you cannot get by reading them.

## What it actually is

There is no Discord desktop app to talk to on Android, so this is **not** the usual local-IPC
Rich Presence integration. The app logs in as *your Discord user* via OAuth2 and speaks the raw
Discord **gateway WebSocket** protocol itself — IDENTIFY, RESUME, heartbeats — pushing presence
as op 3 payloads. That is why there is a hand-rolled gateway client and reconnect state machine
here at all, and why the failure modes are network-protocol failure modes rather than "the SDK
returned an error".

Two pieces exist for non-obvious reasons:

- **`DiscordExternalAssets`** — Discord will not render an arbitrary image URL in a presence.
  Album art has to be run through the `external-assets` REST endpoint first, which returns an
  `mp:external/...` reference that the presence payload can use. Resolution is a network round
  trip, so it happens *after* the text-only presence is already sent, and the result is a second
  send. If it fails you silently get text-only presence, which is the usual reason artwork is
  missing.
- **`DiscordSuperProperties`** — fabricates an `X-Super-Properties` header so requests look like
  a real Discord client. Present because the endpoints above expect it, not for any app feature.

## Connection lifecycle invariants

Rewritten to fix a permanent deadlock and a token feedback loop (upstream PR #4164). Each rule
encodes a bug that was diagnosable only from on-device thread dumps and logcat — not from reading
the code, and not from the test suite. Breaking one reintroduces a failure that is hard to
reproduce and effectively invisible.

- **One owner.** The epoch coordinator in `DiscordRpcManager` is the only thing that opens
  connections: a single worker coroutine drains a `ConnectionIntent` queue, and bumping
  `connectionEpoch` invalidates in-flight work. Do not add a second path that calls
  `gateway.connect()`.

- **Never hold a lock across a suspending or network call.** The old design awaited the WebSocket
  handshake inside `reconnectMutex.withLock`. A superseded handshake left its `CompletableDeferred`
  uncompleted, so the mutex was held forever and every later reconnect queued behind it silently —
  recoverable only by killing the process. `synchronized(coordinatorLock)` blocks must stay
  non-suspending: capture state, exit the lock, then do I/O.

- **Never reconnect in reaction to `accessTokenFlow`.** Every write to it already comes from a path
  that establishes the connection itself. Reacting with another reconnect echoed a stale captured
  token back over a newer one, tearing down working connections and re-IDENTIFYing with a dead
  token in a self-sustaining loop (`4004` alternating with self-inflicted `4000`). The token
  collector in `MusicService` may initialize; it must not connect.

- **Gateway sends are connection-scoped, except presence.** `identify` / `resume` / `heartbeat`
  take a `connectionId` and throw `GatewaySupersededException` once that connection is stale.
  `presenceUpdate` deliberately is not scoped, and relies on the `_ready` guard instead:
  `beginEstablish()` clears `_ready` under `coordinatorLock` **before** any close/connect, and the
  presence senders are non-suspending from that check through to the send. Keep both halves of
  that arrangement, or scope presence too.

- **The retry ladder self-arms.** Exhausting `MAX_RECONNECT_ATTEMPTS` is not terminal: an
  `EnsureConnected` intent arriving from outside a retry resets the attempt counter, and the 30s
  `syncDiscordState` poll supplies exactly that. Presence recovering by itself within ~30s is by
  design — don't "fix" it with another retry mechanism.

- **Gate connecting, not just sending.** Every presence-sending path is guarded by the enable
  preference, so a connection opened while RPC is off broadcasts nothing — but it is still a live
  authenticated socket with heartbeats, costing battery. Startup init is gated on the persisted
  preference for that reason.

- **Don't run a liveness check before the first ping.** The heartbeat loop waits one interval and
  then checks for an ACK. On the first tick nothing has been sent, so there is no liveness
  information to act on. Comparing timestamps captured either side of a coroutine `start()` looks
  correct and misfires whenever a millisecond boundary falls between them.

## Testing caveats

Tests are JUnit4 + Robolectric under `app/src/test/kotlin/.../discord/`. `DiscordGateway` accepts
an optional `WebSocket.Factory` so a fake socket can be injected.

Coverage is uneven, and a green run means less here than it looks. The gateway callbacks, intent
merging and backoff helpers are covered; the manager's epoch coordinator — the highest-risk part
of the rewrite — is not, because `DiscordRpcManager` is an `object` with hardwired dependencies.
Covering it means making the gateway, auth and scheduling injectable.

These tests also run on `Dispatchers.Unconfined`, which hides dispatch-ordering bugs outright
(see the AGENTS.md gotcha). The heartbeat bug above passed the suite for precisely that reason
while misfiring in production.
