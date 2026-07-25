# Codebase audit log

A working log for the audit-and-fix loop: the open task list, what was done
about each one, and what later audit rounds find. Round 1 was a full read of
every Kotlin source file (13.1k lines) plus `testDebugUnitTest` and
`lintDebug`.

## How this log is used

One task per iteration, in list order unless a task is blocked:

1. Pick the first task whose status is `TODO` (tiers are the priority order).
2. Mark it `WIP`, implement the fix, add or extend unit tests where the fix
   is testable.
3. Verify: `./gradlew :app:assembleDebug :app:testDebugUnitTest` must pass,
   plus `:app:compileReleaseKotlin` when the change is not test-only.
4. Mark it `DONE`, append a **Progress log** entry (what changed, what it
   fixed, anything the next task needs to know).
5. Commit the code, the tests and the log entry together, then push to
   `origin main`.

When every task is `DONE`, run the audit again from scratch, append the
findings as a new round, and keep going.

Status values: `TODO` · `WIP` · `DONE` · `WONTFIX` (with a reason).

Each task is identified by its commit *subject*, not a hash: the log entry
ships inside the commit it describes, so the hash doesn't exist yet when the
entry is written. `git log --oneline --grep=<subject>` resolves it. Nothing is
marked `DONE` before the work exists and the tests pass.

## Baseline at round 1

- 264 unit tests, 0 failures.
- `lintDebug` **fails**: 1 error (`MissingPermission`), 96 warnings.
- No CI. Release build unsigned, `isMinifyEnabled = false`.
- Local `main` 11 commits ahead of `origin/main` at the start of round 1.

---

## Tier 1 — breaks in normal use

### A1 · Leaving the app silences a running tour — `DONE`

`PlacesViewModel.onCleared()` calls `audioService.shutdown()`
(`PlacesViewModel.kt:325`), but `AudioService` is a `@Singleton`
(`AudioModule.kt:17`) shared with `TourModeService`. Pressing Back while
touring clears the ViewModel, shuts down the TTS engine, and every later
`speak()` emits `ERROR` immediately while the notification still claims the
tour is active. The `ERROR -> deliverNextContent()` branch
(`TourModeService.kt:730`) then drains the whole queue in one tight
recursion.

**Fix:** the ViewModel must not shut down a singleton it doesn't own. Remove
the `shutdown()` call; let the service (and process death) own the engine
lifecycle.

### A2 · Returning to the app shows the tour as off — `DONE`

`bindService` only runs inside `launchTourService()` (`MainActivity.kt:652`);
neither `onCreate` nor `onStart` re-binds. After a rotation, a process
restart, or the `activity?.recreate()` a settings save triggers
(`TourSettingsFragment.kt:389`), the service keeps running but the fact card
disappears and the FAB reads "start tour".

**Fix:** bind in `onStart` whenever the service is alive, and drive
`isTourModeActive` from the bound service's `serviceState` instead of a local
flag.

### A3 · "You have arrived" repeats every 5 seconds — `TODO`

The synthesized arrival instruction uses `maneuverPoint = newLocation`
(`NavigationServiceImpl.kt:328`), and the voice-prompt dedup key contains the
maneuver point (`MainActivity.kt:1264`). Every fix within 50 m of the
destination produces a new key, so the arrival prompt is spoken again and
again while parked.

**Fix:** use the route destination as the arrival maneuver point and latch
arrival so it is announced once per drive.

### A4 · A revived service becomes a zombie — `TODO`

`onStartCommand` returns `START_STICKY` (`TourModeService.kt:198`), so Android
restarts the service with a **null intent**: `when (intent?.action)` matches
nothing, but `startForeground` already ran. The result is a permanent "Tour
Mode Active" notification with no monitoring behind it.

**Fix:** treat a null intent as "resume the tour" (settings are persisted
anyway), or return `START_NOT_STICKY`.

### A5 · The same story can be told twice — `TODO`

`ContentDeliveryQueue.offer` (`ContentDeliveryQueue.kt:20`) has no dedup, and
three paths queue content for one place: geofence `enter`, geofence `dwell`
30 s later, and `APPROACHING`/`ARRIVED` proximity alerts. The only guard is
`currentPoi?.id != poi.id` (`TourModeService.kt:498`, `:574`), which stops
repeats only while that POI is still current — once the guide moves on, a
re-alert queues the same place again. Repeated content is the top complaint
recorded in `tour_guide_research.md:37`.

**Fix:** dedup by `poiId` inside the queue, plus a spoken-this-session set so
a place can't be re-queued after it has been told.

