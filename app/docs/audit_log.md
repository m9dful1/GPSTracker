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

### A3 · "You have arrived" repeats every 5 seconds — `DONE`

The synthesized arrival instruction uses `maneuverPoint = newLocation`
(`NavigationServiceImpl.kt:328`), and the voice-prompt dedup key contains the
maneuver point (`MainActivity.kt:1264`). Every fix within 50 m of the
destination produces a new key, so the arrival prompt is spoken again and
again while parked.

**Fix:** use the route destination as the arrival maneuver point and latch
arrival so it is announced once per drive.

### A4 · A revived service becomes a zombie — `DONE`

`onStartCommand` returns `START_STICKY` (`TourModeService.kt:198`), so Android
restarts the service with a **null intent**: `when (intent?.action)` matches
nothing, but `startForeground` already ran. The result is a permanent "Tour
Mode Active" notification with no monitoring behind it.

**Fix:** treat a null intent as "resume the tour" (settings are persisted
anyway), or return `START_NOT_STICKY`.

### A5 · The same story can be told twice — `DONE`

`ContentDeliveryQueue.offer` (`ContentDeliveryQueue.kt:20`) has no dedup, and
three paths queue content for one place: geofence `enter`, geofence `dwell`
30 s later, and `APPROACHING`/`ARRIVED` proximity alerts. The only guard is
`currentPoi?.id != poi.id` (`TourModeService.kt:498`, `:574`), which stops
repeats only while that POI is still current — once the guide moves on, a
re-alert queues the same place again. Repeated content is the top complaint
recorded in `tour_guide_research.md:37`.

**Fix:** dedup by `poiId` inside the queue, plus a spoken-this-session set so
a place can't be re-queued after it has been told.

### A6 · Proximity alerts lose all but one POI per fix — `DONE`

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

### A7 · `narrationTimes` is unsynchronized shared state — `DONE`

Declared as a plain `mutableListOf` (`TourModeService.kt:130`), pruned with
`removeAt(0)` and appended in `generateAndQueueContent` (`:602`, `:633`) —
which runs concurrently from both the geofence and proximity launches on
`Dispatchers.Default` — and appended again by the way-of-life watcher
(`:827`), while `TourLogic.narrationAllowed` iterates it with `count {}`.
That is a live `ConcurrentModificationException` window; the surrounding
catch swallows it and silently drops a narration.

**Fix:** confine narration bookkeeping to one owner (a `Mutex`, or route all
delivery decisions through a single coroutine).

### A8 · `isDelivering` is checked and set non-atomically — `DONE`

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

### A9 · TTS initialization failure is discarded — `DONE`

`initialize()` returns `Boolean` and all three call sites ignore it
(`TourModeService.kt:239`, `:244`, `PlacesViewModel.kt:77`). A device without
the requested voice data takes the `LANG_NOT_SUPPORTED` path
(`AudioServiceImpl.kt:176`), which resumes `false`, never installs the
progress listener, and leaks the `TextToSpeech` instance. The user gets a
completely silent tour with no explanation.

**Fix:** fall back to the device default locale, surface the failure as a
tour-mode error (and an `ACTION_INSTALL_TTS_DATA` prompt), and shut down the
engine that failed to initialize.

### A10 · Way-of-life filler hammers Overpass on empty roads — `DONE`

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

### A11 · Route math runs on the main thread every fix — `DONE`

`FrameworkLocationClient` delivers updates on `Looper.getMainLooper()`
(`FrameworkLocationClient.kt:66`), and `updateNextInstructionBasedOnRoute`
calls the O(N) `findClosestPointOnRoute` once per instruction
(`NavigationServiceImpl.kt:385`). A city route with ~2000 polyline points and
40 instructions is ~80k distance computations every 5 s on the UI thread,
competing with the map's camera animation.

**Fix:** precompute each instruction's route index once per route version and
move the per-fix update off the main looper.

