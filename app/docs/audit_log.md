# Codebase audit log

A working log for the audit-and-fix loop: the open task list, what was done
about each one, and what later audit rounds find. Round 1 was a full read of
every Kotlin source file (13.1k lines) plus `testDebugUnitTest` and
`lintDebug`. Round 2 begins after the twenty tasks it opened were all done.

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

### A17 · `geocodeAddress` is broken on Android 13+ and unused — `DONE`

The API 33 branch passes a callback and returns `result` synchronously, so it
is always null (`NavigationServiceImpl.kt:538`). Nothing calls it.

**Fix:** delete it, or make it correct with `suspendCancellableCoroutine`
before something depends on it.

### A18 · Release readiness — `DONE`

No `signingConfig`; `isMinifyEnabled = false`; `versionCode = 1` /
`versionName = "1.0"`; `lintDebug` fails on `MissingPermission`
(`FrameworkLocationClient.kt:74`); 96 lint warnings (26 `UseTomlInstead`, 24
`GradleDependency`, 8 `DefaultLocale`); no CI at all.

**Fix:** handle the lint error, add a GitHub Actions workflow running
`assembleDebug + testDebugUnitTest + lintDebug`, and set up release signing
and shrinking.

### A19 · The buggiest file has zero tests — `DONE`

`TourLogic`'s pure functions are thoroughly covered, but `TourModeService` —
1146 lines holding every issue in Tiers 1–2 — has no tests, and neither do
`AudioServiceImpl`'s state transitions beyond its two pure helpers.

**Fix:** extract the delivery state machine behind fakes (`ContentService`,
`AudioService`, `LocationAwarenessService`) so A5, A7, A8 and A10 are covered
by tests rather than reasoning.

### A20 · Smaller polish — `DONE`

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

### A17 — "Delete the geocoder that never worked"

Deleted rather than fixed, from both `NavigationServiceImpl` and the
`NavigationService` interface, along with the `Geocoder` field and the three
imports that went with it.

Fixing it would have meant building a `suspendCancellableCoroutine` wrapper
around the platform geocoder to compete with something the app already has and
already uses: address lookup goes through `GeocodingApiService` (Photon) or
`GoogleGeocodingApiService`, chosen by the map-provider setting, and that is
what the destination search runs on. A second implementation on a different
backend, with different failure modes and no callers, is not an asset — and this
one returned null on every Android 13+ device because it read `result` before
the callback it passes had any chance to set it.

If platform geocoding is ever genuinely wanted, `suspendCancellableCoroutine`
is the shape it needs, and the API services are the precedent for where it
belongs.

No new tests: removal, and the tally holds at 366 with debug and release both
compiling.

### A18 — "Make the build tell the truth about itself"

**`lintDebug` passes.** It had failed since the baseline. The
`MissingPermission` error was `LocationManagerCompat.removeUpdates` inheriting
the request side's permission annotation; it is suppressed with the reason
written down — handing updates *up* can't expose a location, and it has to work
when the permission has just been revoked, which is exactly when it matters.

**CI exists**: `.github/workflows/build.yml` runs `assembleDebug`,
`testDebugUnitTest` and `lintDebug` on every push and pull request — the same
three commands each task in this log verifies with — then `assembleRelease`
separately, because R8 runs for no other variant and a missing keep rule can't
fail any of the first three. No secrets: every API key is optional and the build
falls back to keyless services and Google's test ad units, so a fresh clone
passes as-is. Reports upload on failure.

**Release shrinking is on**, and the keep rules are the interesting part. R8
renames what it likes, and three kinds of name in this app are *stored data*:

- `LatLng` is written into Room columns as Gson JSON, so its field names are
  the on-disk format. Kept whole — the fields are only ever touched
  reflectively, so shrinking would otherwise be free to drop them.
- Enum constants are persisted by name (`ContentSource` and `DetailLevel` in
  Room, `Category` and `AccountTier` in DataStore) and read back with `valueOf`.
- Gson needs `Signature` to resolve the converter's anonymous `TypeToken`.

Verified against R8's own output rather than by assertion: `seeds.txt` lists
`LatLng.latitude`/`longitude` and all four enums with their constants, and
`mapping.txt` shows `LatLng` unrenamed. Obfuscating any of them would have
produced a release-only failure on a device that had previously run a debug
build — the worst possible shape for a bug. Line numbers are kept for readable
crash reports. The release APK is 51.9 MB against the debug 61.2 MB.

**Signing** reads `RELEASE_STORE_FILE` and friends from `local.properties` or
the environment, the same pattern the API keys already use, and the config only
exists when the keystore does. A clone without it still builds a release APK,
just unsigned — which is all CI could do anyway.

**`versionCode = 1` is left alone deliberately.** For an app that has never
shipped, version 1 / 1.0 is the correct answer, and inventing a higher number
would say something untrue. What was missing is the rule, which now lives here:
bump `versionCode` on every store upload, `versionName` when the release is
worth a name.

**Not done: the warning sweep.** 78 warnings remain, and the two biggest groups
are deliberate deferrals rather than oversights — 22 `UseTomlInstead` (hardcoded
dependency coordinates that belong in the version catalog; mechanical, and A16
already moved the two that mattered) and 22 `GradleDependency` plus 11
`NewerVersionAvailable` (upgrades, which want reading release notes rather than
bumping numbers in a loop). The 8 `DefaultLocale` warnings *were* fixed — four
`String.format` calls in the settings sheet, the only warnings in the list that
were a correctness question rather than housekeeping.

