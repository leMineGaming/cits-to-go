# Implementation status — C-ITS Drive Assist

Last updated: 2026-10-31

## Build and test status

| Check | Command | Result |
| --- | --- | --- |
| Kotlin compile (debug) | `./gradlew :app:compileDebugKotlin` | **PASS** |
| Unit tests | `./gradlew :app:testDebugUnitTest --rerun-tasks` | **PASS — 470 tests, 0 failures, 0 errors, 53 skipped** |
| Android lint | `./gradlew :app:lintDebug` | **PASS** |
| Debug APK | `./gradlew :app:assembleDebug` | **PASS** — `android-app/app/build/outputs/apk/debug/app-debug.apk` (37 MB) |
| Unsigned release APK | `./gradlew :app:assembleRelease` | **PASS** — `android-app/app/build/outputs/apk/release/app-release-unsigned.apk` (29 MB) |
| Instrumented tests | `./gradlew :app:connectedDebugAndroidTest` (API 36 emulator) | **PASS — 30 tests, 0 failed** |
| Android Auto | Desktop Head Unit / vehicle | **MANUAL REQUIRED** — see README test checklist. |

The 53 skipped unit tests are the pre-existing recorded-field-capture and Wireshark validation tests.
The PCAP fixtures are not present in this checkout and `tshark` is not installed. The test helpers now
report those as JUnit assumptions (skipped) instead of false failures; they still run when the
fixtures/tool are available. All new Drive Assist tests run.

Environment used: JDK 17.0.21, Android SDK platform 35, build-tools 35.0.0, Gradle 8.14.4, AGP 8.7.3,
Kotlin 2.3.21.

## Completed features

### Communication
- [x] TCP client with configurable host/port, connect timeout, reconnect base delay and status.
- [x] Bounded exponential backoff with jitter, capped at 60 s.
- [x] Length-prefixed (4/2-byte BE), COBS and newline-hex framing.
- [x] Streaming reassembly; oversized/malformed frames rejected without dropping the stream.
- [x] Foreground service, notification, graceful stop, socket/scope cleanup.
- [x] Network availability callback and reconnect-on-available.
- [x] Connection test and diagnostics counters/log.
- [x] Configurable location interval/accuracy; location permission handling.

### C-ITS messages
- [x] Reused existing MAPEM/SPATEM/DENM UPER decoders (no rewrite).
- [x] MAPEM intersection and per-lane speed limits decoded; per-lane limit takes precedence in GLOSA.
- [x] DENM event speed and heading retained.
- [x] DENM à-la-carte root fields normalised (lane position, impact reduction, roadworks, stationary vehicle, temperature, positioning solution).
- [x] Normalised `DriveSnapshot`, `SignalSnapshot`, `SafetyEvent` models.
- [x] Message freshness, decode counters, malformed/unsupported classification.
- [x] Simulation source with MAPEM, SPATEM and DENM (clearly labelled; never live).

### Positioning and GLOSA
- [x] MAPEM coordinate conversion (reference point + east/north cm).
- [x] Lane polyline projection, lateral offset and heading matching.
- [x] Stop-line/past-stop-line detection.
- [x] Lane connection signal-group resolution with confidence scoring.
- [x] GLOSA green/red feasibility, speed caps, comfort limits, arrival window, confidence.
- [x] Stale-data, low-confidence, no-timing and impossible-arrival handling.
- [x] No numeric recommendation when GLOSA cannot be safe.

### Safety alerts
- [x] ActionID identity, repetition/update dedup.
- [x] Cancellation, negation and expiry; termination history.
- [x] Cause/subcause classification (ETSI CDD), three priorities.
- [x] Distance, direction, relevance, age, validity, quality, confidence.
- [x] Category/priority filtering and profile relevance distances.
- [x] Notification cooldown; configurable repeat reminders.
- [x] Durable SQLite history.
- [x] Audio tones per priority, audio focus, volume, quiet mode, TTS.