### A6 · Proximity alerts lose all but one POI per fix — `TODO`

`processNewLocation` writes `proximityAlerts.value = alert` inside the
per-POI loop (`LocationAwarenessServiceImpl.kt:254`) and the listener sends
only the surviving value (`:119`). With two places in range the last one in
`ConcurrentHashMap` iteration order wins and the other is never alerted; and
because a `StateFlow` value is re-read after every fix, the last alert is
re-emitted forever. Narration survives only because the geofence path fires
per POI — which makes this whole subsystem dead weight.

**Fix:** emit each alert as it is produced (send inside the loop or use a
`Channel`) and stop re-reading retained state. Watch for the flip side: the
accidental conflation is currently the only thing preventing an alert per
fix, so per-POI rate limiting has to land in the same change.

---

## Tier 2 — concurrency and silent failure

### A7 · `narrationTimes` is unsynchronized shared state — `TODO`

Declared as a plain `mutableListOf` (`TourModeService.kt:130`), pruned with
`removeAt(0)` and appended in `generateAndQueueContent` (`:602`, `:633`) —
which runs concurrently from both the geofence and proximity launches on
`Dispatchers.Default` — and appended again by the way-of-life watcher
(`:827`), while `TourLogic.narrationAllowed` iterates it with `count {}`.
That is a live `ConcurrentModificationException` window; the surrounding
catch swallows it and silently drops a narration.

**Fix:** confine narration bookkeeping to one owner (a `Mutex`, or route all
delivery decisions through a single coroutine).

### A8 · `isDelivering` is checked and set non-atomically — `TODO`

`if (!audioService.isSpeaking() && !isDelivering) deliverNextContent()`
(`TourModeService.kt:637`) lets two concurrent coroutines both pass.
Separately, the notification's "Next" action (`:1107`) stops audio and starts
a new delivery while the interrupted chain is still unwinding, and that
chain's `else -> isDelivering = false` (`:733`) clears the flag underneath
the new delivery.

**Fix:** `AtomicBoolean.compareAndSet` as the gate, or serialize delivery
through one coroutine fed by a channel. Fix together with A7 — same problem,
and the per-tour state (`narrationTimes`, `narratedRegions`, `isDelivering`,
plus whatever A5 adds) wants one owner, not four locks.

### A9 · TTS initialization failure is discarded — `TODO`

`initialize()` returns `Boolean` and all three call sites ignore it
(`TourModeService.kt:239`, `:244`, `PlacesViewModel.kt:77`). A device without
the requested voice data takes the `LANG_NOT_SUPPORTED` path
(`AudioServiceImpl.kt:176`), which resumes `false`, never installs the
progress listener, and leaks the `TextToSpeech` instance. The user gets a
completely silent tour with no explanation.

**Fix:** fall back to the device default locale, surface the failure as a
tour-mode error (and an `ACTION_INSTALL_TTS_DATA` prompt), and shut down the
engine that failed to initialize.

### A10 · Way-of-life filler hammers Overpass on empty roads — `TODO`

When `nearbyCities` returns empty — rural highway, or a rate-limited or
failed request — `maybePlayWayOfLife` returns without stamping
`lastWayOfLifeAt` (`TourModeService.kt:795`), so the 30 s watcher POSTs to
the public Overpass instance again on every tick for the rest of the drive.
That is exactly the scenario the feature exists for. (Introduced by
`39d6d35`.) `NearbyCityApiService.nearbyCities` also catches only
`IOException`, so a truncated response throws `JSONException` at the caller.

**Fix:** stamp the cooldown on empty and failed lookups too, and back off
further after repeated failures.

---

## Tier 3 — performance

### A11 · Route math runs on the main thread every fix — `TODO`

`FrameworkLocationClient` delivers updates on `Looper.getMainLooper()`
(`FrameworkLocationClient.kt:66`), and `updateNextInstructionBasedOnRoute`
calls the O(N) `findClosestPointOnRoute` once per instruction
(`NavigationServiceImpl.kt:385`). A city route with ~2000 polyline points and
40 instructions is ~80k distance computations every 5 s on the UI thread,
competing with the map's camera animation.

**Fix:** precompute each instruction's route index once per route version and
move the per-fix update off the main looper.

### A12 · Every narration re-fetches a POI already in memory — `TODO`

