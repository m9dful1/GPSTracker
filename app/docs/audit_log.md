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

### B9 · `MainActivity`'s navigation state machine — `DONE`

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

### B10 · Everything behind the AGP 9 wall — `DONE`

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

### B11 · `targetSdk` 35 → 36 — `TODO`

The last lint warning in the project, and the one change in this whole audit
whose effects are invisible to every gate available here. `compileSdk` is 37
and every dependency is current; only `targetSdk = 35` remains.

Raising it opts the app into Android 16's behaviour changes, and this app sits
in the middle of the ones that matter: a **foreground location service**,
**posted notifications**, and a **full-screen map drawn under the system bars**
with insets applied by hand. Nothing in the build, the 434 unit tests, lint or
R8 can tell anyone whether a service still starts, a notification still shows,
or the map still clears the status bar.

**Fix:** change the one line, then run it. What to check on a device, in this
order — start a tour and confirm the foreground notification appears and the
service survives leaving the app; confirm narration still plays; drive a route
and confirm the turn card and the nav card are not under the status bar or
gesture inset; confirm the back gesture still leaves the activity cleanly
(Android 16 turns predictive back on by default, and this app registers no
`OnBackPressedCallback`); confirm the consent dialog and a test ad still
appear. Anything that fails is a real finding for round 3.

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

### B9 — "Give the navigation state machine somewhere to live"

`NavigationPresenter` now owns the `NONE → PREVIEW → GUIDING → NONE` machine
and every decision that hangs off it: which button shows and what the other one
says, whether the card reads "Navigating to" or "Route to", whether a turn card
appears, whether anything is spoken and in what words, whether the camera
follows, and whether a new route becomes a narration corridor. The
`VoicePromptGate` moved inside it, because "has this already been said" is part
of "should this be said".

**It is not shaped like A19's or B7's host interface, deliberately.** Those own
a loop: they call out to the service repeatedly and asynchronously, so an
interface to call *back* through is the only way to test them. This owns no
loop — `MainActivity` collects the status flow and asks what each update means.
So the presenter answers with data (`Buttons`, `RouteCard`, `Eta`, a prompt
string or null) and the activity renders it. That needs no fakes at all, and it
leaves the order of the view calls exactly where it was, which matters when the
change can't be run on a device. Copying the `Host` shape here would have been
pattern-matching, not design.

**The split runs along the resources line.** The presenter decomposes; the
activity words. `Eta.Remaining(hours, minutes, arrivalAtMillis)` is a fact about
the drive and is tested; turning it into "1 h 30 min (4:15 PM)" needs
`getString` and the device's own 12-or-24-hour setting, so it stays in the
activity. Same for the destination line: the presenter says `guiding = true`,
the activity picks the string.

Tests: 18 new cases (429 total, 0 failures) — the phases and their illegal
transitions (Start without a preview does nothing, and says so rather than
half-starting), a preview showing no turn card and saying nothing, the two
announced timings and the three silent ones, the same turn suppressed at the
same distance but announced again when it gets closer, **arrival spoken once
however many times a parked car reports it**, a new drive forgetting all of it,
and the ETA arithmetic including the progress bar's horizon.

**One thing the extraction made explicit rather than changed:** `ADVANCE`
timing puts a card on screen but is never spoken. That was true before — the
activity only spoke `IMMEDIATE` and `APPROACHING`, so the `ADVANCE` branch in
the old `formatInstructionForVoice` was unreachable. `promptFor` now returns
null for it with the reason written down, and the wording branch is kept
because it is the same sentence `APPROACHING` uses.

`MainActivity` is **1521 → 1460 lines**, and the presenter is 227. As in B7,
the line count is not the win — the win is that the navigation state machine
can be interrogated without a car.

### B10 — "Take the wall down, in the order it had to come down"

**19 lint warnings → 1**, in five commits. The one left is `targetSdk`, which
is now **B11** and needs a device, not a build.

| Commit | What moved |
| --- | --- |
| `Move onto AGP 9, Gradle 9.5 and AGP's built-in Kotlin` | AGP 9.3.1, Gradle 9.5, Kotlin 2.3.10 via AGP, KSP 2.3.10, Hilt 2.60.1 |
| `Take the eleven upgrades that were waiting on AGP 9` | compileSdk 37; core-ktx 1.19.0, activity 1.13.0, lifecycle 2.11.0, datastore 1.2.1, Room 2.8.4 |
| `Move to OkHttp 5, and test it over a real socket` | OkHttp 5.4.0 + MockWebServer, 5 new tests |
| `Upgrade Play Services and the Gradle wrapper` | Gradle 9.6.1, Maps 20.0.0, Ads 25.4.0, UMP 4.0.0 |

**AGP 9 is not a version bump, it is a change of who compiles Kotlin.** It
brings built-in Kotlin support enabled by default, and that cannot sit beside
`org.jetbrains.kotlin.android`: the first attempt failed casting AGP's new
extension to the old `BaseExtension`, and once Kotlin was new enough to
recognise the situation it said so outright — "not compatible with AGP's 9.0
new DSL". The documented opt-out (`android.builtInKotlin=false`) does not help,
because the new DSL is the part that collides. So the Kotlin plugin is gone
from both build files and the catalog, `kotlinOptions` moved to
`kotlin.compilerOptions`, and `jvmTarget` is inherited from
`compileOptions.targetCompatibility` instead of being repeated.

**Kotlin stops at 2.3.10, not the 2.4.10 lint asks for.** KSP is how this
project processes Room and Hilt, and KSP's newest release is 2.3.10. Lint
reports the Kotlin compiler's latest version without knowing what else in the
build depends on it.

**Hilt had to move in the same commit, not after it.** 2.57.2 fails to apply
under AGP 9 ("Android BaseExtension not found") and 2.60.1 is the version that
*requires* AGP 9. There is no ordering in which they are two changes.

**Then everything else fell out, exactly as B10 predicted.** compileSdk 37 and
five AndroidX upgrades landed in one commit with nothing to fix — including
Room 2.8.4, whose `AbstractMethodError` in `FieldBundle$$serializer` was the
old KSP's kotlinx-serialization runtime all along. **The committed schema JSON
is unchanged for the third time**, which is the check that matters for Room.