### A12 · Every narration re-fetches a POI already in memory — `DONE`

`handleGeofenceEvent` and `deliverNextContent` both call
`placesRepository.getPlaceDetails(id)` (`TourModeService.kt:495`, `:661`),
which misses the DB for any not-yet-visited place and hits the network
(`PlacesRepositoryImpl.kt:128`) — while
`LocationAwarenessServiceImpl.monitoredPointsOfInterest` is holding that same
object. On the Google provider that is a billable Places call per geofence
crossing, and a network failure means no narration at all.

**Fix:** carry the POI through (or keep an in-memory id→POI map) instead of a
repository lookup.

### A13 · ETA is a 30 mph constant after the first calculation — `DONE`

The routing API's own duration is used once (`NavigationServiceImpl.kt:97`)
and then replaced by `remainingDistance / 13.4f` (`:286`, `:291`), so highway
ETAs read badly wrong.

**Fix:** scale the API duration by the remaining route fraction and re-use
the real duration on each recalculation.

---

## Tier 4 — data safety and completeness

### A14 · Schema changes will delete the user's journal — `DONE`

`fallbackToDestructiveMigration()` (`AppDatabase.kt:37`) plus
`android:allowBackup="true"` with no `dataExtractionRules`. The Tour Journal
is user-visible history — the one thing here that should not be disposable.

**Fix:** real migrations from v2 onward, and backup rules that either exclude
the DB or handle it deliberately.

### A15 · Nothing ever evicts the caches — `DONE`

`tour_content` and `points_of_interest` grow without bound; `TourContentDao`
has only `deleteAllContent()`, which nothing calls.

**Fix:** age- or size-based trim, plus a "clear cached stories" control in
settings. Must be migration-safe once A14 lands (no `DROP TABLE`).

### A16 · Dead code and dead dependencies — `DONE`

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

### A3 — "Announce the destination once, not at every fix"

Fixed at both ends, because arrival was reported wrongly *and* announced
wrongly.

In `NavigationServiceImpl`, the synthesized arrival instruction carried
`maneuverPoint = newLocation` — the car's own drifting position — so every
fix inside the radius looked like a brand new instruction. It now carries the
route's destination, and the whole "have we arrived" question moved into
`util/ArrivalLatch`, reset when a drive starts and stops. In `MainActivity`,
the `lastAnnouncementKey` string became `util/VoicePromptGate`, which keeps
the same one-key-per-maneuver rule and adds a separate arrival latch, so
arrival is spoken once per drive no matter which instruction reports it —
the route's own final instruction and the synthesized one are two different
keys for the same event. The gate is also reset when a new destination is
chosen, which the bare field never was: a second drive to a place already
visited would have kept its stale key.

The latch turned up a second bug on the way in. A Take a Tour loop drive ends
where it began, so it starts *inside* the arrival radius — the old code
announced arrival immediately and repeatedly at the trailhead, and a plain
"once per drive" latch would have made that the only announcement, with
nothing said on the actual return. `ArrivalLatch` therefore requires the car
to have been outside the radius before an arrival counts. The cost is that a
destination closer than 50 m is never announced; silence for a drive that
short is the right trade.

Tests: 8 cases in `ArrivalLatchTest` and 8 in `VoicePromptGateTest` (288
total, 0 failures) covering the parked-car repeat, the loop drive at both
ends, one maneuver announced twice at different distances, and arrival
reported two different ways. Both new files are plain Kotlin in `util/`, which
is what made this testable at all — the behaviour used to live in a private
`Float` comparison inside a service that needs a `Context` and a `Geocoder`.

**Noted, not fixed:** `NavigationServiceImpl.kt:247` warns "Condition is
always 'true'" — `val dest = currentState.destination` is already smart-cast
non-null by the guard at the top of `updateNavigation`. Confirmed pre-existing
(same warning at the old line 241 with my changes stashed). It belongs with
A18's warning sweep.