`handleGeofenceEvent` and `deliverNextContent` both call
`placesRepository.getPlaceDetails(id)` (`TourModeService.kt:495`, `:661`),
which misses the DB for any not-yet-visited place and hits the network
(`PlacesRepositoryImpl.kt:128`) — while
`LocationAwarenessServiceImpl.monitoredPointsOfInterest` is holding that same
object. On the Google provider that is a billable Places call per geofence
crossing, and a network failure means no narration at all.

**Fix:** carry the POI through (or keep an in-memory id→POI map) instead of a
repository lookup.

### A13 · ETA is a 30 mph constant after the first calculation — `TODO`

The routing API's own duration is used once (`NavigationServiceImpl.kt:97`)
and then replaced by `remainingDistance / 13.4f` (`:286`, `:291`), so highway
ETAs read badly wrong.

**Fix:** scale the API duration by the remaining route fraction and re-use
the real duration on each recalculation.

---

## Tier 4 — data safety and completeness

### A14 · Schema changes will delete the user's journal — `TODO`

`fallbackToDestructiveMigration()` (`AppDatabase.kt:37`) plus
`android:allowBackup="true"` with no `dataExtractionRules`. The Tour Journal
is user-visible history — the one thing here that should not be disposable.

**Fix:** real migrations from v2 onward, and backup rules that either exclude
the DB or handle it deliberately.

### A15 · Nothing ever evicts the caches — `TODO`

`tour_content` and `points_of_interest` grow without bound; `TourContentDao`
has only `deleteAllContent()`, which nothing calls.

**Fix:** age- or size-based trim, plus a "clear cached stories" control in
settings. Must be migration-safe once A14 lands (no `DROP TABLE`).

### A16 · Dead code and dead dependencies — `TODO`

Unused source: `domain/usecase/` (all three files),
`domain/service/PlacesService.kt`, `domain/repository/ContentRepository.kt`,
`ExampleUnitTest`, `ExampleInstrumentedTest`. Unused libraries shipped in the
APK: `retrofit`, `converter-gson`, `logging-interceptor`,
`navigation-fragment-ktx`, `navigation-ui-ktx` (all HTTP is hand-rolled
OkHttp; Gson survives only through `LatLngConverter`). `darkModeEnabled` is
persisted and read but never applied and has no UI.

**Fix:** delete the dead source and dependencies; either wire up
`darkModeEnabled` or drop it.

### A17 · `geocodeAddress` is broken on Android 13+ and unused — `TODO`

The API 33 branch passes a callback and returns `result` synchronously, so it
is always null (`NavigationServiceImpl.kt:538`). Nothing calls it.

**Fix:** delete it, or make it correct with `suspendCancellableCoroutine`
before something depends on it.

### A18 · Release readiness — `TODO`

No `signingConfig`; `isMinifyEnabled = false`; `versionCode = 1` /
`versionName = "1.0"`; `lintDebug` fails on `MissingPermission`
(`FrameworkLocationClient.kt:74`); 96 lint warnings (26 `UseTomlInstead`, 24
`GradleDependency`, 8 `DefaultLocale`); no CI at all.

**Fix:** handle the lint error, add a GitHub Actions workflow running
`assembleDebug + testDebugUnitTest + lintDebug`, and set up release signing
and shrinking.

### A19 · The buggiest file has zero tests — `TODO`

`TourLogic`'s pure functions are thoroughly covered, but `TourModeService` —
1146 lines holding every issue in Tiers 1–2 — has no tests, and neither do
`AudioServiceImpl`'s state transitions beyond its two pure helpers.

**Fix:** extract the delivery state machine behind fakes (`ContentService`,
`AudioService`, `LocationAwarenessService`) so A5, A7, A8 and A10 are covered
by tests rather than reasoning.

### A20 · Smaller polish — `TODO`

- `_isNarrationPlaying` is not updated when audio focus pauses playback
  (`AudioServiceImpl.kt:102` vs `TourModeService.kt:88`), so the fact card's
  play/pause button lies.
- Notification channel names and descriptions are hardcoded English
  (`TourModeService.kt:930-980`) while the rest of the UI uses `strings.xml`.
- ETA uses a hardcoded 24-hour `SimpleDateFormat("HH:mm")`
  (`MainActivity.kt:1215`) instead of the locale's time format.
- A planned Take a Tour drive re-discovers stops from the route corridor
  rather than registering the stops it just curated, so a chosen stop can be
  crowded out by the `MAX_ROUTE_POIS = 60` cap.

---

## Progress log

Newest last. One entry per completed task: what changed, what it fixed, and
anything the next task should know.

### Round 1 opened — audit recorded