### Dashboard and configuration
- [x] Drive dashboard with connection, speed, position, junction, signal, GLOSA, alerts, freshness.
- [x] Traffic-light screen with light visualization, timings, MAPEM lanes and GLOSA.
- [x] Safety screen with active/history tabs, filters and details.
- [x] MAPEM debug screen with lane canvas, stop lines, connections and signal groups.
- [x] Raw diagnostics screen and export.
- [x] Connection settings with test and diagnostics.
- [x] Driver profiles: create/edit/duplicate/select/delete, independent persistence.
- [x] Versioned settings/profile JSON with migration defaults and corrupt-file fallback.
- [x] Config export/import.
- [x] Onboarding with disclaimer, connection test, permissions, profile selection and service start.
- [x] Light/dark themes, large-text/keep-screen-on/units profile preferences.

### Android Auto
- [x] Official Car App Library 1.7.0 dependency.
- [x] `CarAppService`, session, screen, manifest service/category/metadata.
- [x] NavigationTemplate while driving, PaneTemplate status, MessageTemplate critical hazard.
- [x] Car notification extension for alerts.
- [x] Auto-refresh of the car screen while started.
- [x] Phone dashboard independent of Android Auto.

### Engineering and delivery
- [x] Version bump to 2.63.0 / versionCode 126.
- [x] CI workflow (tests, lint, debug/release builds, artifacts, reports).
- [x] Release workflow (tag/manual, tests, optional signing, AAB, notes, SHA256SUMS).
- [x] README rewritten for the complete product.
- [x] `docs/DRIVE_ASSIST.md` decoder/ASN.1/architecture notes.
- [x] Upstream attribution and licence preserved.

## Decisions made

- **Keep the existing decoders.** MAPEM/SPATEM/DENM parsing is reused and extended; the drive layer
  normalises the results rather than reimplementing ASN.1.
- **SharedPreferences + JSON instead of Room for profiles/settings.** Versioned documents with
  tolerant decoding avoid silently overwriting user settings. SQLite is used only for append-only
  event history, avoiding a KSP/Room toolchain increase for one table.
- **Application-scoped `DriveEngine`.** One engine is shared by the service, phone UI and Android Auto;
  the service owns sockets/location, the engine owns domain state, and the UI is stateless.
- **Conservative GLOSA.** `minEndTime` + safety margin, comfort limits, stale/confidence gates, and
  suppression for critical hazards. Never recommends speeding.
- **TCP framing is explicit.** Hardware protocols vary; the user selects length-prefix/COBS/hex rather
  than the app guessing and mis-framing. Default is 4-byte big-endian length prefix.
- **Navigation category with honest scope.** The app uses NavigationTemplate for glanceable driving
  status but explicitly does not claim turn-by-turn routing or a head-unit map.
- **Test fixtures.** Missing PCAP/tshark content is reported as skipped, not silently passed or failed.
- **Lint workaround.** Three Compose runtime detectors (`FlowOperatorInvokedInComposition`,
  `RememberInComposition`, `FrequentlyChangingValue`, plus `UnrememberedState`) crash inside the
  Kotlin 2.3 analysis API in this toolchain. They are disabled; all other lint checks run and pass.
  The crash is a detector/toolchain incompatibility, not an app diagnostic.

## Known limitations

- Android Auto must be validated on the DHU or a vehicle; head-unit host policy may refuse sideloaded
  navigation-category apps.
- DENM future/unknown à-la-carte extension additions are not interpreted; the standard root fields
  are normalised and a partial-decode marker is retained if a future extension cannot be read.
- MAPEM lane speed limits are used from node attributes; if a receiver omits them, the intersection
  limit or the profile/default limit applies.
- Hardware-provided positioning is not wired to a specific receiver protocol; the engine accepts
  position updates from any source.
- The TCP protocol is not standardised; proprietary frame headers need a decoder adapter.
- Field-capture/tshark tests are skipped in this environment.

## Next steps

1. Re-run `connectedDebugAndroidTest` on the target API 26+ device/emulator (API 36 emulator run passed).
2. Run the Android Auto DHU checklist with a live or simulated feed.
3. Validate against the user's actual receiver framing and capture a fixture for CI.
4. Optionally add hardware-position ingestion; DENM à-la-carte root normalisation is complete.
5. If distributing publicly: category/host review, privacy review and legal review.
