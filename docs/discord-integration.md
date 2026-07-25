# Discord Rich Presence

How Metrolist pushes "now playing" to Discord, and the invariants of the connection
lifecycle. Read this before touching anything under `app/src/main/kotlin/com/metrolist/music/discord/`.

There is no Discord desktop app to talk to on Android, so this does **not** use local IPC.
The app logs in as *your Discord user* over OAuth2 and speaks the raw Discord **gateway
WebSocket** protocol itself, sending presence updates as op 3 payloads.

## File map

| File | Role |
|---|---|
| `DiscordAuth.kt` | OAuth2 authorization-code + PKCE flow, token exchange and refresh |
| `DiscordOAuthActivity.kt` | Catches the `metrolistdiscord://oauth2/callback` redirect |
| `DiscordTokenStore.kt` | Token persistence in `SharedPreferences`, AES-encrypted via Android Keystore |
| `DiscordGateway.kt` | Raw gateway WebSocket client: IDENTIFY / RESUME / HEARTBEAT, connection state |
| `DiscordReconnectStrategy.kt` | Maps close codes to an action (Resume / ReIdentify / RefreshAndReIdentify / SurfaceFatal) and owns reconnect backoff |
| `DiscordRpcManager.kt` | Orchestrator: auth state, connection coordinator, presence dedup/debounce |
| `DiscordPresence.kt` | Builds the op 3 `PRESENCE_UPDATE` JSON |
| `DiscordActivityBuilder.kt` | Builds an activity from a `Song` |
| `DiscordTemplateRenderer.kt` | Expands user templates like `{song.name}`, `{artist.name}` |
| `DiscordExternalAssets.kt` | Resolves arbitrary image URLs into Discord `mp:external/...` asset refs |
| `DiscordSuperProperties.kt` | Fakes the `X-Super-Properties` client header |
| `DiscordDefaults.kt` | Default templates, OAuth endpoints, scopes |
| `playback/MusicService.kt` | Drives it: song/playback changes, enable toggle, 30s reconnect poll |
| `ui/screens/settings/integrations/DiscordSettings.kt` | Settings UI (login/logout, toggles, templates) |

`DISCORD_APP_ID` is a build config value and **must** be supplied via `local.properties` or the
environment — the build fails loudly rather than falling back (see AGENTS.md).

## Connection lifecycle invariants

The lifecycle was rewritten to fix a permanent deadlock and a token feedback loop (upstream
PR #4164). Each rule below encodes a bug that was only diagnosable from on-device thread dumps
and logcat, not from reading the code. Breaking one reintroduces a failure that is hard to
reproduce and effectively invisible in tests.

- **One owner.** `DiscordRpcManager`'s epoch coordinator is the only thing that opens
  connections: a single worker coroutine drains a `ConnectionIntent` queue, and bumping
  `connectionEpoch` invalidates any in-flight work. Do not add a second path that calls
  `gateway.connect()`.

- **Never hold a lock across a suspending or network call.** The previous design awaited the
  WebSocket handshake inside `reconnectMutex.withLock`. A superseded handshake left its
  `CompletableDeferred` uncompleted, so the mutex was held forever and every later reconnect
  queued behind it silently — recoverable only by killing the process. `synchronized(coordinatorLock)`
  blocks must stay non-suspending: capture state, exit the lock, then do I/O.

- **Never reconnect in reaction to `accessTokenFlow`.** Every write to it already comes from a
  path that establishes the connection itself. Reacting with another reconnect echoed a stale
  captured token back over a newer one, tearing down working connections and re-IDENTIFYing with
  a dead token in a self-sustaining loop (`4004` alternating with self-inflicted `4000`).
  `MusicService`'s token collector may initialize; it must not connect.

- **Gateway sends are connection-scoped.** `identify` / `resume` / `heartbeat` take a
  `connectionId` and throw `GatewaySupersededException` if that connection is no longer active.
  `presenceUpdate` is deliberately *not* scoped and relies on the `_ready` guard instead:
  `beginEstablish()` clears `_ready` under `coordinatorLock` **before** any close/connect, and
  the presence senders are non-suspending from that check through to the send. Keep both halves
  of that arrangement, or scope presence too.

- **The retry ladder self-arms.** Exhausting `MAX_RECONNECT_ATTEMPTS` is not terminal: an
  `EnsureConnected` intent arriving from outside a retry resets `reconnectAttempts` and
  `retryExhausted`, and `syncDiscordState`'s 30s poll supplies exactly that. Presence recovering
  on its own within ~30s is by design — don't "fix" it with another retry mechanism.

- **Gate connecting, not just sending.** Every presence-sending path is guarded by
  `discordRpcEnabled`, so a connection opened while RPC is off broadcasts nothing — but it is
  still a live authenticated socket with heartbeats, costing battery. Startup init is gated on
  the persisted preference for this reason.

- **Don't run a liveness check before the first ping.** The heartbeat loop waits one interval,
  then checks for an ACK. On the first tick no heartbeat has been sent, so there is no liveness
  information to act on; `lastSentAt` stays `0` until the first send and the check is skipped
  while it is. Comparing timestamps captured either side of a coroutine `start()` looks correct
  and misfires whenever a millisecond boundary falls between them.

## Testing notes

Tests live in `app/src/test/kotlin/com/metrolist/music/discord/` (JUnit4 + Robolectric,
`@Config(sdk = [33])`). `DiscordGateway` takes an optional `webSocketFactory: WebSocket.Factory?`
so tests can inject a fake socket without hitting the network.

Coverage is uneven and worth knowing before trusting a green run: the gateway callbacks, intent
merging and backoff helpers are covered, but the manager's epoch coordinator — the highest-risk
part — is not, because `DiscordRpcManager` is an `object` with hardwired dependencies. Testing it
would require making the gateway, auth and scheduling injectable.

Note also that these tests use `Dispatchers.Unconfined`, which hides dispatch-ordering bugs
entirely (see the AGENTS.md gotcha). The heartbeat bug above passed the suite for exactly that
reason while misfiring in production.