Full read of the codebase produced tasks A1–A20 above. Baseline captured:
264 tests green, `lintDebug` failing, no CI, 11 unpushed commits on `main`.
No code changes in this entry.

### A1 — "Keep the tour guide's voice alive when the app closes"

`PlacesViewModel` no longer overrides `onCleared` to call
`audioService.shutdown()`. The engine is a process-wide `@Singleton` shared
with the foreground service, so the ViewModel's death is not the engine's
death. The constraint now lives as a comment on the injected dependency
rather than on the deleted override — that is where someone reaching for
cleanup will read it, and it covers the fragments too, not just this one
call site. `TourModeService.stopTourMode()` was already calling `stop()`
rather than `shutdown()`, which is the right granularity: narration ends,
the engine stays usable for the next tour.

Also fixed the failure mode this bug exposed. `deliverNextContent` retried on
`ERROR` with no limit, so an engine that fails instantly (dead, or audio focus
denied by a phone call) drained the entire queue in one tight recursion and
lost every story in it. It now carries a `consecutiveErrors` count and gives
up after `TourLogic.MAX_CONSECUTIVE_DELIVERY_ERRORS` (3), leaving the rest
queued for the next trigger and clearing the fact card since nothing is being
spoken. A stale-skip deliberately does not count as a failure, and a success
resets the run.

Tests: 3 new cases in `TourLogicTest` for `shouldKeepDeliveringAfterError`
(267 total, 0 failures); debug and release variants both compile. The
one-line ViewModel change is not unit-testable without instrumentation — no
test was written for it, and the comment on the dependency is the regression
guard.

**For later tasks:** the recursion is still the delivery mechanism. A8
(serialize delivery) should turn it into a loop rather than adding a third
guard on top of `isDelivering` and the error count.

### A2 — "Reattach the running tour when the activity comes back"

The binding now follows the activity's lifecycle instead of the tour's:
`bindTourService()` in `onStart`, `unbindTourService()` in `onStop` (and
guarded in `onDestroy`). Binding uses **no `BIND_AUTO_CREATE`** on purpose —
this has to observe a tour that is already running, never conjure a service
with no tour behind it, which is exactly the zombie A4 is about. A
non-auto-create connection is still registered when the service is absent and
connects the moment it appears, so the same call serves `launchTourService()`
right after `startService()`.

Three things had to move with it:

- **One set of collectors.** `observeTourModeServiceState()` used to launch
  three `lifecycleScope` coroutines per connection and read the service
  through a nullable field. It now takes the bound service and keeps its
  collectors in `tourServiceObserverJob`, cancelled on rebind and on
  disconnect. Rebinding on every `onStart` would otherwise have stacked a set
  of collectors per resume, all writing the same views.
- **The service is the authority.** `isTourModeActive` is assigned from
  `serviceState.isRunning` rather than tracked in parallel. The tap still
  flips the FAB optimistically — the service reports `Starting` a moment
  later — but nothing else guesses.
- **A `Starting` state.** Deriving the UI from `serviceState` alone made
  things worse at first: the service only reached `Active` after settings
  load, TTS init and the first POI discovery, so the state a fresh binding
  replayed was `Inactive`, and the FAB flipped back to "start" for seconds
  after the tap. `TourModeState.Starting` closes that gap, with
  `isRunning` (`Starting || Active`) as the single question every caller asks
  — the FAB, the geofence revival path in `onStartCommand`, and the start/stop
  guards.

Two bugs fell out of the `Starting` work. Stopping a tour *during* setup
returned early (`!is Active`) and did nothing, so the guide carried on while
the FAB read "start"; `stopTourMode()` now accepts `Starting` and cancels the
tour's own coroutine, held in the new `tourJob`, so a cancelled start stops
registering geofences. And `Active` is now only promoted *from* `Starting`, so
a stop that lands mid-setup can't be undone by the tail of the start.

The one hole binding can't cover: a tour stopped from the notification while
the activity was unbound has nothing to call back into. `TourModeService.isAlive`
(a `@Volatile` companion flag set in `onCreate`/`onDestroy`) is checked in
`onStart` — if we think a tour is running and no service exists, clear the UI
before binding and let the service correct us if it is there after all.

Tests: 5 new cases in `TourModeStateTest` pinning `isRunning` for every state
(272 total, 0 failures); debug and release both compile. The lifecycle wiring
itself needs instrumentation to test — none was written for it.

**For later tasks:** A4 should note that the fact card's play/pause and skip
buttons `startService()` with a playback action, which will *create* the
service if it is gone — the same zombie path as a `START_STICKY` revival, from
a different direction.