**OkHttp 5 compiled unchanged — so I went and got some evidence.** The app uses
a handful of stable APIs and none of them moved, but "it compiled" is not a
test of the library every feature in this app talks through. OkHttp ships
MockWebServer, so `GeocodingOverHttpTest` now drives a geocoder over a real
socket: a well-formed response with the query, language and bias checked on the
way out; an **HTML error page served with HTTP 200**, which is what a public
geocoder actually does when it is unhappy; an error status; a refused
connection; and a blank query that never reaches the network. That also closes
a hole B4 named out loud — it gave every API service a guarded parse and said
plainly that the `try` around the request stayed unexercised for want of a test
server.

**The Play Services majors turned out to be version changes, not migrations.**
Maps 20, Ads 25 and UMP 4 all compile against the map controller, the ad
managers and the consent flow untouched. What that does *not* establish is
whether a consent dialog still appears or an ad still fills, and nothing here
can. That part of this app has never been verifiable in this environment and
still isn't.

Tests: **434, 0 failures** (5 new). Every commit ran `assembleDebug`,
`testDebugUnitTest`, `compileReleaseKotlin`, `lintDebug` and `assembleRelease`
— R8 and the resource shrinker included, because a toolchain change is exactly
where they break.

---

# Round 3

A third pass, after round 2's eleven tasks. The reading went where rounds 1 and
2 spent least time: the narration pipeline (`ContentServiceImpl`), the TTS
engine (`AudioServiceImpl`), the Room content cache, and the compiler's own
warnings — which are now worth listening to, because there are only 34 of them
and 24 are one library's deprecations.

## Baseline at round 3

- **434 unit tests**, 0 failures (382 at the start of round 2).
- `lintDebug` **1 warning**, 0 errors (78 at the start of round 2).
- 34 compiler warnings, of which 24 are MapLibre's deprecated annotation API.
- AGP 9.3.1, Gradle 9.6.1, Kotlin 2.3.10 (compiled by AGP), KSP 2.3.10,
  compileSdk 37, targetSdk 35.
- `MainActivity` 1460 lines, `TourModeService` 1228. 14.7k lines of source,
  5.5k of tests.

Round 2 ended with the project's dependencies current and its lint clean. What
it did not touch is the piece the whole app is *for*: the voice.

---

## Tier 1 — breaks in normal use

### C1 · Pausing during a turn prompt loses the story and silences the tour — `DONE`

`AudioServiceImpl` parks an interrupted narration in a single `pendingResume`
slot so a navigation prompt can speak over it and hand the floor back.
`pause()` writes to that same slot **unconditionally**:

```kotlin
override fun pause(): Boolean {
    synchronized(lock) {
        val active = current ?: return false
        if (isPaused) return false
        pendingResume = PendingNarration(active.text, lastSpeakingPosition, active.channel)
```

So when the thing being paused *is* a prompt, the parked narration is
overwritten and its channel is dropped without ever being closed. The sequence
is ordinary:

1. A story is playing. `current` = the narration.
2. A turn comes up. `speakPriority` parks the narration and speaks the prompt.
3. The listener taps pause on the notification — or a call arrives, and
   `AUDIOFOCUS_LOSS_TRANSIENT` calls `pause()` for them.

The story's flow is now referenced by nothing and is never closed, so
`NarrationDelivery`'s `collectLatest` never returns, so its
`finally { session.endDelivery(...) }` never runs. **`TourSession.isDelivering`
stays true for the rest of the tour**: no further story can be delivered (the
delivery gate is held) and the quiet-stretch filler sees `narrationBusy()`
forever. The guide goes permanently silent, and only stopping the tour clears
it.

Resume makes it worse in a smaller way: the prompt is restarted as
`isPrompt = false`, so it is treated as narration on completion.

**Fix:** pausing a prompt must not evict the narration behind it. Losing the
tail of "turn left in 200 feet" is fine; losing the story is not — and wedging
the delivery gate is worse than either.

---

## Tier 2 — untested where it matters

### C2 · The audio state machine is the last stateful piece with no tests — `DONE`

`AudioServiceImplTest` has 13 cases and every one of them tests a pure helper:
`resumeTextFrom`, `progressFraction`, `languageUsable`. The state machine those
helpers serve — `current`, `pendingResume`, `isPaused`, `lastSpeakingPosition`,
and the interleaving of `speak`, `speakPriority`, `pause`, `resume`, `stop`,
audio-focus callbacks and utterance callbacks — has **no coverage at all**.
C1 lives in exactly that gap.

This is round 1's lesson for the fourth time: the logic is untestable because
it sits inside an Android class. Except here it barely touches Android —
`TextToSpeech.speak/stop`, and the audio-focus request. Everything else is
bookkeeping, which is what `TourSession` already is for the tour.

**Fix:** the shape that worked three times already. Pull the utterance
bookkeeping into a pure class with the engine behind a small interface
(`speak(id, text)`, `stop()`, focus request/abandon), and write the
interleavings down as tests — starting with the one in C1.

### C3 · Room's destructive-migration escape hatch is deprecated — `DONE`

`AppDatabase` calls `.fallbackToDestructiveMigrationFrom(1)`, and Room 2.8
deprecates it in favour of an overload that makes you say whether *all* tables
are dropped. The distinction is the point: the old call drops only the tables
Room knows about.

This is A14's line of defence — version 1 predates schema export and is the
only version allowed to be rebuilt, everything later must migrate or fail
loudly. That reasoning is only as good as the call implementing it, and the
call is now deprecated with a behavioural question attached.

**Fix:** move to the explicit overload, and say in the comment which answer was
chosen and why.

---

## Tier 3 — housekeeping

### C4 · MapLibre's annotation API is deprecated out from under us — `WONTFIX`

24 of the project's 34 compiler warnings are one thing: `Marker`,
`MarkerOptions`, `Polyline`, `PolylineOptions`, `Polygon`, `PolygonOptions`,
`addMarker`, `removeMarker`, `setOnMarkerClickListener` — the whole annotation
API `MapLibreMapController` and `MarkerIcons` are built on. MapLibre replaced
it with style layers and sources.

This is not urgent — deprecated is not removed — but it is the largest single
block of warnings in the project, it is a rewrite rather than a rename, and it
gets harder the longer the POI-marker code grows against the old API.