### A4 — "Stop the tour service from haunting the notification shade"

`onStartCommand` called `startForeground` first and worked out what to do
second, so any command that turned out to be meaningless still left a "Tour
Mode Active" notification behind. It now resolves the intent into a
`util.TourCommand` *before* entering the foreground:

- **A null intent is `RESUME`.** `START_STICKY` hands a killed service back
  with no intent; `when (intent?.action)` matched nothing, so the service sat
  in the foreground monitoring nothing at all. The tour's settings are
  persisted, so it starts the tour again instead. Resuming re-plays the
  spoken greeting — kept deliberately: after the system reclaims the app, "I
  found 12 places nearby" is how the user learns the guide is back.
- **Controls for a tour that has ended are `NONE`.** The A2 note called this
  out: the fact card's play/pause and skip buttons `startService()`, which
  *creates* the service if it is gone. A `NONE` command stops the instance
  with `stopSelf(startId)` and returns `START_NOT_STICKY` without ever going
  foreground. Nothing reaches the service that way through
  `startForegroundService()` — only the geofence path uses that, and it never
  resolves to `NONE` — so skipping `startForeground()` can't trip the
  five-second rule.
- **A stop request for an already-stopped tour is `NONE` too**, which ends the
  instance rather than parking it in the foreground.

A malformed geofence intent used to `return START_STICKY` after
`startForeground` had already run — same zombie, third route. It now stops the
service if no tour is behind it, and is ignored (rather than treated as a
stop) if a tour is running: one bad intent shouldn't end a live tour.

Tests: 9 cases in `TourCommandTest` (297 total, 0 failures), including the
null-intent revival, playback controls with and without a tour, and an unknown
action.

**Still open, related:** if the tour dies into `Error` while geofences are
registered, `stopTourMode()` returns early and they are never unregistered.
Not touched here — it belongs with A7/A8's single-owner rework of the tour's
state.

### A5 — "Tell each place once"

`ContentDeliveryQueue` now enforces one place, one telling, in two parts:

- **At most one pending entry per `poiId`.** The geofence `enter`, the `dwell`
  30 s later and a proximity alert all queue the same story; the second and
  third are refused. A *stronger* claim is the exception — an `ARRIVED` alert
  (priority 5) after an `APPROACHING` one (3) replaces the waiting entry
  instead of queueing behind it, so the story moves up the queue rather than
  being told twice.
- **A set of places already told**, checked on `offer`. That closes the
  re-alert case the old `currentPoi?.id != poi.id` guard could not: it only
  held while the place was still current, so a place re-alerting after the
  guide moved on queued again.

The delivered set is marked from `TourModeService` on `COMPLETED`, through a
new `ContentService.markContentDelivered`, deliberately **not** from `poll()`.
Content can be polled and then dropped for being behind the listener
(`TourLogic.narrationIsStale`), and that place was never actually narrated —
the existing comment promises a future pass can still tell it, and marking on
dequeue would have quietly broken that promise. `clear()` drops both the
pending entries and the told set, so the next tour starts fresh; the only
caller is `stopTourMode()`.

Two things this deliberately does not change: on-demand playback from the
place details sheet goes straight to `AudioService`, never through the queue,
so a place can still be replayed by hand after the guide has told it; and the
`currentPoi?.id` pre-checks in the geofence and proximity handlers are now
redundant but harmless, so they stayed.

Tests: 7 new cases in `ContentDeliveryQueueTest` (304 total, 0 failures)
covering enter+dwell, arrival replacing approach, the weaker claim, the
re-alert after telling, the stale-drop re-queue, other places being
unaffected, and a fresh tour after `clear()`.

### A6 — "Alert about every place in range, once each"

The `MutableStateFlow` that alerts were written into is gone.
`processNewLocation` now takes an `onAlert` callback and sends each alert as it
is found, so two places in range produce two alerts instead of whichever won
the `ConcurrentHashMap` iteration order — and a fix that finds nothing sends
nothing, where before the listener re-read the retained value and re-sent the
last alert forever.