Tests: unchanged at 366, 0 failures. `lintDebug` green for the first time since
round 1, and `assembleRelease` builds through R8.

### A19 — "Put the delivery loop where it can be tested"

The loop moved out of `TourModeService` into `service/NarrationDelivery`, a
plain class taking `TourSession`, `ContentService`, `AudioService` and a
`Host` interface for the service-side effects — the fact card, the visit
record, the trip tally, the place lookup and the spoken introduction. The
service implements `Host` and keeps a one-line `deliverQueuedContent()`.

The extraction was cheap because the collaborators were already interfaces;
what made the loop untestable was the `Service` wrapped around it, not the
dependencies. `NarrationDeliveryTest` fakes `ContentService` over a real
`ContentDeliveryQueue` and scripts `AudioService` outcome by outcome, so the
behaviours the earlier tasks argued about are now asserted:

- every queued story told in priority order, and marked so it can't be queued
  again (A5);
- a place already behind the car skipped without being spoken, recorded or
  marked (the stale drop);
- **a stale skip not spending the error budget** — the case the A1 comment
  claimed and nothing checked;
- a dead engine giving up after `MAX_CONSECUTIVE_DELIVERY_ERRORS` with the rest
  of the queue intact for the next trigger, and a success resetting the run
  (A1);
- an interruption standing the loop down, a skip request carrying it to the
  next story instead (A8's "Next" rework);
- one loop at a time, and the gate released when it ends (A8).

**Two tests I had to rewrite because my premise was wrong, which is the point
of writing them.** I first scripted "fail, fail, succeed" over two stories and
expected both told; a failed story is *consumed* from the queue, not retried, so
nothing was told at all. The same mistake sat in my stale-skip test. Both now
use more stories than the cap, which is the only arrangement that can actually
distinguish a reset run from a spent one. Reasoning about this loop is exactly
what kept going wrong.

Tests: 12 cases in `NarrationDeliveryTest` (378 total, 0 failures).

**What still has no tests, honestly:** `maybePlayWayOfLife` (its policy is
covered in `TourLogicTest`, its orchestration isn't), the geofence and proximity
handlers, and `startTourMode`/`stopTourMode`. Those are Android lifecycle and
notification work; the same `Host`-style seam would extract them, but each is a
smaller behaviour than the delivery loop and none of them held a Tier 1 bug.

### A20 — "Four small lies the app told"

**The play/pause button.** `_isNarrationPlaying` was maintained around the
button presses, so anything that paused playback without a press — a phone call
taking audio focus, which is the common case — left the icon wrong for the rest
of the tour. `AudioService` now publishes `isPlaying` (an utterance in progress
and not paused), republished from a single `publishPlaying()` after every
transition including the audio-focus ones, and the service exposes it straight
through instead of tracking a copy. Three manual writes went away with it.

**Notification channels** read from `strings.xml` like the rest of the UI —
four names and four descriptions that were hardcoded English inside the
service.

**The ETA clock** used `SimpleDateFormat("HH:mm")`, so a phone set to 12-hour
time was told its arrival in 24-hour. It uses `DateFormat.getTimeFormat(this)`
now, which is the platform's answer to that question.

**A planned tour's stops can no longer be crowded out.** The corridor
registration re-discovered places along the route and capped that discovery at
60, so on a long tour a stop the *user had chosen* could be dropped in favour of
whatever discovery happened to return. `updateRouteCorridor` now takes the
plan's stops and lists them first, through
`TourPlanLogic.corridorPlaces` — the tour's own places are the reason the drive
exists, and discovery is the extra. `MainActivity` holds the active tour's stops
beside its name and clears both together.

Tests: 4 cases in `TourPlanLogicTest` (382 total, 0 failures) — chosen stops
first, a stop that discovery also found listed once as the chosen copy, an
ordinary drive with no stops, and a tour whose corridor turned up nothing. The
other three items are framework plumbing (a `StateFlow` published under a lock,
resource lookups, a platform formatter) and are not unit-testable here; the
`isPlaying` transitions in particular want instrumentation.

---

## Round 1 complete

All twenty tasks `DONE`. From the baseline: **264 tests → 382**, `lintDebug`
from failing to clean, no CI to a workflow that builds debug, tests, lints and
runs R8 over a release build. Nine of the twenty produced a new pure class in
`util/` or `service/` — the recurring lesson being that the logic was untestable
because it sat inside an Android component, not because it was complicated.

Three findings were banked along the way for the next round rather than fixed
out of scope: the `JSONException` sweep across six API services (A10), the
`Error`-state geofence leak (A4, A7/A8), and the lint warning groups A18
deferred.

---

# Round 2

A second full pass, after round 1's twenty tasks. The reading went where round
1 spent least time — the map controllers, the UI sheets, the preferences layer,
the Gemini and ads code — plus a re-read of what round 1 itself changed, and
the three findings round 1 banked deliberately.

## Baseline at round 2

- 382 unit tests, 0 failures.
- `lintDebug` **passes**: 0 errors, 78 warnings.
- CI builds debug, tests, lints and runs R8 over a release build.
- Release signs from a keystore when one is configured; shrinking on.

**Two files got bigger, not smaller.** `MainActivity` went 1391 → 1512 lines
and `TourModeService` 1146 → 1287, even though round 1 extracted the delivery
loop *out* of the service. Nine new pure classes carried logic out to where it
could be tested, but the fixes also added routing, state and comments to the
two files that were already the largest. That is the honest shape of round 1:
the *hard* parts became testable; the *big* parts stayed big.

---

## Tier 1 — breaks in normal use

### B1 · A corrupt preferences file crashes every launch — `DONE`

`preferencesDataStore(name = "user_preferences")` is created with no
`corruptionHandler` (`UserPreferencesRepository.kt:25`) and none of its flows
carry a `.catch`. `GPSTrackerApplication.onCreate` reads two of them with
`runBlocking { first() }` (`GPSTrackerApplication.kt:44`), so a
`CorruptionException` — or any `IOException` — from that file is an
unhandled exception on the main thread during application startup. The app then
fails to launch until its data is cleared, which also destroys the Tour Journal.

A14 made this materially more likely rather than less: cloud backup and device
transfer now restore the `datastore/` directory, so a file written by another
device (or a half-finished restore) is a real input where before it could only
be a local write interrupted by a kill.

**Fix:** `ReplaceFileCorruptionHandler { emptyPreferences() }` on the DataStore,
and `.catch` on the exposed flows emitting defaults, so a bad file costs the
user their settings rather than the app.

### B2 · One unguarded enum read can break every preference — `DONE`

`userPreferencesFlow` maps the stored detail level with
`UserPreferences.DetailLevel.valueOf(it)` (`UserPreferencesRepository.kt:98`).
An unrecognized value throws `IllegalArgumentException` inside the `map`, which
fails the flow for every collector — including `startTourMode`'s `.first()`, so
the tour ends in `TourModeState.Error` and nothing explains why.

It is the only unguarded read in the file. `parseCategories` skips unknown names
with a comment about other app versions, `MapProvider.fromStorage`,
`AccountTier.fromStorage` and `MapStyles.normalize` all sanitize. The
convention exists; this site missed it.

**Fix:** parse it like its neighbours — unknown value falls back to `MEDIUM`.

### B3 · A new destination keeps the old destination marker — `DONE`

Both map controllers refuse to move the marker once one exists
(`MapLibreMapController.kt:262`, `GoogleMapController.kt:248` — `if
(destinationMarker != null) return`), and only `stopNavigation` clears it
(`MainActivity.kt:1478`). Choosing a new destination doesn't go through
`stopNavigation`: `onDestinationSelected` cancels the job and calls
`startActiveNavigation` directly. The route polyline *is* cleared per route
version, so the map ends up showing the new route with the previous drive's
destination pin still on it.

**Fix:** clear the destination marker when a new destination is chosen, and let
`showDestinationMarker` move an existing marker rather than ignoring the call.

---

## Tier 2 — silent failure

### B4 · The `JSONException` sweep round 1 deferred — `DONE`

Banked from A10. `PlacesApiService:232`, `GeocodingApiService:163`,
`GooglePlacesApiService:214` and `GoogleGeocodingApiService:116` parse the
response body inside a `try` that catches only `IOException`, so a truncated or
unexpected body throws `JSONException` at the caller instead of failing as
"empty". `RoutingApiService` and `GoogleRoutingApiService` have a broad catch,
but only around an inner error-message parse — the route parse itself is
outside it.

`WikipediaApiService` and (since A10) `NearbyCityApiService` do it right, so the
pattern to copy is already in the tree. Each caller handles failure
differently, which is why this wants one deliberate pass rather than the
drive-by A10 declined to make.

### B5 · A failed tour leaves its geofences registered — `DONE`

Banked from A4 and A7/A8. `stopTourMode` returns early unless
`_serviceState.value.isRunning` (`TourModeService.kt:427`), and `Error` is not
running. A tour that dies mid-flight — `startTourMode`'s catch sets `Error` and
calls `stopSelf` — therefore skips every cleanup line below that guard:
registered geofences, the location listener, the content queue and the session
all survive the service. The geofence receiver then revives the service on the
next crossing (A4's `GEOFENCE` command deliberately does that), so a broken tour
comes back from the dead.

**Fix:** separate "release the tour's resources" from "stop a running tour", and
run the release unconditionally from `onDestroy` and the error path.

---

## Tier 3 — housekeeping

### B6 · Lint's remaining warning groups — `DONE`

Banked from A18. 78 warnings, no errors: 22 `UseTomlInstead` (hardcoded
dependency coordinates that belong in the version catalog), 22
`GradleDependency` and 11 `NewerVersionAvailable` (upgrades — these want release
notes read, not numbers bumped), then small groups: 3 `UseKtx`, 3
`UnusedResources`, 2 `Autofill`, 2 `NotifyDataSetChanged`, 2 `Overdraw`, 2
`ButtonStyle`, and one each of `RtlEnabled`, `RelativeOverlap`, `ButtonOrder`,
`UseCompoundDrawables`, `OldTargetApi`, `ObsoleteSdkInt`.

**Fix:** the catalog move and the small groups are mechanical and worth doing;
the dependency upgrades should be a separate deliberate pass.

### B7 · The two biggest files kept growing — `DONE`

`MainActivity` (1512 lines) now holds map camera work, the navigation UI state
machine, service binding, ads, permissions, voice reporting and the tour
lifecycle. `TourModeService` (1287) holds notifications, geofence and proximity
handling, discovery, the way-of-life watcher and the delivery host.

Round 1's repeated lesson was that logic is untestable because it sits inside an
Android component, and these two are the largest remaining instances. A19's
`Host`-interface seam worked cleanly for the delivery loop; the same shape fits
the navigation UI state machine (`NavState`, the prompt gating, the camera
decisions) and the service's discovery/notification split.

**Fix:** not one change. The next candidates, in order of value: the way-of-life
watcher (its policy is already pure, only the orchestration isn't), then the
navigation status/instruction handling in `MainActivity`.

### B8 · The dependency and target-SDK upgrade pass — `DONE`

Opened by B6, which deliberately left it: after the catalog move and the small
groups, all 36 remaining lint warnings are version upgrades, and none of them
is a number to bump without reading something first.

- **21 `GradleDependency` · 12 `NewerVersionAvailable`** — now nearly all on
  `libs.versions.toml`, one place, which is the point of the move. The
  interesting ones are not the point releases: OkHttp 4.12 → **5.4** is a major
  version, Kotlin coroutines 1.7.3 → **1.11** crosses several minors under a
  Kotlin version this project pins, lifecycle 2.7 → **2.11**, Room 2.6.1 →
  newer (a Room upgrade regenerates schemas — see A14), Hilt 2.56.2 → 2.60.1
  (plugin and artifacts move together now).
- **2 `AndroidGradlePluginVersion`** — Gradle 8.13 → 8.14.5, and AGP 8.13.2 →
  **9.3.1**, a major with its own migration guide.
- **1 `OldTargetApi`** — `targetSdk = 35`, latest is 36. This is the one that
  is *not* housekeeping at all: raising `targetSdk` opts the app into new
  platform behaviour, and this app runs a foreground location service, posts
  notifications, and draws over a map with system-bar insets — three of the
  areas that change most between releases.

**Fix:** one dependency at a time or in small related groups, each with the
release notes read and the gradle gate run, and `targetSdk` last and on its
own. Not a single commit.

### B9 · `MainActivity`'s navigation state machine — `TODO`

Opened by B7, which named two extractions and did the first. `MainActivity` is
**1521 lines** and the largest thing in it is the navigation UI: `NavState`,
the prompt gating, the camera decisions, the status/instruction handling, and
the ETA and distance formatting, spread across the activity's callbacks.

The pieces of policy are already pure and tested (`VoicePromptGate`,
`RouteProgress`, `CameraLogic`, `DistanceFormatter`). What isn't testable is
the orchestration between them — which state a status update moves the UI to,
and what that means for the camera, the card and the voice.

**Fix:** the same seam as A19 and B7 — a `NavigationPresenter` holding
`NavState` and the transitions, with a `Host` for the views and the map. The
activity keeps the view wiring and nothing else about navigation.

### B10 · Everything behind the AGP 9 wall — `TODO`

Opened by B8, which took every upgrade the current toolchain accepts and found
that the rest are not independent bumps at all — they are one migration wearing
eleven hats. The 19 warnings that remain:

- **11 blocked by AGP 9 outright.** `androidx.core:core:1.19.0` fails
  `checkDebugAarMetadata` with "requires Android Gradle plugin 9.1.0 or higher"
  and compileSdk 37; Hilt's Gradle plugin 2.60.1 refuses to *apply* below AGP
  9.0.0. Between them that pins core-ktx, activity, lifecycle (x4), Room (x2),
  datastore and Hilt (x3) at the versions B8 landed.
- **Room 2.8.4 specifically** also needs a newer kotlinx-serialization than
  Kotlin 2.1.0's KSP puts on the annotation-processor classpath — it dies with
  an `AbstractMethodError` inside `FieldBundle$$serializer`.
- **Kotlin 2.1.0 → 2.4.10**, which drags KSP with it (`ksp` is pinned to the
  Kotlin version by construction) and is what Room 2.8 is really waiting for.
- **AGP 8.13.2 → 9.3.1**, the major everything above depends on.
- **OkHttp 4.12 → 5.4**, the only one that needs *code* changed: `Response.body`
  is non-null in 5.x and all six API services read `response.body?.string()`.
- **Three Play Services majors** — maps 19→20, ads 24→25, UMP 3→4 — over the
  ads and consent flow, which is the part of this app that cannot be verified
  without a device and a real AdMob account.
- **`targetSdk` 35 → 36**, still last and still on its own.

**Fix:** AGP 9 and Kotlin 2.4 first, together, because nothing else moves until
they do. Then the AndroidX and Hilt versions fall out for free. OkHttp 5 and the
Play Services majors are separate reading each. `targetSdk` last.

---

## Round 2 progress log

Newest last.

### Round 2 opened — audit recorded

Read the map controllers, the UI sheets, the preferences layer, the Gemini and
ads code, and re-read round 1's own changes. Found three new problems (B1–B3),
confirmed the three findings round 1 banked (B4–B6), and recorded the file-size
regression round 1 caused (B7). No code changes in this entry.

Also checked and found *nothing* to report in: `GeminiApiService` (bounded
timeout, catches broadly, validates its own output before speaking it),
`ConsentManager` (defaults to non-personalized, every path calls back), and the
four `!!` sites in the tree (three are the standard view-binding idiom, one is
guarded by a `while (remaining.isNotEmpty())`).

### B1 — "Survive a preferences file we can't read"

Three layers, because the file is read before there is any UI to report to:

- **The store replaces a corrupt file** rather than throwing at whoever opens
  it: `corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }`.
- **Reads absorb the rest.** All seven read flows now come off one
  `storedPreferences` that `.catch`es and emits `emptyPreferences()`, so an
  `IOException` costs the user their settings rather than the app.
- **Writes absorb their own failures.** All seven `edit` sites go through a
  `save` helper that logs an `IOException` instead of letting it out. They are
  called from `viewModelScope` and the service's scope with no try/catch above
  them, so a failed write was an uncaught exception looking for somewhere to
  crash.

The mapping from stored values to settings moved into
`toUserPreferences(Preferences)` in the companion, which is what makes the
claim testable: "nothing stored" now has a test asserting the result is the
shipping defaults and, specifically, a tour that can still speak — an empty file
answered with a *zeroed* configuration would be a silent guide, which is the
failure this fix exists to avoid.

**Left as it is, deliberately:** `GPSTrackerApplication` still seeds the map
provider and account tier with `runBlocking { first() }`. It is a blocking
main-thread read, but a load-bearing one — `MainActivity.onCreate` picks its map
controller from the provider, so an activity that started before the answer
arrived would build the wrong map. What made it dangerous was that it could
throw; it no longer can.

Tests: 2 new cases in `UserPreferencesRepositoryTest` (384 total, 0 failures).
The corruption handler itself is DataStore's, and exercising it needs a real
file and an instrumented test.

### B2 — "Read the detail level like every other stored enum"

`UserPreferences.DetailLevel` gained a `fromStorage` companion function, copied
from `AccountTier.fromStorage` and `MapProvider.fromStorage` line for line —
`entries.firstOrNull { it.name == name } ?: MEDIUM`. The repository calls it
instead of `valueOf`, so an unrecognized value costs the user their detail
setting rather than failing `userPreferencesFlow` for every collector.

**I checked the other three `valueOf` sites while here, and all were already
guarded**: `parseCategories` and `TourContentEntity.toDomainModel` catch
`IllegalArgumentException`, and `TourLogic.contentPriorityFor` wraps its category
lookup the same way. The detail level was the only gap, which is the shape of
this bug — a convention followed everywhere except once.

Tests: 2 new cases (386 total, 0 failures) — an unrecognized value falling back
to `MEDIUM`, and every real level surviving a round trip through storage, so the
fallback can't quietly swallow a valid one.

### B3 — "Point the destination pin at the destination"

Fixed at both ends, because either alone leaves a case standing:

- **The controllers move the pin** instead of returning early when one exists.
  Google's marker takes a new `position`; MapLibre's is replaced, matching how
  that file already clears it. `showDestinationMarker` is now what its name
  says — set the pin here — rather than "set it if there isn't one".
- **A new destination clears the old pin** in `startActiveNavigation`, beside
  the voice-gate reset. This is the case the controller fix alone misses: if the
  new destination fails to route, nothing draws, and the previous drive's pin
  would sit there over a route that doesn't exist.

The `"Destination"` marker title was hardcoded English in both controllers,
which is the same class of thing A20 fixed for the notification channels, so it
went to `strings.xml` while the line was open.

No new tests: this is call ordering between an activity and two map SDKs, with
no pure decision to pin down. `lintDebug` and both variants still build, and the
change is small enough to read in full — but it *is* the kind of fix that only a
device confirms, and I haven't run one.

### B4 — "Let every API service fail the way its callers expect"

Six services, three contracts, one shape: each parser gained a sibling that
catches `JSONException` and answers in the caller's own currency.

| Service | Contract | Guarded door |
| --- | --- | --- |
| `PlacesApiService` | throws `IOException` | `parseElementsResponseOrThrow` |
| `GooglePlacesApiService` | throws `IOException` | `parseNearbyResponseOrThrow`, `parsePlaceDetailsOrThrow` |
| `GeocodingApiService` | empty on failure | `parseSearchResponseOrEmpty` |
| `GoogleGeocodingApiService` | empty on failure | `parseSearchResponseOrEmpty` |
| `RoutingApiService` | null on failure | `parseRouteResponseOrNull` |
| `GoogleRoutingApiService` | null on failure | `parseRouteResponseOrNull` |

The raw parsers still throw, which is right — they're the strict readers, and
the tests that assert what a well-formed body means still call them directly.
What changed is that no service hands a `JSONException` to a caller that never
named one.

**The severity is not evenly spread, and the two geocoders are the real bug.**
`search` promises "empty on failure", and both search sheets take it at its
word: `DestinationSearchBottomSheet` and `TakeATourBottomSheet` launch it into
a `lifecycleScope` with no `try` anywhere above it. A `JSONException` there was
not a failed search, it was the app closing while the user typed. Everything
else in the sweep was a *misreported* failure rather than a crash: the two
Places services' callers all catch `Exception` broadly, so a malformed body
already degraded to `Result.failure` or an empty flow — but it reached
`ErrorMessages.friendlyMessage` as an unrecognized throwable and came out
"Please try again", where an `IOException` says "no connection, check your
internet". That mapping is the reason the wrapper throws `IOException` rather
than something new.

**The audit was wrong about the two routing services and I'm correcting the
record.** B4 says their broad catch sits "only around an inner error-message
parse — the route parse itself is outside it". It isn't: `parseRouteResponse`
is called inside the same `try` as the request, and `catch (e: Exception)`
below it has been catching it all along. So there was no bug here, only a bad
log line — a malformed route body was already null, just logged as "Unexpected
error getting route", which is the wrong word for the ordinary behaviour of a
free public server under load and the only thing anyone has to go on when a
route silently doesn't appear. Both now name it. The broad catch stays in both,
with a comment saying what it's still for: `parseRouteResponse` can also trip
over a numeric field that isn't numeric (`"duration": "1234s"` goes through
`toDouble`) and on `coerceIn(0, shape.size - 1)` when a leg's shape decodes to
nothing — neither is a `JSONException`.

**Why `JSONException` and not `Exception`:** a blanket catch in the guarded
door would swallow programming errors in the parsers themselves, which are
where the domain mapping lives. I checked each parser for non-JSON failure
modes reachable from a bad body, and the two geocoders — the two with no net
above them — have none: every field they read goes through `optString` or
`getDouble`, and `getDouble` throws `JSONException`.

The `catch (e: Exception)` around each routing service's *error-message* parse
narrowed to `JSONException` too. No behaviour change (anything else still lands
in the outer catch), but it now says what can actually go wrong on that line.

Tests: 12 new cases across the six test files (398 total, 0 failures). Two per
service, and the second one is the one that matters — a guard that answers
"empty"/"null"/"throw" for *everything* would pass the first test and fail the
second, so each pair pins down both that a body we can't read fails per
contract and that a body we can still parses through the same door. The
malformed inputs are the two real ones: an HTML error page returned with HTTP
200 (Overpass does this when overloaded) and a single field missing from an
otherwise valid body.

**Not covered:** the catch blocks themselves — the `try` in `search`,
`runQuery`, `execute` and `getRoute` — still need an HTTP client to exercise,
and there's no MockWebServer in the test setup. The guarded parse *is* the
decision, and it's pure; what remains untested is the one-line call site that
uses it.

### B5 — "Release the tour on every path that ends one"

`stopTourMode` was two jobs in one function: *let go of what the tour holds*
and *stop the running tour*. The `isRunning` guard belonged to the second and
was blocking the first. Split into three:

- **`releaseTourResources()`** — the cleanup, no guard, safe in any state and
  any number of times.
- **`endTour(finalState)`** — release, then set the state, then leave the
  foreground and stop the service. `Error` is as terminal as `Inactive`; the
  state is the only difference between them.
- **`stopTourMode()`** — now one line: `endTour(Inactive)`.

Every path that ends a tour goes through one of them. There are five, and the
release used to reach only the first:

| Path | Before | Now |
| --- | --- | --- |
| STOP command (the Stop button) | released | `endTour(Inactive)` |
| `startTourMode` catch | `Error` + `stopSelf`, nothing released | `endTour(Error)` |
| proximity flow `.catch` | `Error` only — didn't even `stopSelf` | `endTour(Error)` |
| `onDestroy` | called `stopTourMode`, which returned early unless running | `releaseTourResources()` |
| `TourCommand.NONE` → `stopSelf` | via `onDestroy` | via `onDestroy` |

**What the leak actually was.** The audit called it "geofences stay
registered", which is close but names the wrong object. There are no platform
geofences — A16's move off Play Services made them manual, derived per fix
inside `startProximityMonitoring`'s location listener. So the thing that
survives a failed tour is *the listener*, and the listener is what calls
`notifyGeofenceTransition`, which calls `startForegroundService`. That is the
revival loop: dead tour → live listener → transition → service starts →
`TourCommand.GEOFENCE` sees no running tour → `startTourMode()` → and if that
start is failing for a reason that persists, round and round.

**A second, worse bug in the same lines, which the audit missed.** The release
handed the unregister call to a coroutine:

```kotlin
serviceScope.launch { locationAwarenessService.unregisterAllPointsOfInterest() }
```

and `onDestroy` ran `serviceScope.cancel()` on the line after. The scope is
`Dispatchers.Default`, so the launch had not started yet — it was cancelled
before it ran, **every time**, on every normal destroy, not just the failure
paths. The monitored places therefore outlived every tour that ended by the
service going away.

The fix is that `unregisterAllPointsOfInterest` is no longer `suspend`. It
never did anything suspending — four in-memory clears and a
`ProximityAlertGate.reset()` — so the `suspend` was decoration, and decoration
that forced the release into a coroutine that couldn't survive its caller. The
interface says why in a comment now, and `releaseTourResources` carries the
requirement that made it necessary: **nothing in it may suspend.**

**Deliberately not fixed:** `unregisterPointOfInterest` (the single-place
sibling) is still `suspend` and equally fake, but it has no callers anywhere in
the tree — dead API, which is A16's kind of task, not this one.

**No new tests, and this time the tests already existed.** The two facts this
bug is made of are both pinned in the tree already: `a failed tour is not
running` (`TourModeStateTest`, from A2) and `stopping a tour that has already
ended is nothing to do` (`TourCommandTest`, from A4). Read together they
*prove* it — the state says the tour isn't running, so the guard skipped the
cleanup, and the Stop button couldn't reach it either because a stop request on
a non-running tour maps to `NONE`. What was missing was never a test; it was
the release. The fix itself is service lifecycle wiring with no pure decision
in it, and `TourModeService` can't be instantiated in a JVM unit test — which
is exactly B7's complaint about that file, and one more reason to do it.

398 tests, 0 failures; `lintDebug` still 0 errors and 78 warnings. Verified by
reading every caller, not by running a tour on a device.

### B6 — "Clear lint's fixable warnings and move the deps to the catalog"

**78 warnings → 36, still 0 errors.** Everything that remains is a version
upgrade, which B6 said should be its own pass; that pass is now **B8** rather
than a loose end.

**The catalog move (22 `UseTomlInstead`).** Every hardcoded coordinate in
`app/build.gradle.kts` moved to `libs.versions.toml`, and the two plugin
versions hardcoded in the root build file went with them. Two things fell out
that make this more than tidying:

- **`hilt = "2.56.2"` is now one version ref** shared by the plugin and both
  artifacts. They have to match; before, they matched by coincidence in two
  different files.
- **appcompat was declared twice and disagreed.** The catalog said 1.7.0, the
  build file said 1.6.1, and the build file won — which is exactly what lint's
  message was telling us ("already available as `androidx-appcompat`, but using
  version 1.6.1"). Using the alias takes 1.7.0. That is a real dependency
  change and the only version this task moves.

**The small groups.** Each was a genuine fix rather than a suppression, except
one:

| Warning | What it was | What it is now |
| --- | --- | --- |
| `NotifyDataSetChanged` ×2 | two adapters rebuilding every visible row per keystroke | one shared `ListAdapter` with a `DiffUtil` callback |
| `UnusedResources` ×3 | `R.color.black`/`white` unused; `ic_launcher_round` unreferenced | colors deleted; the manifest now declares `android:roundIcon` |
| `UseKtx` ×3 | `visibility == VISIBLE`, `Uri.parse`, `Bitmap.createBitmap` | `isVisible`, `String.toUri`, `createBitmap` |
| `Overdraw` ×2 | list rows painting `selectableItemBackground` as a background | the ripple moved to `android:foreground` |
| `Autofill` ×2 | two search boxes with no `autofillHints` | `importantForAutofill="no"` — a place search isn't a form field |
| `RelativeOverlap` | the turn-card title could run under the close button | bounded with `layout_toStartOf` + `ellipsize` |
| `UseCompoundDrawables` | search bar = LinearLayout + ImageView + TextView | one `TextView` with `app:drawableStartCompat` |
| `ObsoleteSdkInt` | `SDK_INT >= M` branch, minSdk 24 | branch and its deprecated fallback deleted |
| `SetTextI18n` | `"$eta • ${…}"` concatenated into `setText` | `navigation_eta_and_distance` string resource |
| `RtlEnabled` | manifest never declared RTL either way | `android:supportsRtl="true"` |
| `ButtonOrder` | nav card had Start \| Cancel | Cancel \| Start |
| `ButtonStyle` ×2 | wants borderless button-bar buttons | **suppressed**, with the reason in the layout |

Three of those deserve more than a table row:

- **The two search adapters were byte-identical**, class name aside — same item
  layout, same holder, same binding, same `notifyDataSetChanged`. So the fix
  for the lint warning and the fix for the duplication are the same edit: one
  `SearchResultAdapter` in `ui/adapter/`, used by both sheets. `ListAdapter`
  also means a refined query keeps the rows it already had instead of rebinding
  the visible list on every response, which is the difference between a list
  that settles and a list that flickers while you type.
- **`ButtonStyle` is the one I refused.** Lint wants
  `?android:attr/buttonBarButtonStyle` on the second button of each pair. These
  are Material filled/outlined pairs, not AlertDialog button bars — borderless
  would leave "Start Navigation" and "Save" looking like links. Suppressed at
  the container with the reasoning written next to it, which is the honest form
  of "lint is wrong here".
- **`ButtonOrder` is the one that changes what the user sees.** Lint is right
  and the app was already inconsistent with itself: the settings sheet had
  Cancel on the left, the navigation card had it on the right. They agree now,
  and they agree with the platform.

`assembleRelease` was run as well as the usual gate, because this task touched
resources under `isShrinkResources` and changed a dependency version: R8 and
the resource shrinker both still pass. 398 tests, 0 failures.

**Untested by anything but the compiler:** every layout change. No unit test can
see a compound drawable render or a ripple draw in a foreground, and I haven't
run a device. The riskiest of them is the search bar — it went from three views
to one, and `app:drawableStartCompat` relies on the activity inflating that
`TextView` as an `AppCompatTextView`, which an `AppCompatActivity` does.

### B7 — "Lift the quiet-stretch filler out of the service"

`WayOfLifeWatcher` now holds the filler: the `watch()` timer, the whole
`maybeSpeak` decision, and the two fields that were only ever the filler's —
when the last segment played, and how many region lookups in a row came back
empty. `TourModeService` keeps a `Host` adapter and the job it runs in.

The seam is A19's, deliberately: constructor dependencies for the interfaces
(`TourSession`, `ContentService`, `AudioService`), a `Host` for everything that
is really the service (settings, the fact card, where the listener is, when the
guide last spoke). Two departures from that template, both forced by what is on
the other side:

- **`lastSpokenAt` stays in the service.** Every narration path writes it — the
  tour's greeting, a corridor announcement, a delivered story — so the filler
  reads it through the Host (`lastSpokenAtMillis`) and reports back through
  `onSpoke()`. Moving it would have given one of five writers custody of it.
- **The region lookup is on the `Host`, not the constructor.**
  `NearbyCityApiService` is a concrete final class over OkHttp; a watcher that
  takes one cannot be given a stand-in for it. So the Host answers
  `regionsNear(location, radiusMeters)` and the watcher keeps the radius and
  the "nearest one" choice, which are the parts that are policy.

**`maybeSpeak(nowMillis)` takes the clock as an argument** rather than reading
it. That single signature change is what makes a four-minute silence, a
ten-minute cooldown and an hour-long backoff describable in a test instead of
waitable in a car.

Tests: 13 new cases (411 total, 0 failures) plus the two fakes moved to a
shared `TourServiceFakes.kt` — `NarrationDeliveryTest` had its own copies, and
A20 already showed what that costs: adding `isPlaying` to `AudioService` broke
one fake and would have broken both. The new cases cover what nothing could
reach before: a quiet stretch earning a segment, a queued sight outranking one,
**a sight arriving during the region lookup** (the second busy check, which
exists precisely because a geofence can fire while the network call is in
flight), the empty-country backoff doubling instead of re-POSTing every 30
seconds, a region already covered this tour, an undocumented region, highway
speed shortening the segment, a cap of zero muting it, a parked car, no fix,
and `reset()` letting the next tour start over.

**One wart the extraction made visible, and I left it alone.** The region is
claimed with `markRegionNarrated` *before* the content fetch and the second
busy check — so a sight interrupting a lookup costs that region for the rest of
the tour, silently. The test asserts the current behaviour and says in a
comment that it is pinned rather than endorsed: `markRegionNarrated` doubles as
the "don't retry this region" guard, and splitting the two is a behaviour
change, not a refactor. It is now a change someone can make with a test to
catch them.

**The line count barely moved: 1287 → 1228.** 137 lines of filler left, 45
lines of `Host` adapter arrived. That is the honest arithmetic of this kind of
extraction, and it is not the point — the point is that the filler's decisions
are now reachable without a foreground service, and 13 of them are pinned. The
same will be true of B9, which is the second extraction B7 named and which
still has `MainActivity` at 1521 lines.

### B8 — "Take every upgrade the toolchain allows, and name the wall"

**36 lint warnings → 19**, in four commits, each verified with the full gate
before the next one started:

| Commit | What moved |
| --- | --- |
| `Compile against API 36 and take the AndroidX upgrades AGP 8 allows` | compileSdk 36; core-ktx 1.16.0, activity 1.10.1, lifecycle 2.9.4, datastore 1.1.7, appcompat 1.7.1, constraintlayout 2.2.1, recyclerview 1.4.0 |
| `Move Room to 2.7.2 and drop the merged room-ktx artifact` | Room 2.6.1 → 2.7.2, one dependency deleted |
| `Upgrade the third-party libraries the toolchain accepts` | Material 1.14.0, MapLibre 13.4.1, Gson 2.14.0, desugar 2.1.5, coroutines 1.11.0, Hilt 2.57.2 |
| `Upgrade the test tooling and the Gradle wrapper` | Gradle 8.14.5; ext-junit 1.3.0, Espresso 3.7.0, Mockito 5.23.0, org.json 20260719 |

**The finding that shaped the whole task: most of these are not independent.**
Bumping each library to what lint asks for fails, and fails informatively:
`androidx.core:core:1.19.0` requires compileSdk 37 *and AGP 9.1.0*, and Hilt's
2.60.1 plugin refuses to apply under AGP 8 at all. So eleven of the warnings
are one migration — AGP 9 plus Kotlin 2.4 — and treating them as eleven bumps
would have meant eleven failed attempts. They are now **B10**, described as the
single thing they are.

**compileSdk 36 went in; `targetSdk` stayed at 35.** Worth saying plainly
because the two get conflated: `compileSdk` is what the code is compiled
against and changes nothing at runtime, and raising it is what let the AndroidX
versions move at all. `targetSdk` is what the platform uses to decide how to
treat the app, and this app runs a foreground location service, posts
notifications and draws under the system bars.

**Release notes read, not skipped**, for the four upgrades where a break would
be silent rather than a compile error:

- **lifecycle 2.9** makes `Lifecycle.DESTROYED` terminal — an
  `IllegalStateException` for anything reusing a destroyed owner. Nothing here
  does.
- **activity 1.9–1.10**'s automatic changes (edge-to-edge, predictive back)
  apply only to apps calling `enableEdgeToEdge()` or registering an
  `OnBackPressedCallback`. This app does neither; it applies insets by hand.
- **Room 2.7** merged `room-ktx` into `room-runtime` (so a dependency is gone,
  not stale) and switched KSP to generating **Kotlin** rather than Java.
  `AppDatabase_Impl` is a `.kt` file now. The restrictions that come with
  Kotlin codegen — no nullable collection return types, no abstract properties
  as DAO getters — are ones the DAOs already satisfied, and **the committed
  schema JSON came out byte-for-byte identical**, which is the only thing that
  really matters: A14 exists because those files are what a future migration
  validates against.
- **Material 1.13/1.14** put their work into Material 3 Expressive styles and
  new components. This app's theme extends `Theme.MaterialComponents.DayNight`,
  the Material 2 line, which got bug fixes and accessibility work. It was the
  one visual risk in the pass and the one I checked hardest.

**Room 2.8.4 was attempted and rolled back**, not skipped: its compiler
serialises schema bundles with kotlinx-serialization and throws
`AbstractMethodError: FieldBundle$$serializer ... typeParametersSerializers()`
against the runtime Kotlin 2.1.0's KSP supplies. Forcing a newer serialization
artifact onto the `ksp` configuration would have papered over a toolchain
mismatch to satisfy a version number, so 2.7.2 is where Room stops until
Kotlin moves.

Tests: **411, 0 failures**, unchanged — no new ones, and that is the honest
answer here. A dependency upgrade's test is the existing suite, and this suite
exercises the parts these libraries touch: Room migrations, the coroutine-heavy
delivery and filler loops, the DataStore-backed preferences, the org.json
parsers. `assembleRelease` was run at the end as well, since R8 sees every one
of these jars.

**What no gate here can see:** the app was not run. Material's rendering,
MapLibre 13.4's map, the Play Services libraries and anything about how the UI
looks are unverified by anything but the compiler.