**Fix:** a separate, deliberate pass over `MapLibreMapController`, moving
markers to a `SymbolLayer` with a `GeoJsonSource` and the route to a
`LineLayer`. Google's controller is unaffected; the `MapController` interface
is the seam that makes this a one-file change.

**`WONTFIX` — the fix above is the wrong fix, and the urgency was overstated.**
Two facts found on picking it up: the annotation API was deprecated in MapLibre
**7.0.0** and still ships in **13.4.1**, six major versions later with no
removal announced; and MapLibre's own replacement is the **Annotation Plugin**,
a separate artifact with `SymbolManager`/`LineManager`/`FillManager`, not the
hand-rolled style layers written above. Doing what this task says would have
been a from-scratch rewrite of the default map provider's rendering, verified
by nothing but a compiler, to replace a working API with one the library
doesn't recommend. The real work is **C6**; the warnings are suppressed with
their reasons in the meantime.

### C5 · `MainActivity` is still the biggest file — `DONE`

1460 lines, after B9 took the navigation state machine out. What is left is
still several jobs: map camera work, service binding, permissions, ads, the
tour lifecycle, the journal and settings sheets, and the location listener.

**Fix:** as before, one extraction at a time and only where a decision comes
with it. The camera work is the strongest candidate — `CameraLogic` is already
pure and tested, but *when* to drive, follow, or settle back is still spread
across four callbacks.

### C6 · Port MapLibre rendering to the Annotation Plugin — `TODO`

C4's replacement, with the right target. `org.maplibre.gl:android-plugin-annotation-v9`
provides `SymbolManager`, `LineManager` and `FillManager` — a close mapping to
what `MapLibreMapController` already does, with click handling built in.

**Why it isn't done yet:** every part of it fails invisibly. Managers bind to a
`Style`, and this app swaps styles six ways plus night mode — today's comment
"markers survive style swaps" stops being true, and everything drawn has to be
re-registered and re-populated on each load. Marker icons stop being `Icon`
objects and become images registered into the style by name, one per hue and
alpha. Marker taps stop being `setOnMarkerClickListener` and become feature
queries with their own hit radius. A mistake in any of those is an empty map,
and no unit test, lint run or R8 pass can see a pin.

**Fix:** with a device to hand. Port one thing at a time — the route line
first (no icons, no hit-testing), then the scout circle, then the POI markers
and their taps — launching between each. `MapController` is the seam, so
Google's controller and the whole activity stay untouched.

---

## Round 3 progress log

Newest last.

### Round 3 opened — audit recorded

Read `ContentServiceImpl`, `AudioServiceImpl`, the Room content cache and DAO,
and the compiler's warnings in full. Found one Tier 1 defect (C1), the untested
surface it hides in (C2), one deprecation on the data path (C3), and two
housekeeping items (C4, C5). No code changes in this entry.

**Checked and found nothing to report in:** `ContentServiceImpl` — the cache is
keyed by `poi_id` as its primary key, so the "regenerate for the other tier"
path really does replace rather than accumulate, `cleanForSpeech` unwraps
nested parentheticals innermost-first, and `trimToNewest` is correct against a
primary-key column. `TourContentDao` and `TourContentEntity` agree on every
column. The `Condition is always 'true'` warning in `NavigationServiceImpl:279`
is a smart-cast artefact of the null check at line 245, not a missing guard.

**Carried over from round 2:** B11 (`targetSdk` 35 → 36), which needs a device
rather than a build and is the last lint warning in the project.

### C1 and C2 — "Keep the story when a prompt is paused"

Done together, because they are the same change: the bug was unfixable-with-
tests until the state machine had somewhere to live, and the state machine was
worth moving because of the bug.

**`SpeechSession`** now holds what `AudioServiceImpl` kept in four fields
behind one lock — who is speaking, what was set aside, how far in, and whether
the listener paused. Each `synchronized` block in the service became one call
on it, and the pattern is the one `TourSession` established: every method is
atomic, and every consequence that belongs outside the lock is *returned*
rather than performed. `pause()` hands back the channel to notify and the
channel to close; `supersede()` and `clear()` hand back the channels to close;
`finish()` hands back the narration to pick up. The service does that work
after the lock is released, exactly where it did before.

**The fix is four lines inside `pause()`.** A prompt on top of a narration is
now dropped rather than set aside, so the story underneath survives:

```kotlin
if (active.isPrompt) {
    Pause(paused = true, close = active.channel)
} else {
    parked = Parked(active.text, position, active.channel)
    Pause(paused = true, notifyPaused = active.channel)
}
```

Losing the tail of "turn left in 200 feet" is a much smaller thing than losing
the story — and much smaller than what actually happened, which was the story's
flow being referenced by nothing and never closed, so `NarrationDelivery`'s
`collectLatest` never returned, its `endDelivery` never ran, and the delivery
gate stayed held for the rest of the tour.

**I checked that the tests catch it rather than merely pass.** After writing
them I put the old behaviour back — `parked = Parked(active.text, ...)`
unconditionally — and re-ran: two failures, `pausing during a prompt keeps the
story, and drops the prompt` and `resuming after that pause picks the story
back up`. Then restored the fix and re-ran green. A19 is why: the tests I wrote
there passed against a premise that was wrong, and a test that cannot fail is
not evidence.

Tests: **17 new cases (451 total, 0 failures)** covering the interleavings that
had none — a prompt handing the floor back when it finishes, a prompt that
finishes *while paused* leaving the story for the listener rather than starting
it unasked, a newer prompt replacing an older one without disturbing the story,
a new narration superseding both, a refused audio-focus resume being put back,
a collector going away as the current speaker versus as a stale one, and the
position only being recorded for the utterance actually speaking.

`AudioServiceImpl` is 525 → 446 lines with 231 lines of `SpeechSession` beside
it, and for once the arithmetic is beside the point in the other direction: the
new file is not extracted *logic*, it is extracted *state*, and it is the state
that was wrong.

**Left alone deliberately:** the `isClosedForSend` check in
`handleUtteranceFinished` is still a delicate API and still the one compiler
warning in this file. It guards against resuming into a collector that has gone
away, and the race it admits — the channel closing between the check and the
resume — ends with an utterance nobody hears rather than a wedged tour. Worth
knowing about; not worth a lock held across a TTS call.

### C3 — "Say out loud that a rebuilt version is rebuilt"