Per-POI rate limiting landed in the same change, as the task required: the
conflation was the only thing holding back an alert per place per fix. The
rule lives in `util/ProximityAlertGate` — alert when the place's state moves
on (nearby → approaching → arrived), or when the same state has held for
`DEFAULT_REPEAT_AFTER_MS` (10 minutes), because a repeat says nothing new. The
gate is consulted even when a place produces no alert, which is how leaving
the radius forgets it: coming back is news again rather than waiting out a
cooldown. It is cleared on unregister and on stop.

This is the first change to make the subsystem actually pull its weight —
narration previously survived only because the geofence path fires per POI,
which made these alerts dead weight.

**One thing this change forced.** Two places alerting on one fix means two
concurrent `generateAndQueueContent` coroutines, and `narrationTimes` was a
plain `mutableListOf` read with `count {}` while another coroutine appended —
a `ConcurrentModificationException` that, on the proximity path, has no
surrounding catch and so would crash rather than drop a narration. It is now a
`CopyOnWriteArrayList`, which closes the crash. **A7 still owns the real fix**:
one owner for the per-tour state, so the cap is read and written atomically
rather than merely without crashing.

Tests: 9 cases in `ProximityAlertGateTest` (313 total, 0 failures) covering the
per-fix repeat, state progression, three places not hiding each other, the
repeat window, leaving and re-entering the radius, unregister, and reset.

### A7 + A8 — "Give the tour's state one owner"

Done as one change, as both tasks said to: they are the same problem, and the
four fields wanted one owner rather than four locks.

`util/TourSession` now holds the hourly narration cap, the regions covered,
the delivery flag and the skip request, each behind one lock, with the rules
still in `TourLogic`. What that fixes:

- **The cap is claimed, not checked-then-taken.** `tryReserveNarration` prunes,
  counts and records in one step, so two places coming into range on the same
  location fix can no longer both slip past a cap of one. The old advisory
  check survives as `canNarrate`, used before the content fetch purely to skip
  work the cap would waste; the claim happens at the point of queueing. A
  duplicate the queue turns away hands its slot back (`releaseNarration`) —
  nothing was said, so nothing should count. This also retires A6's
  `CopyOnWriteArrayList` stopgap: the list is a plain one again, because
  nothing outside the lock touches it.
- **Delivery is claimed with a token.** `beginDelivery()` returns null if a
  loop already owns delivery, so the two triggers can't both start telling
  stories. The token matters for the second half of A8: an interrupted loop
  used to clear the flag underneath a newer one, so `endDelivery(token)`
  releases only if that loop is still the current generation — `reset()` bumps
  the generation, orphaning anything still unwinding.
- **The delivery recursion became a loop**, which A1's entry asked for. The
  error run is a local variable now rather than a parameter threaded through
  recursive calls, and the `finally` releases delivery on every exit including
  cancellation.

**"Next" needed rethinking.** It used to stop the audio and start a second
delivery, which is precisely how two loops ended up fighting. Stopping audio
is indistinguishable from anything else taking the floor, so the request is
now explicit: `requestSkip()`, then the running loop reads the interruption as
"move on" and continues. If nothing was delivering, the launched call starts a
loop instead — same outcome either way. A pending skip is cleared as each
narration begins, so it only ever speaks for the story it interrupted; left
set, a later unrelated flush (an on-demand replay from the place details
sheet) would have read as a skip and pulled the tour forward.

Tests: 15 cases in `TourSessionTest` (328 total, 0 failures), including the
cap-of-one under five concurrent claims, the released slot, the superseded
loop that must not release the flag under a new one, and the skip that only
speaks for its own story.

