# AGENTS.md

Guidance for OpenCode sessions working in the Metrolist repo.

## Project status

Metrolist is a YouTube Music client for Android, closely following Material Design 3.

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
- `app/src/main/assets/player_configs.json` and `player_dates.json` are **auto-synced twice daily** from `ZemerTeam/zemer-cipher` by `.github/workflows/sync-player-configs.yml` and committed straight to `main`. Don't hand-edit — your changes will be overwritten.
- Generated proto sources are gitignored; never edit `app/src/main/java/com/metrolist/music/listentogether/proto/*` by hand. Edit the `.proto` in the `metroproto` submodule.
- All library modules already set `isCoreLibraryDesugaringEnabled = true` and target Java 21; new modules should match.

## Commit conventions

- Use conventional commits with an optional scope, per repo history (e.g. `fix(sync): resolve playlist duplication`, `chore: update player configs from upstream`). Provide a body explaining root cause and fix. Only stage your own changes.
- Commit with CI SKIP, by default. Changes should trigger CI only if the user explicitly wants to or if it's justified.