`.fallbackToDestructiveMigrationFrom(1)` became
`.fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)`.

**`true` is not the default-looking answer, it is the argued one.** The newer
overload exists because the old call drops only the tables Room knows about
*today*. Version 1 is rebuilt precisely because there is no exported schema for
it — no record of what it contained — so a rebuild that left unrecognised
tables standing would carry that unknown forward into a database the app
believes it understands. Rebuilt means rebuilt. Room recommends `true` for the
same reason.

**The literal `1` stays a literal, deliberately.** It would read better as
`AppMigrations.FIRST_MIGRATABLE_VERSION - 1`, and that would be worse: raising
that constant abandons another version's journal, and wiring the fallback to
follow it would make that happen silently on someone else's edit. The whole
posture of A14 is that losing the journal should be loud.

So the coupling is pinned instead of automated: a new test asserts
`FIRST_MIGRATABLE_VERSION` is still 2, and fails with the instruction to update
`AppDatabase` **and be sure abandoning that version's journal is what you
mean**. An invisible relationship between two files became a red build.

Tests: 1 new case (452 total, 0 failures). The builder call itself still cannot
be unit-tested — it needs a real database file and an instrumented test — so
what is testable is the relationship, and that is what got tested.

### C4 — "Make the compiler's warnings mean something again"

**32 compiler warnings → 2**, and both survivors are real: the delicate
coroutines API in `AudioServiceImpl` (documented in the C1 entry) and the
always-true condition in `NavigationServiceImpl` (a smart-cast artefact, noted
when round 3 opened).

**What I did not do is the fix this task asked for, and the task is wrong.**
Picking it up turned up two facts that change it completely:

- MapLibre deprecated the annotation API in **7.0.0**. The app is on **13.4.1**
  — six major versions later, still shipping, no removal announced. "Deprecated
  out from under us" overstated it considerably.
- The replacement MapLibre actually recommends is its **Annotation Plugin**, a
  separate artifact with `SymbolManager`/`LineManager`/`FillManager`. My
  proposed fix — hand-rolled `SymbolLayer` and `LineLayer` over `GeoJsonSource`
  — was rebuilding by hand what the library ships.

So C4 is `WONTFIX` and the real work is **C6**, written against the plugin and
explicitly gated on a device. This is the same call as B11: the failure mode is
an empty map on the default, keyless provider — the primary experience — and
nothing available here can see a pin appear.

**What this iteration delivers instead is the part that is verifiable and was
worth doing on its own.** Both files carry a `@file:Suppress("DEPRECATION")`
with the reason, the timeline, and a pointer to C6. That is not tidying: those
24 warnings were two thirds of the build's output, and three times this session
I had to grep them away to read the two that mattered. A warning list nobody
can read is a warning list nobody reads.

No new tests — nothing changed at runtime. 452 tests, 0 failures; debug,
release and lint all still build.

### C5 — "Let the camera decide for itself"

`CameraDirector` now answers one question — what should the camera do about
this fix — and `MainActivity` performs the answer. Seven fields left the
activity with it: the last speed and GPS bearing, the position history that
stands in for a missing course, the driving gate, whether the view is tilted,
whether the listener is still being followed, and whether the map has been
placed at all.

**The decision was never in one place.** It was spread across the location
listener, `updateLocationOnMap`, the recenter FAB, `updateCameraForNavigation`,
`beginGuidance`, `drawRouteFromNavigationService` and `stopNavigation` — seven
callbacks, each holding one clause of the same rule and one field of the same
state. That is why it had no tests: there was nothing to call.

**Recording and deciding are separate, and that separation is load-bearing.**
`onLocation(speed, bearing)` feeds the driving gate's hysteresis and keeps the
bearing; `moveFor(location, navigating)` chooses the move. They are two methods
because the old code did them in two places — the listener recorded on *every*
fix, including ones arriving before the map was ready, while the decision sat
behind `if (!map.isReady) return`. Folding them together would have quietly
changed the gate's 20-fix release count and swallowed the first-fix move on any
device slower to hand back a map. Faithfulness here was the whole job.

The result is a sealed `Move` — `FirstFix`, `Driving`, `TopDown`, `Follow`,
`None` — and a nine-line `applyCameraMove` in the activity that is the only
code left touching the map camera.

Tests: **15 new cases (467 total, 0 failures)**. `CameraLogicTest` already
covered the arithmetic; these cover the choosing, which is what nobody could
ask before: the first fix winning even mid-navigation, walking pace not
counting as driving, a car coming to rest settling the view back **once** and
then leaving it alone, a short stop *not* flattening the view, panning away
handing the camera over until it is asked for back, recentering going
heading-up while guided but flat while parked, a bearing reported at a
standstill being discarded as noise, the fallback bearing derived from where
the driver has been, and a route overview leaving nothing owed.

`MainActivity` is **1460 → 1401 lines**. Three rounds of extraction have taken
it from 1512, which is honest progress and not a transformation — what changed
is that four more decisions inside it are now answerable without a car.

---

# Round 4

A fourth pass, after round 3. The reading went to the last places nobody had
read closely: the ViewModels, the ads and consent layer, and
`LocationAwarenessServiceImpl` — the file that derives every geofence the guide
reacts to.

## Baseline at round 4

- **467 unit tests**, 0 failures (434 at the start of round 3).
- `lintDebug` **1 warning** (`targetSdk`), 0 errors.
- **2 compiler warnings**, both known and documented.
- `MainActivity` 1401 lines, `TourModeService` 1228. 15.0k lines of source.
- Open from earlier rounds, both needing a device: **B11** (`targetSdk` 36) and
  **C6** (the MapLibre Annotation Plugin port).

---

## Tier 2 — silent failure

### D1 · Ads are non-personalized everywhere consent isn't required — `DONE`

`ConsentManager` decides personalization with one expression, written three
times:

```kotlin
useNonPersonalizedAds = info.consentStatus != ConsentInformation.ConsentStatus.OBTAINED
```

UMP reports **`NOT_REQUIRED`** outside consent regions — most of the world,
including this app's likely largest market. `NOT_REQUIRED` is not `OBTAINED`,
so `npa=1` goes onto every ad request there, permanently. The app opts itself
out of personalized advertising exactly where it is free to serve it.