**Left where it is, deliberately:** `lastSpokenAt` (single `@Volatile` writer),
`lastWayOfLifeAt` and `currentPoi` (one coroutine each), and the trip
counters, which the delivery loop now owns exclusively — one loop at a time is
what made them safe. `consumeTripSummaryPhrase()` still reads them from the
binder thread; an `Int` read of a counter is benign, and moving all of it in
would have doubled the diff for no safety gained.

**Still open, unchanged:** the `Error`-state geofence leak noted under A4.
`stopTourMode()` returns early when the state is `Error`, so registered
geofences are never unregistered. It survived this rework because it is a
state-machine question, not a shared-state one — it belongs with A19's tests
around the service.

### A9 — "Say why the guide has no voice"

`initialize()` now does three things it didn't:

- **Falls back to the device's default voice.** A device without the requested
  language used to fail outright; it now retries with `Locale.getDefault()`,
  because a guide speaking the wrong language is worth far more than a silent
  one. The `setLanguage` result check moved into
  `AudioServiceImpl.languageUsable`, which also catches the `ERROR` and
  no-engine cases the old two-value check missed.
- **Shuts down an engine it can't use.** The old `LANG_NOT_SUPPORTED` path
  resumed `false` and walked away from a live `TextToSpeech` — a leaked
  connection to the TTS service that never spoke a word.
- **Reports what happened.** `AudioService.voiceAvailability` is a `StateFlow`
  of `READY` / `USING_DEFAULT_VOICE` / `MISSING_VOICE_DATA` /
  `ENGINE_UNAVAILABLE`, with `canSpeak` for the callers that only need the
  yes-or-no. `updateVoiceSettings` keeps it honest when the language changes
  mid-tour, and falls back the same way rather than muting a running tour.

The UI reads that one flow through `PlacesViewModel`, so there is a single
place explaining silence and no double-reporting. `MISSING_VOICE_DATA` — the
one case the user can fix — gets a Snackbar with an **Install** action firing
`ACTION_INSTALL_TTS_DATA` (guarded against no activity handling it); the other
two get a plain explanation. Each state is announced once rather than on every
re-emission.

**A deliberate departure from the task's wording.** The task said to surface
this as a tour-mode error, and I didn't: `TourModeState.Error` means *fatal*
after A2 and A4 — `isRunning` is false for it, so the FAB would flip off, and
`TourCommand.forAction` maps a stop request on a non-running tour to
`NONE`, which would have broken the Stop button for the rest of the tour. A
tour with no voice is silent, not over: the map, the journal, the fact cards
and the notification all still work. So the voice problem got its own channel
and the tour state was left alone. The service does use the return value now —
it logs the silent start and skips the welcome announcement instead of
speaking into a dead engine.

Tests: 6 cases in `AudioServiceImplTest` (334 total, 0 failures) over
`languageUsable` — every available result, both missing-voice results, the
engine error, and no engine at all — plus `canSpeak` for each availability.
The engine callback itself needs instrumentation; the decision it makes is
what these cover.

### A10 — "Back off when there's nothing to say about where you are"

My own bug, from `39d6d35`. Every path out of `maybePlayWayOfLife` stamped
`lastWayOfLifeAt` except the one that mattered: an empty region lookup just
returned, so the 30-second watcher POSTed to the public Overpass instance again
on the next tick, and the next, for the rest of the drive. The emptiest roads
are exactly what the feature exists for, so the failure case was the common
case.

Now an empty result stamps the cooldown and counts itself.
`TourLogic.wayOfLifeCooldownMs` doubles the wait per consecutive empty lookup
from the ordinary 10 minutes, capped at an hour, and `shouldPlayWayOfLife`
takes the cooldown as a parameter (defaulted, so the existing call sites and
tests are unchanged). A rural highway goes from 120 requests an hour to at
most 6. The counter resets the moment a lookup finds a region, and on tour
stop. Empty and rate-limited are deliberately treated the same — `nearbyCities`
returns an empty list either way, and both deserve the same answer: ask less
often.

