# AGENTS.md

Guidance for AI agent sessions working in this repo.

## Project status

Metrolist is a YouTube Music client for Android, closely following Material Design 3.

## THIS IS A FORK — read this first

This checkout is **`AspireOne/Metrolist`**, a personal long-term fork of upstream
**`MetrolistGroup/Metrolist`**. It is not the official project. It exists so its owner can carry
personal patches while still receiving upstream releases automatically, and it publishes its own
signed APKs that the in-app updater pulls from this fork rather than upstream.

| | |
|---|---|
| `applicationId` | `com.metrolist.music.aspireone` (coexists with the official app) |
| App name | `Metrolist++` |
| Release tags | `fork-v<version>` — prefixed so they never collide with upstream's `v<version>` |
| Release *title* | bare numeric (`13.6.1.3`) — this is what the in-app updater compares |
| Updater target | `BuildConfig.UPDATE_REPO`, set to this fork |
| Variant built | `foss` only |

Consequences that catch people out:

- Anything pointing at `MetrolistGroup/Metrolist` in *runtime* code is suspect and probably a bug.
  Credit links, the LICENSE link and User-Agent strings legitimately still reference upstream.
- Never publish a release under a bare `v<version>` tag. That collides with upstream's tags and
  breaks `git fetch --tags upstream` on the next mirror run.
- The `metrolist.cc` App Link (`autoVerify`) cannot verify for this fork — we own neither the
  domain nor its `assetlinks.json`. Cosmetic only; do not try to "fix" it.

## Deeper docs — `docs/`

`docs/` holds per-feature and per-subsystem documentation: architecture, hard-won gotchas, and
invariants that are not obvious from the code. This file stays general; `docs/` goes deep.

**Read the relevant document(s) before working in an area they cover** — they exist because the
context in them was expensive to rediscover. Skip the ones unrelated to the task at hand.

| Document | Covers |
|---|---|
| `docs/discord-integration.md` | Discord Rich Presence: OAuth, the gateway client, and the connection-lifecycle invariants |

When you solve something in an area that cost real effort to work out — a non-obvious constraint,
a bug whose cause was hard to see, a design that looks wrong but isn't — add or update a document
here rather than letting the next agent rediscover it.

## Signing keys

Neither key is in git. Both live outside the repo:

| Key | Path | Purpose |
|---|---|---|
| Release | `C:\Users\matej\.android\release-aspireone.keystore` | the fork's permanent identity |
| Debug | `C:\Users\matej\.android\debug.keystore` | local builds; public well-known password |

**Alias, password, fingerprint and backup instructions are in
`release-aspireone.keystore.README.txt` beside the keystore.** Do not copy the password into this
file or anywhere else in the repo — it is a public repository.

The release key is also held as the `KEYSTORE` GitHub secret (base64 of the file). Its SHA-256 is
pinned in the `SIGNING_CERT_SHA256` repo *variable*, and the workflow asserts every built APK
against it — so a signing-identity regression fails the build rather than shipping.

Losing this key means no future release can update an installed app in place, only uninstall +
reinstall. If asked to regenerate it, confirm the owner genuinely wants a new, unrelated identity.

## Release pipeline

`.github/workflows/fork-release.yml` ("Sync, Build, and Release") is the only release path. **All
upstream workflows are disabled** — do not re-enable them; they build the wrong package under the
wrong identity. `.github/workflows/keepalive.yml` stops GitHub auto-disabling the schedule after
60 days of inactivity.

Two modes in one workflow:

- **Mirror** (nightly cron `0 1 * * *`, or `workflow_dispatch`) — merge upstream's latest release
  into `main`, build, publish at upstream's version.
- **Personal** (push to `main` with `[release]` in the head commit message) — publish an interim
  release, versioned with a 4th component (`13.6.1` → `13.6.1.3`). Ordinary pushes accumulate
  without publishing; a later `[release]` commit includes all accumulated changes.

Facts worth knowing before changing anything:

- `versionCode` is `git rev-list --count HEAD` — monotonic by construction.
- Ordering is merge → tests → build → lint → sign → verify → **push `main`** → release. Nothing
  reaches `main` or the phone until every gate passes; a failed run costs one wasted CI run.
- The unit-test gate is **differential**: upstream ships permanently-failing tests, so a merged
  failure only blocks if the same test passes on the pristine upstream baseline. It fails closed
  when reports cannot be parsed (i.e. a compile or Gradle-configuration error).
- Mirror pushes use `GITHUB_TOKEN`, whose pushes do not trigger workflows — so a mirror run cannot
  trigger a spurious personal release.
- Use an empty commit to publish accumulated changes without another source edit:
  `git commit --allow-empty -m "chore: release fork [release]"`.
- `[skip ci]` suppresses all GitHub Actions and is not needed merely to avoid a release.

Secrets: `KEYSTORE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `DISCORD_APP_ID`,
`LASTFM_API_KEY`, `LASTFM_SECRET`. Variable: `SIGNING_CERT_SHA256`.

## Resolving upstream merge conflicts

Conflicts surface as a **failed nightly mirror run** (the workflow aborts the merge and leaves
`main` untouched). Resolution is manual:

```bash
git fetch --tags upstream
git merge <upstream-tag>      # resolve, then commit
git push origin main
```

Push the resolved merge without `[release]`. The next scheduled run—or a manual workflow
dispatch—sees the merge already in history (`git merge-base --is-ancestor`) and publishes it at
the bare upstream version. Adding `[release]` instead intentionally makes it a personal release.

### The governing rule

**Take upstream's improvement; preserve our intent.** For each conflicting hunk, work out what
upstream was trying to achieve and what our side was trying to achieve, then adopt upstream's change
*in a way that keeps ours working*. The usual correct shape is upstream's new code with our
modification re-expressed on top of it.

Choosing "ours" wholesale discards upstream's fix. Choosing "theirs" wholesale is worse: it looks
clean, compiles, passes tests, signs, installs — and silently reverts behaviour the owner
deliberately added. **No automated gate in this repo can detect that.** It is the one failure mode
you must actively guard against by reading.

Only drop one of our deviations when upstream genuinely made it obsolete — it implemented the same
thing itself, or removed the feature our patch modified. Say so explicitly when you do.

### When to stop and ask

If upstream fixed something we had already fixed differently, and upstream's version also improves
adjacent behaviour but carries a regression, there is no safe mechanical answer. Keeping ours loses
the improvement; taking theirs inherits the bug; cherry-picking means performing surgery on
upstream's code on the owner's behalf.

**Escalate to the owner.** Do not quietly pick one. A blocked mirror costs one nightly run; a wrong
resolution can go unnoticed for weeks. This class of conflict is exactly why automated resolution
was designed, built, and then deliberately parked — see the branch
`feat/ai-merge-conflict-resolver` and its commit message.

### Verifying a resolution

Check that the fork's deviations survived, by comparing them before and after rather than against a
memorised list (the list grows as patches are added):

```bash
BASE=$(git merge-base ORIG_HEAD <upstream-tag>)
git diff "$BASE" ORIG_HEAD      # what made the fork the fork, before
git diff <upstream-tag>         # the same, after resolution
```

Every deviation in the first should still be present, relocated, or explicitly obsoleted in the
second. Then confirm it builds: `./gradlew :app:compileFossReleaseKotlin`.

### Current fork deviations

Small today, but **verify against the diff rather than trusting this list** — it will drift.

- `app/src/main/kotlin/com/metrolist/music/utils/Updater.kt` — `GITHUB_API_BASE` reads
  `BuildConfig.UPDATE_REPO` instead of hardcoding upstream.
- `app/build.gradle.kts` — adds `UPDATE_REPO` `buildConfigField`, `METROLIST_VERSION_NAME` /
  `METROLIST_VERSION_CODE` overrides, and makes a missing `DISCORD_APP_ID` **throw** instead of
  falling back to upstream's app ID. That throw is at Gradle *configuration* time, so every
  `gradlew` invocation needs the variable — including test-only ones.
- `.gitattributes` — `merge=ours` on `.github/workflows/**`.
- Fork-only files with no upstream counterpart: `fork-release.yml`, `keepalive.yml`,
  `.github/scripts/failed_tests.py`, this file.

## Toolchain

- **JDK 21** required (Temurin). Kotlin 2.4.10, AGP 9.3.0, KSP.
- `compileSdk = 37`, `minSdk = 26`, `targetSdk = 36`. JVM toolchain pinned to 21.
- No ktlint / detekt / spotless. Lint is the only static check and is non-blocking: `abortOnError = false`, `warningsAsErrors = false` (`app/build.gradle.kts:231-236`).
- The active lint config is `app/lint.xml` (loaded via `lintConfig = file("lint.xml")` in the android block): ignores `MissingTranslation`, escalates `MissingQuantity` to error for `cs`/`lt`/`sk` locales. The repo-root `lint.xml` (which holds the media3 `UnstableApi` ignore) is **not** wired into the Gradle lint task — edit `app/lint.xml` to change lint behavior.
- `org.gradle.unsafe.configuration-cache=true` — be aware config cache is on; some plugins break.
- `org.gradle.caching=false` intentionally (works around NewPipeExtractor SNAPSHOT timeouts).

## First-time setup

Before building, **initialize the `metroproto` submodule** or proto generation silently no-ops:

```bash
git submodule update --init --recursive
```

Protobuf codegen for the Listen Together feature:
- `metroproto/listentogether.proto` is the source; generated Kotlin/Java land in `app/src/main/java/com/metrolist/music/listentogether/proto/` (gitignored).
- The Gradle `generateProto` task runs automatically before `compile*` / `assemble*` and **downloads `protoc` to `app/build/` if missing** (`app/build.gradle.kts:40-304`). You rarely need `app/generate_proto.sh` manually.
- If `metroproto/listentogether.proto` is absent (submodule not initialized), the build warns and skips — code that references generated proto types will then fail to compile.

Debug signing: `app/persistent-debug.keystore` (gitignored). If absent, create it per `development_guide.md`:

```bash
keytool -genkeypair -v -keystore app/persistent-debug.keystore -storepass android \
  -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

## Build / verify commands

There are three product flavors on the `variant` dimension; **`foss` is the default**:

| Flavor | Cast | Updater | Notes |
|--------|------|---------|-------|
| `foss`  | no  | yes | default; what most local builds use |
| `gms`   | yes | yes | Google Cast deps (`gmsImplementation` only) |
| `izzy`  | no  | no  | the only F-Droid-compliant build |

```bash
./gradlew :app:assembleFossDebug                          # local debug APK
./gradlew :app:assembleFossRelease                        # release (R8 + resource shrink, see app/proguard-rules.pro)
./gradlew :app:lintFossDebug                              # lint (non-blocking but run it)
./gradlew :app:testFossDebugUnitTest                      # unit tests ( flavor-specific task!)
./gradlew :app:testFossDebugUnitTest --tests "com.metrolist.music.discord.DiscordTokenStoreTest"  # one test class
```

Tests are **JUnit4 + Robolectric** (`@Config(sdk = [33])`), unit-test JVM only — no device/emulator and no instrumentation tests in source despite the `AndroidJUnitRunner` being configured. Tests live under `app/src/test/kotlin/...`.

## Module layout

Root `settings.gradle.kts` includes the `:app` plus seven Android-library modules. Each library is a Ktor-based API client:

- `:innertube` — YouTube Music API (the core data source).
- `:kugou`, `:lrclib`, `:betterlyrics` — lyrics providers.
- `:lastfm` — Last.fm scrobble API.
- `:shazamkit` — music recognition.
- `:paxsenix` — Apple Music lyrics/search API client (reuses `betterlyrics`'s `TTMLParser`).
- `metroproto/` — **git submodule** (not a Gradle module); holds `listentogether.proto`.

`:app` (namespace `com.metrolist.music`) is the real application. Key entrypoints:
- `App.kt` — `@HiltAndroidApp` `Application`, Coil `ImageLoader` factory, startup wiring.
- `MainActivity.kt` — single Compose activity.
- `playback/MusicService.kt` — Media3 `MediaLibraryService` (foreground playback).
- `di/` — Hilt modules; `di/Qualifiers.kt` defines `@ApplicationScope`.
- `com/dpi/*` — `ContentProvider`s used to hook early init, **not** for content.

## Build flags / environment

`app/build.gradle.kts` reads optional env vars (and `local.properties` for LastFM):

- `METROLIST_APPLICATION_ID`, `METROLIST_APP_NAME` — override application ID / app name (CI uses these for per-PR builds).
- `METROLIST_DEBUG_KEYSTORE_PATH`, `METROLIST_DEBUG_KEYSTORE_PASSWORD`, `METROLIST_DEBUG_KEY_ALIAS`, `METROLIST_DEBUG_KEY_PASSWORD` — override debug signing.
- `LASTFM_API_KEY`, `LASTFM_SECRET` — inject via env or `local.properties` (optional; empty by default, only needed to exercise LastFM features). Release signing (`STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) is CI-only.

## Gotchas

- `org.json:json` is **globally excluded** (`app/build.gradle.kts:331-333`). The standalone artefact bundles an Apache Harmony `JSONArray` with an internal `myArrayList` field absent from Android's platform `org.json`; R8 inlines against it and crashes with `NoSuchFieldError` at runtime. Don't re-add it.
- `app/src/main/assets/player_configs.json` and `player_dates.json` are auto-synced from `ZemerTeam/zemer-cipher` by `.github/workflows/sync-player-configs.yml` upstream — but **that workflow remains disabled with the other upstream workflows in this fork**. These files therefore only update when a mirror merge brings upstream's committed version across. Don't hand-edit them.
- `dataStore.get(key)` / `dataStore[key]` do `runBlocking(Dispatchers.IO)` internally — every call is a blocking disk read on the calling thread. `MusicService.onCreate` therefore reads **all** startup preferences once into `startupPrefs` (`runBlocking { dataStore.data.first() }`) and everything after that point must read `startupPrefs!![Key] ?: default`. Adding a `dataStore.get()` back into `onCreate` silently re-introduces a main-thread disk read into the most ANR-sensitive method in the app; ~15 of them were deliberately consolidated away. Outside `onCreate` (in coroutines, callbacks, settings screens) `dataStore.get()` is fine.
- Unit tests drive coroutine scopes with `Dispatchers.Unconfined`, so a `launch` body starts **inline on the calling thread** instead of being dispatched. Any bug whose trigger is dispatch latency or coroutine start ordering is therefore invisible to the test suite even when the test looks like it covers the code. A real example: a heartbeat liveness check compared two `System.currentTimeMillis()` reads taken either side of a `start()`; under `Unconfined` they always landed in the same millisecond and passed, while production (`Dispatchers.IO`) crossed a millisecond boundary often enough to kill healthy connections. When reasoning about ordering, check what dispatcher production actually uses — do not infer safety from green tests.
- Generated proto sources are gitignored; never edit `app/src/main/java/com/metrolist/music/listentogether/proto/*` by hand. Edit the `.proto` in the `metroproto` submodule.
- All library modules already set `isCoreLibraryDesugaringEnabled = true` and target Java 21; new modules should match.

## Commit conventions

- Use conventional commits with an optional scope, per repo history (e.g. `fix(sync): resolve playlist duplication`, `chore: update player configs from upstream`). Provide a body explaining root cause and fix. Only stage your own changes.
- Do not add `[skip ci]` merely to suppress a release; ordinary pushes do not publish. Use
  `[release]` when the pushed head commit should publish, and reserve `[skip ci]` for the uncommon
  case where all enabled CI should be suppressed.