It errs in the user's favour, which is why nothing has caught it: no crash, no
log, no test, and the only signal is revenue that never arrives. But it is
plainly not what the code means — `NOT_REQUIRED` means consent was not needed,
not that it was refused.

**Fix:** non-personalized when consent is required and not yet given, or the
status is unknown; personalized when it was obtained *or* was never required.
And make it one pure function instead of three copies of an expression, so it
can be tested — which is the only reason this was three copies of a bug rather
than one.

### D2 · The ads layer is three singletons with one test between them — `DONE`

`AdRetryPolicy` is pure and tested. `ConsentManager`, `InterstitialAdManager`
and `AdsInitializer` are `object`s whose every method takes an `Activity`,
`Context` or `Application`, so nothing in them can be exercised — and D1 lives
in exactly that gap, which is now the fourth time this audit has found a defect
inside an untestable Android singleton (A19, C1, and B7's watcher before it).

**Fix:** not a rewrite of the ads layer. The decisions worth pulling out are
small and few: the consent-status mapping (D1), and whether an ad may load at
all given tier, consent and an ad already in flight. The Android calls can stay
exactly where they are.

---

## Tier 3 — housekeeping

### D3 · Every service teardown that never toured logs an error — `DONE`

Mine, from B5. `releaseTourResources` calls `stopProximityMonitoring()`
unconditionally — which was the point, since the failed tours it exists for
never reached a clean stop. But that method calls
`context.unregisterReceiver(batteryReceiver)`, and a receiver that was never
registered throws `IllegalArgumentException`, caught and logged at **error**
level.

So a service that starts, receives a control it can't act on and stops — the
`TourCommand.NONE` path A4 added — now writes "Error unregistering battery
receiver" on the way out, every time. The release is right; the noise is not,
and error-level logs that are routine are how real ones get missed.

**Fix:** the receiver's registration is state like any other. Track it, and
unregister only what was registered.

### D4 · A developer's phone is hardcoded in the consent settings — `DONE`

`ConsentManager.buildConsentRequestParameters` carries
`addTestDeviceHashedId("38F6DC6...")` under `BuildConfig.DEBUG`. It is one
person's device, it means nothing to anyone else who clones this, and it fails
silently — a debug build on any other phone simply doesn't get the EEA test
form it was meant to guarantee.

**Fix:** read it from `local.properties` like the API keys, so each developer
gets their own and a clone without one still builds.

---

## Round 4 progress log

Newest last.

### Round 4 opened — audit recorded

Read `PlacesViewModel`, the four files of the ads layer,
`LocationAwarenessServiceImpl` and `LocationCadence`. Found one silent defect
(D1), the untestable surface it lives in (D2), one error-level log I introduced
myself in B5 (D3), and one hardcoded developer detail (D4).

**Checked and found nothing to report in:** `LocationCadence` and the cadence
re-registration around it — `stopProximityMonitoring` resets
`requestedIntervalMs`, so a second tour in the same process re-registers its
listener properly, which was the failure I went looking for.
`InterstitialAdManager` holds an `Activity` only inside a callback on an ad it
nulls on dismissal. `PlacesViewModel`'s repeated `launchIn(viewModelScope)`
collections all terminate, so they accumulate nothing; two overlapping nearby
fetches can land out of order, which costs a stale POI list for one refresh and
nothing else.

### D1 and D2 — "Consent that was never needed is not consent refused"

`ConsentPolicy` now holds the rule, and it is the rule that changed:
personalized advertising is allowed when the listener agreed **or when nobody
had to ask**. Before, only `OBTAINED` counted, so every request from outside a
consent region — `NOT_REQUIRED`, most of the world — carried `npa=1` forever.

**The mapping happens at the boundary, in UMP's own names.** `ConsentManager`
translates `info.consentStatus` into a `ConsentState` using
`ConsentInformation.ConsentStatus.OBTAINED` and friends, so the compiler checks
that mapping; `ConsentPolicy` never imports the ad SDK, which is what makes it
testable at all. My first draft copied UMP's int values into the policy by
hand — four magic numbers whose only guarantee was that I had typed them
correctly. That is exactly the kind of assumption this audit keeps finding, so
it went before it was committed.

**The expression existed three times** — once in `gatherConsent`, twice in
`showPrivacyOptions` — which is why it was three copies of one bug rather than
one. It is now one function with a name.

**Verified against the bug, not just for passing.** Putting the old rule back
(`OBTAINED -> false; else -> true`) fails two of the five tests and only those
two. That check is worth the minute it costs: A19's tests once passed against a
premise that was wrong.

Tests: 5 new cases (472 total, 0 failures) — each state's answer, and one that
pins the *shape*: exactly two states allow personalizing. A state added to the
enum without a decision fails to compile in the policy; a state added to the
wrong side of the rule fails here.

**D2 is done, and its second half deliberately isn't.** D2 asked for the
consent mapping and "whether an ad may load at all given tier, consent and an
ad already in flight". The first is above. The second, on reading it, is
`if (!adsAllowed() || isLoading || interstitialAd != null) return` — three
booleans whose test would restate them. Extracting that to reach a coverage
number is the thing this audit has refused all the way through; the ads layer's
remaining code is SDK calls and lifecycle glue, and `AdRetryPolicy` already
covers the one piece of arithmetic in it. Recorded rather than manufactured.

### D3 and D4 — "Stop shouting about nothing, and stop testing on one phone"

Two pieces of noise, one of them mine.

**D3.** The battery receiver's registration is now state, and only what was
registered gets unregistered. B5 made `releaseTourResources` unconditional on
purpose — a tour that *failed* holds exactly as much as one that was stopped —
and the cost was that a service which starts, receives a control it can't act
on and stops (A4's `TourCommand.NONE` path) wrote **"Error unregistering
battery receiver"** on the way out, every time. The release was right; the
error was an artefact of asking a receiver that had never been registered to go
away.

The catch that remains is deliberately broad, which is the opposite of what B4
argued for elsewhere, and the reason is the call site: this runs from the tour
service's `onDestroy`, where the contract is that teardown does not throw.
B4's rule was never "narrow everywhere" — it was "catch what the caller's
contract needs", and here that is everything. It logs at warning now, because
with the guard in front of it, reaching that catch means something odd rather
than something wrong.

**D4.** `addTestDeviceHashedId("38F6DC6…")` was one person's phone, written
into the source. Its purpose is to force the EEA consent form on a development
device so the flow can be tested outside a consent region — and on every other
clone it did the exact opposite of that, silently: an id that matches nothing,
so no form, and no sign anything was meant to happen. It now reads
`UMP_TEST_DEVICE_HASH` from `local.properties` like the API keys, is skipped
entirely when blank, and `app/docs/ads.md` says where to find your own (UMP
prints it to logcat on first run).

No new tests. Both changes are Android-boundary bookkeeping — a `registerReceiver`
call's state and a build-config string — with no decision to pin down; the one
piece of this area that *was* a decision is D1's, and it has five. 472 tests, 0
failures.

---

# Round 5

A fifth pass. The reading went to the settings sheet — at 469 lines the largest
file no round had opened — and the Take a Tour planner behind it.

## Baseline at round 5

- **472 unit tests**, 0 failures (467 at the start of round 4).
- `lintDebug` **1 warning** (`targetSdk`), 0 errors; 2 compiler warnings, both
  documented.
- Open and device-gated from earlier rounds: **B11** (`targetSdk` 36) and **C6**
  (the MapLibre Annotation Plugin port).

Everything found this round is in `TourSettingsFragment`, and none of it is
exotic: a crash on a fast tap, a value the app changes behind the user's back,
and a coroutine launched on a scope that is about to be destroyed.

---

## Tier 1 — breaks in normal use

### E1 · Saving before the settings finish loading crashes the app — `DONE`

`currentPreferences` is a `lateinit var`, assigned only inside the
`viewModel.userPreferences.observe { }` callback, and `savePreferences()` opens
with `currentPreferences.copy(...)`.

That LiveData comes from `userPreferencesFlow.asLiveData()` — a DataStore read,
so it arrives after a coroutine hop and a file read. The sheet's buttons are
laid out and tappable before then. Tap **Save** on a cold first open and the
result is `UninitializedPropertyAccessException`: not a caught failure, not a
degraded save, a crash.

It hides because the ViewModel is activity-scoped: the second time the sheet
opens, LiveData replays its value into the observer immediately and the field is
always set. Only the first open in a process is exposed, which is exactly the
open a new user makes.

**Fix:** the field is genuinely optional until the read lands. Make it so, and
have Save do nothing (or stay disabled) until there is something to copy.

---

## Tier 2 — silent failure

### E2 · Every save nudges the voice speed and pitch the user never touched — `DONE`

Voice speed is stored as a float in `0.5..2.0` and shown on a 0–20 SeekBar.
`speedToProgress` converts with `.toInt()`, which truncates:

| stored | progress | saved back as |
| --- | --- | --- |
| 0.5 | 0 | 0.5 |
| **1.0** (the default) | 6 | **0.95** |
| 1.2 | 9 | 1.175 |
| 1.5 | 13 | 1.475 |
| 2.0 | 20 | 2.0 |

So opening settings, changing the notification distance and pressing Save moves
the voice speed the user never went near. Pitch has the same four functions and
the same bug. It settles after one save rather than drifting forever, which is
why it has gone unnoticed — the guide simply speaks a little slower and lower
than it was asked to, from the first save onward.

The four conversions (`progressToSpeed`, `speedToProgress`, `progressToPitch`,
`pitchToProgress`) are two behaviours written twice, private to a fragment, and
untested — which is the whole reason a rounding bug lives in them.

**Fix:** one pure conversion for both sliders, rounding rather than truncating,
tested for the round trip. Then Save can only change what was actually moved.

### E3 · The audio settings are written twice, the second time on a dying scope — `DONE`

`savePreferences()` calls `viewModel.updateUserPreferences(...)` — which writes
every field, audio included, and updates the TTS engine — and then calls
`viewModel.updateAudioSettings(...)` inside `lifecycleScope.launch { }`, writing
four of those fields again and re-reading preferences to update the engine
again.

Two things are wrong and they cancel out, which is the interesting part. The
second write is redundant. And it is launched on the **fragment's**
`lifecycleScope` one line before `dismiss()`, so it may be cancelled before it
ever reaches the ViewModel. A coroutine that might not run, doing work that was
already done: the only reason this isn't a bug is that its own redundancy covers
its own cancellation.

**Fix:** delete the second call. One save, one write, and the engine update the
ViewModel already performs.

---

## Round 5 progress log

Newest last.

### Round 5 opened — audit recorded

Read `TourSettingsFragment` in full and `TakeATourViewModel`. Found one crash
(E1), one silent value change (E2) and one redundant write on a scope about to
be destroyed (E3).

**Checked and found nothing to report in:** `saveMapProvider` / `saveAccountTier`
— the `or` rather than `||` is deliberate and commented, both saves must run,
and both persist through the activity-scoped ViewModel so the write survives the
`recreate()` that follows. The Google-provider radio button is correctly
disabled when no `MAPS_API_KEY` is configured, and the account-tier section is
debug-only in both its visibility and its save path. `TakeATourViewModel`
guards against a second `planTour` while one is in flight and catches broadly
around the whole plan.

### E1, E2 and E3 — "One save, and it saves what it was shown"

Three tasks, one function. They are the same forty lines of `savePreferences`
and the conversions it calls, and splitting them into three commits would have
been bookkeeping rather than work.

**E1 — the crash.** `currentPreferences` is a nullable `var` now, which is what
it always was in fact: nothing exists until the DataStore read lands. Save is
disabled until the observer fires and re-enabled when it does, so the gap
between the sheet appearing and the preferences arriving is *visible* instead of
being a crash on a fast tap. `savePreferences` also returns early if there is
nothing to copy — belt to that braces, since a disabled button is a UI promise
and this is the code's own.

**E2 — the value nobody touched.** `VoiceSliders` holds the conversion for both
sliders, and the fix is the scale, not the rounding. At 20 steps across 0.5–2.0
the step is 0.075 and **1.0 is not a position at all** — it sat at 6.67, and
truncating gave back 0.95. Rounding alone would have given 1.025: still not the
value the user had. At **0.05 per step (30 positions)** every value the app
itself uses is a position, so down-and-back is identity. The layout's two
`android:max` values moved with it, and the XML's default `progress="10"` now
means 1.0, which is what `UserPreferences` says — it used to mean 1.25.

`progressFor` also rounds to nearest rather than truncating, which is what a
value stored by the *old* scale needs: it moves by at most half a step instead
of always losing a whole one.

**E3 — the coroutine that might not run.** The second
`viewModel.updateAudioSettings(...)` is gone. It re-wrote four fields
`updateUserPreferences` had just written, and re-read preferences to update a
speech engine that call had just updated — from the **fragment's**
`lifecycleScope`, one line before `dismiss()`, so it may well never have run.
Two faults that cancelled each other out. One write now, in the ViewModel's
scope, which is where a write that must outlive a dismissed sheet belongs.

Tests: **6 new cases (478 total, 0 failures)** — the app's own defaults
surviving a round trip, *every* position surviving one, the round numbers a
person would expect being positions at all, the ends of the scale being the ends
of the range, a between-steps value moving to the nearer step, and out-of-range
values clamping.

**One of those tests was wrong when I wrote it, and the code was right.** I
asserted that `0.97` and `1.0` land on the same position; they don't, because
0.97 is nearer to 0.95 than to 1.00, and the test failed. That is the A19 lesson
for the third time: a test written from an assumption rather than from the
arithmetic. It now pins what the rule actually says, and says in a comment why
0.97 is a coin toss and not the case to pin.

---

# Round 6

A sixth pass, over what no round had opened: the journal and place-details
sheets, `TourContentRepository`, `SwitchingApis` and `GPSTrackerApplication`.

## Baseline at round 6

- **478 unit tests**, 0 failures (472 at the start of round 5).
- `lintDebug` **1 warning** (`targetSdk`), 0 errors; 2 compiler warnings, both
  documented.
- Open and device-gated: **B11** (`targetSdk` 36), **C6** (MapLibre plugin port).

The yield is thinner than earlier rounds — most of what was read is sound, and
that is recorded below rather than padded out. But the first finding is a crash,
and it is one **this session's own E2 fix walked past**.

---

## Tier 1 — breaks in normal use

### F1 · The voice-settings dialog crashes on a value the settings sheet stores — `DONE`

There are two places to set voice speed and pitch: the tour settings sheet, and
a **Voice Settings** dialog inside a place's details sheet. They disagree about
which values exist.

`dialog_voice_settings.xml` gives both sliders `android:stepSize="0.1"` from
`valueFrom="0.5"`, so the only values that slider accepts are 0.5, 0.6, … 2.0.
`PlaceDetailsBottomSheet` then does:

```kotlin
dialogBinding.sliderVoiceSpeed.value = currentPreferences?.voiceSpeed ?: 1.0f
```

Material validates that. From `BaseSlider` in material 1.14.0:

> `Value(%s) must be equal to valueFrom(%s) plus a multiple of stepSize(%s) when using stepSize(%s)`

It throws `IllegalStateException`. So a stored speed that isn't a multiple of
0.1 crashes the app when the dialog opens — and the settings sheet stores such
values as a matter of course:

- **Before E2** (20 steps of 0.075): 15 of 21 slider positions produced an
  invalid value, **including the stored default of 0.95**. Saving the settings
  sheet at all and then opening Voice Settings was a crash.
- **After E2** (30 steps of 0.05): 15 of 31 positions still do — 0.55, 0.65,
  0.75, 0.85, 0.95, 1.05 and so on.

E2 changed *which* values crash and not *whether* they do, which is worth saying
plainly: fixing the sheet's own round trip did nothing for the second UI reading
the same stored value, because nothing tied the two together.

**Fix:** one definition of the voice range and its grid, used by both. The
dialog should coerce whatever is stored onto that grid before handing it to a
`Slider` — which also makes an out-of-range value from a restored or
hand-edited preferences file harmless instead of fatal.

---

## Tier 3 — housekeeping

### F2 · The journal's dates ignore the device's clock setting — `DONE`

`TourJournalBottomSheet` formats entry dates with
`SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())`, in two places —
the row and the share text. The locale is right; the pattern is not. `h:mm a`
is 12-hour with AM/PM whatever the device is set to, so a user on 24-hour time
reads "3:45 PM" in the journal and "15:45" on the navigation card.

That is exactly what A13 and A20 fixed for the ETA, with
`DateFormat.getTimeFormat(context)`. The journal was written before that and
never caught up. The literal `'at'` is untranslated English inside a date
pattern too.

**Fix:** `DateUtils.formatDateTime` with date, time and abbreviated month, which
follows both the locale and the 12/24-hour setting — and drops the hardcoded
word.

### F3 · One sheet reaches for its ViewModel the long way round — `DONE`

`TourJournalBottomSheet` uses `ViewModelProvider(requireActivity())[PlacesViewModel::class.java]`
and a `lateinit var`, where every other fragment uses
`by activityViewModels()`. It works — Hilt installs the factory the manual form
picks up — but it is the one place that would break if the ViewModel ever needed
assisted injection, and it is a `lateinit` where a delegate would do.

**Fix:** the delegate, like its neighbours.

---

## Round 6 progress log

Newest last.

### Round 6 opened — audit recorded

Read `TourJournalBottomSheet`, `PlaceDetailsBottomSheet`, `SwitchingApis`,
`TourContentRepository`, `GPSTrackerApplication` and the voice-settings dialog
layout. Found one crash (F1) and two pieces of housekeeping (F2, F3).

**Checked and found nothing to report in:** `SwitchingApis` — routing place
details by id shape rather than by the active provider is deliberate and
commented, so a POI found before a provider switch still resolves.
`GPSTrackerApplication`'s `runBlocking` seed is documented, is one small file,
and B1 removed its ability to throw; the Google-without-a-key fallback is
handled there too. `TourContentRepository` is a thin facade with nothing in it
to get wrong. `JournalFormatter` already injects date rendering so it can stay
pure — the caller is what got it wrong, not the formatter. The two voice
slider grids (0.1 in the dialog, 0.05 in the sheet) **nest**, so a value set in
one is representable in the other; the fault is the validation, not the
resolution.

**On the thinner yield:** five rounds have each turned up something real in
code nobody had read. This round had less to find, and most of what it read was
sound. The highest-value work left in the project is the two device-gated tasks,
not more reading.

### F1 — "One scale, and both sliders on it"

`VoiceSliders` gained `STEP` and `onGrid`, and the place-details dialog now takes
its range, its step **and** its value from there:

```kotlin
slider.valueFrom = VoiceSliders.MIN
slider.valueTo = VoiceSliders.MAX
slider.stepSize = VoiceSliders.STEP
slider.value = VoiceSliders.onGrid(stored ?: UserPreferences().voiceSpeed)
```

Set in that order, because a `Slider` validates its value against the range and
step it has at the time. The layout's `stepSize` moved from 0.1 to 0.05 to
match, though the code now overrides all three — the XML agreeing is for whoever
reads it next.

**`onGrid` is the part that makes it safe rather than merely consistent.** Both
UIs sharing a scale fixes values the sheet writes from now on; it does nothing
for what is *already* stored — an install that saved 0.95 under the old 0.075
scale, or a preferences file restored from another device, or one edited by hand.
`onGrid` puts anything on the grid before a `Slider` sees it, so the crash is
closed for values this app never produced either.

**How the crash was established, since it needed evidence rather than a hunch:**
I read the string out of `BaseSlider` in the material 1.14.0 AAR —
`Value(%s) must be equal to valueFrom(%s) plus a multiple of stepSize(%s)` —
rather than assuming Material validated the value at all. Then counted the
positions: 15 of 21 under the old scale, including the stored default; 15 of 31
under the new one.

Tests: **3 new cases (481 total, 0 failures)** — every position on the scale
satisfying Material's rule exactly, anything stored (including 0.95, 1.025,
1.475, 0.1 and 7) landing on the grid, and a value already on the grid being
left alone. The first of those is the one that would have caught F1: it asserts
the property Material enforces, on the scale both UIs use.

**Left for the next tick:** F2 and F3, both housekeeping.

### F2 and F3 — "The journal catches up with the rest of the app"

**F2.** One `formatVisited(millis)` on the fragment, passed to both the adapter
and `JournalFormatter.shareText` — which already took a date renderer as a
parameter for exactly this reason, so the formatter never needed changing; the
callers did. It uses `DateUtils.formatDateTime` with date, time and abbreviated
month, which follows the locale *and* the 12/24-hour setting. A listener on
24-hour time no longer reads "3:45 PM" in the journal and "15:45" on the
navigation card, and the untranslated `'at'` inside the old pattern is gone.

The `SimpleDateFormat` the row holder built **per view holder** went with it.

**F3.** `by activityViewModels()`, like every other fragment, replacing
`ViewModelProvider(requireActivity())[...]` and the `lateinit var` it needed.

No new tests, and no way to add one worth having: `DateUtils.formatDateTime`
needs a `Context` and answers differently per device setting, which is the whole
point of using it. The pure part — assembling the journal's share text — was
already tested, and it was already right.

**Round 6 closed.** One crash found and fixed, two pieces of housekeeping. What
remains open across all six rounds is **B11** (`targetSdk` 36) and **C6** (the
MapLibre Annotation Plugin port), both of which need a device rather than a
build.

---

# Round 7

A seventh pass, and a different method: rather than read files a fourth time,
this round swept for the **patterns the bugs already found predict**. Three
sweeps, one finding.

## Baseline at round 7

- **481 unit tests**, 0 failures.
- `lintDebug` **1 warning** (`targetSdk`), 0 errors; 2 compiler warnings, both
  documented.
- Open and device-gated: **B11** (`targetSdk` 36), **C6** (MapLibre plugin port).

---

## Tier 3 — housekeeping

### G1 · The tour notification is the last user-facing text built in code — `DONE`

Every string the foreground notification shows was a Kotlin literal: "Tour Mode
Active", "Discovering interesting places nearby...", "Approaching X", "Watching
N interesting places along your route", "Audio Paused", "Playing narration for
X" and the "Unknown location" it fell back to.

Two of them **already existed in `strings.xml`** — `tour_mode_active` and
`tour_mode_discovering`, written for the tour-mode card, with the notification
carrying its own slightly different copy of the same sentence. A20 moved the
notification *channel* names and descriptions to resources; the notification's
own text was left behind, and nothing catches it: lint's `HardcodedText` reads
layouts, not Kotlin.

**Fix:** the existing two reused, five new strings and one plural added, and one
`narratingPlaceName()` replacing two copies of a fallback — which also stopped
that fallback reading "Unknown location", an error-sounding phrase for the
way-of-life filler, which is about a region and correctly has no place at all.

---

## Round 7 progress log

Newest last.

### Round 7 opened, and closed — three sweeps, one finding

**Swept for E1's shape** — a `lateinit var` assigned asynchronously and read
from a user-triggered callback. E1 was that bug in the settings sheet, so the
question was whether it had siblings. Every other `lateinit` in the project is
either Hilt's `@Inject` (set before `onCreate` returns) or a view reference
assigned in `initViews`/`onCreateView` before anything can touch it. **No
siblings.** `PlaceDetailsBottomSheet` still holds its ViewModel in a `lateinit`
assigned via `ViewModelProvider` rather than the `by activityViewModels()`
delegate F3 moved the journal to — synchronous, so not E1's shape, but the same
inconsistency in the one sheet F3 did not cover.

**Swept the test suite for tests that cannot fail.** I wrote three bad tests in
this session — two in A19 on a false premise, one in `VoiceSlidersTest` from an
assumption rather than the arithmetic — so the suite deserved the same
suspicion. Checked all 481 for a test function with no assertion, and every
`assertEquals` for one comparing a value to itself. **Both came back empty.**

**Swept for user-visible English built in code** rather than declared in
`strings.xml`. That found G1, above; the sweep now returns nothing.

**Recorded as deliberate, not as debt:** `ContentServiceImpl.buildFallbackContent`
and `JournalFormatter`'s share text are still English literals, and should stay
that way. They are *content*, not chrome — the narration pipeline is
en.wikipedia.org and English Gemini prompts throughout, so putting the fallback
template in `strings.xml` would advertise a translation the guide cannot
deliver. `JournalFormatter` is deliberately free of Android so it can be pure
and tested; giving it resources would mean giving it a `Context`.

**On the method, and what is left.** Reading files found something real six
rounds running; this round it did not, so the sweeps were the more useful
question, and two of the three found nothing — which is worth as much as a
finding, because it bounds where the same class of bug can still be hiding. The
work with the most value left in this project is not another round. It is
**B11** and **C6**, and both need a device.