`NearbyCityApiService` also caught only `IOException` around a body it then
parses, so a truncated response threw `JSONException` past its own "empty on
failure" contract. The way-of-life watcher's catch-all meant that surfaced as a
mystery log line and a retry 30 seconds later rather than a crash — the wrong
behaviour, not a fatal one. It now catches `JSONException` and returns empty
like every other failure.

Tests: 4 new cases in `TourLogicTest` (338 total, 0 failures) for the ordinary
cooldown, the doubling, the cap, and one that checks the backoff actually holds
filler back through `shouldPlayWayOfLife`.

**Noted for the next round — a systemic version of the same flaw.**
`PlacesApiService`, `GeocodingApiService`, `RoutingApiService`,
`GooglePlacesApiService`, `GoogleGeocodingApiService` and
`GoogleRoutingApiService` all parse the response body inside a `try` that
catches only `IOException`, so a malformed body throws `JSONException` at the
caller in each of them. Not fixed here: each has a different caller with
different error handling, so it wants one deliberate sweep rather than a
drive-by.

### A11 — "Stop recomputing the whole route on every location fix"

Both halves of the task, plus the duplication that made it hard to see.

**The precompute.** `updateNextInstructionBasedOnRoute` asked "where is this
maneuver on the route?" for every instruction on every fix — for a city route
of ~2000 points and ~40 instructions, about 80,000 distance computations every
five seconds, and 40 `Timber.d` lines with them. Maneuver points don't move, so
`NavigationState` now carries `instructionRouteIndices`, computed once per route
(initial calculation and each off-route recalculation) inside
`withContext(Dispatchers.Default)`. Per-fix work drops to one pass over the
route plus one distance per instruction — roughly 2,080 computations instead of
80,000.

**Off the main looper.** Fixes still arrive on the main looper (that is what
`FrameworkLocationClient` offers, and its other callers want it), but the
listener now hands the work to `serviceScope` — `Dispatchers.Default` — instead
of doing it inline, so it no longer competes with the map's camera animation.
A `Mutex` keeps fixes in arrival order, one at a time, since `updateNavigation`
reads and then writes `navigationState`.

**The geometry moved to `util/RouteProgress`**, which is where it became
testable: `closestPointIndex`, `instructionRouteIndices` and
`nextInstructionIndex` are pure and use `GeoUtils` rather than the private
Haversine copy the service kept for itself. The old function's fallback — when
no maneuver is ahead, name the nearest one rather than going blank — is
preserved and now has a test, as does "a maneuver at the current point counts
as behind us".

Tests: 11 cases in `RouteProgressTest` (349 total, 0 failures) including the
off-route match, the empty route, the fallback past the last turn, an unknown
position, and a short index list not throwing.

**Not done, and deliberately:** the one remaining per-fix pass over the route
could be a windowed search around the last known progress index instead of a
full scan. That is a real further win on long routes, but it needs care around
loop routes that pass near themselves, and 2,000 float operations off the main
thread every five seconds is no longer the bottleneck.

### A12 — "Narrate the place we already have"

`LocationAwarenessService` gained `monitoredPointOfInterest(id)`, and both
lookups go through one `placeById` helper: memory first, repository second.
Everything the guide narrates was registered for monitoring beforehand, and
registration keeps the whole object, so the id arriving on a geofence
transition or a queued narration is nearly always one already in hand. The
repository stays as the fallback, which is the only path that can reach the
network — a billable Places call per geofence crossing on the Google provider,
and no narration at all when it fails.

The geofence handler also got simpler: `getPlaceDetails(...).onSuccess { }`
became a plain null check, so an unknown id now logs and moves on to the next
geofence instead of silently skipping the rest of the callback body.

**A data-loss trap this opened, and how it's closed.** The repository copy is
the *stored* row; the monitored copy came from discovery. The delivery loop
finished by writing `poi.copy(isVisited = true, ...)` through
`saveVisitedPlace`, which is an insert-or-replace of the whole row — so
narrating from the discovery copy would have written the user's own notes off
the record. `PlacesRepository.markPlaceNarrated` now asserts only the visit:
`narrationStamp` keeps the stored row wherever there is one and stamps the
timestamp onto it, reading locally so the saving never reaches the network
either. Registration already merges visited state (`mergeVisitedState` runs on
every discovery), so the in-memory copy is otherwise as good as the stored one.

Tests: 3 new cases in `PlacesRepositoryImplTest` (352 total, 0 failures) — the
plain stamp, the notes surviving a re-narration, and the cooldown restarting.

### A13 — "Trust the route planner's own ETA"

The planner's duration was used for exactly one status emission and then
discarded: every later fix recomputed the time left as
`remainingDistance / 13.4f`, one hardcoded average, which reads a motorway
drive as though it were a city block. `NavigationState` now keeps the route's
planned distance and duration, and `RouteProgress.remainingTimeMs` scales the
planned duration by the fraction of route still to drive. A recalculation
replaces both with the new route's figures, so the estimate follows the roads
the guide is actually on.

The 13.4 m/s constant survives as `RouteProgress.FALLBACK_SPEED_MPS`, used only
when there is no planned duration to scale — the straight-line fallback when
the routing API failed. It is named and documented as a guess now, rather than
being the answer.

**Also changed, related:** the status's `distanceRemaining` was the crow-flies
line to the destination while the ETA came from the road ahead, so the two
numbers on the navigation card disagreed with each other. Both now come from
`RouteProgress.remainingRouteDistance`. Arrival detection still uses the
straight-line distance, which is the right measure for "am I there".

Tests: 6 new cases in `RouteProgressTest` (358 total, 0 failures) — the route
half driven, nothing left at the end, the planner's duration scaling by
distance, a full route keeping its full duration, arriving at zero, and the
fallback when no duration was priced.

### A14 — "Carry the journal across schema changes"

**Migrations.** `fallbackToDestructiveMigration()` is gone. `exportSchema` is
on, `ksp` writes the schemas to `app/schemas`, and `2.json` is committed — that
file is the record a future migration migrates *from*, and its absence is why
this couldn't be fixed by writing migrations alone. `AppMigrations.ALL` is where
they go, empty today because version 2 is current, and
`fallbackToDestructiveMigrationFrom(1)` keeps exactly one exception: version 1
predates schema export, so there is no shape to carry forward. Every version
from 2 on must migrate or the open fails loudly.

`AppMigrationsTest` is the guard that makes this stick: it fails when
`AppDatabase.VERSION` outruns the migration chain, when the chain has a gap,
when a migration doesn't move forward, or when one claims to start before
version 2. Bumping the version without writing the migration is now a red build
instead of a wiped journal on somebody's next update.

**Backups.** Both `res/xml` rule files existed as untouched IDE templates and
neither was referenced from the manifest, so `allowBackup="true"` meant the
platform default: back up everything, decided by nobody. They are wired up now
and say what travels — the database (journal, visited places, notes) and the
DataStore preferences — and by listing those, everything else stays behind.
Consent state is deliberately not restored: it is per-device and better
re-collected than inherited.

**The journal mode was the catch.** A `.db` restored without the write-ahead log
it was written with is missing whatever that log still held, so backing up a WAL
database is backing up a maybe. The database is `JournalMode.TRUNCATE` now — one
file, nothing outside it. The cost is writer/reader concurrency this app has no
use for: it writes a handful of rows per drive.

Tests: 4 new cases in `AppMigrationsTest` (362 total, 0 failures).

**Not done here:** a migration that actually runs against a real old database
needs `MigrationTestHelper` and an instrumented test, which this project has no
harness for (`ExampleInstrumentedTest` is the only one, and A16 deletes it).
The exported schema is the prerequisite for writing those, and it now exists.

### A15 — "Let the story cache forget, and leave the journal alone"

**Round 1 got the scope wrong, and this is the correction.** The task named two
growing tables, but `points_of_interest` is not a cache: its only writers are
`saveVisitedPlace` (the user marking a place, or writing a note on it) and
`markPlaceNarrated`. Every row in it is either somewhere the guide narrated or
somewhere the user annotated — it *is* the Tour Journal that A14 just went to
some trouble to protect. Evicting from it would be deleting the user's history
to save space. It grows with what the user has done, which is the correct
behaviour, and nothing here touches it.

That leaves `tour_content`, which is a real cache of regenerable text.
`TourContentDao` gained three queries — a count, an age-based delete, and a
keep-the-newest-N trim — and `StoryCachePolicy` holds the policy those apply:
90 days, or 500 entries, whichever bites first. The age rule is the main one;
the cap catches an install that drives daily for years and never idles long
enough for anything to age out. `pruneStoryCache` runs once per tour start,
which is the quietest moment the app has.

All three are `DELETE` statements against the existing shape — no schema
change, no version bump, no `DROP TABLE`, which is what A14 requires of
anything touching this database from now on.

**The settings control** is a "Clear cached stories" button in tour settings,
with a caption saying in as many words that the tour journal is separate and
stays. It routes through `PlacesViewModel.clearCachedStories` to the
`deleteAllContent` query that existed all along and had no caller.

Tests: 5 cases in `StoryCachePolicyTest` (367 total, 0 failures) over the
cutoff, a story from today surviving it, one past the age falling before it, and
the cap boundary in both directions. The queries themselves need Room and an
instrumented test to exercise; the policy they enforce is what these pin.

### A16 — "Delete what nothing uses"

Gone, each re-checked for references first: `domain/usecase/` (all three —
`GenerateContentForPlaceUseCase`, `GetNearbyPointsOfInterestUseCase`,
`SpeakContentUseCase`), `domain/service/PlacesService.kt`,
`domain/repository/ContentRepository.kt` (referenced only by the use case that
went with it), and both IDE template tests. `retrofit`, `converter-gson`,
`logging-interceptor`, `navigation-fragment-ktx` and `navigation-ui-ktx` are out
of the build — there is no `res/navigation`, no `NavHostFragment`, and every API
service here is hand-rolled OkHttp.

**Two things the app was getting by accident, now declared.** Removing those
libraries broke the build in a way worth recording, because it means the
dependency list was lying about what the app depends on:

- **OkHttp** was only ever transitive — from Retrofit and the logging
  interceptor, and after them from the MapLibre SDK. The entire data layer is
  written against it. It is declared explicitly now (4.12.0, the version that
  already resolved, so nothing moved), because the map SDK is a swappable
  choice and shouldn't be what supplies the HTTP client.
- **`fragment-ktx` and `activity-ktx`** supply the `by viewModels()` and
  `by activityViewModels()` delegates that `MainActivity` and three fragments
  use. Those arrived through `navigation-fragment-ktx`; the base `fragment` and
  `activity` artifacts don't carry them. Both are now in the version catalog,
  with `androidx-activity` switched from `activity` to `activity-ktx`.

**`darkModeEnabled` is dropped**, not wired up. The theme is already
`Theme.MaterialComponents.DayNight.NoActionBar` with a `values-night/colors.xml`
beside it, so the app follows the system today and nothing is lost by removing
the flag. Wiring the flag as it stood would have been worse than nothing: a
`Boolean` can't say "follow the system", so `false` — its default — would have
forced light mode on someone whose phone is in dark mode. An explicit override
wants three states, and this field wouldn't have been reusable for that anyway.

No new tests: this is removal, and the tally drops by one (366) because
`ExampleUnitTest` was one of the deletions. Debug and release both compile,
and nothing named retrofit or navigation remains on the runtime classpath.
