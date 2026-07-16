# Take a Tour

The route FAB (above the tour FAB) plans a curated sightseeing drive
instead of waiting for interesting places to drift by.

## The picker

- **Curated tours nearby** — one-tap tours of famous destinations (the
  Vegas Strip, Grand Canyon South Rim, Zion, the National Mall, ...) shown
  when one is within ~90 miles. Each carries its own natural length and
  focus; the catalog lives in `util/CuratedTours.kt`.
- **Start from** — current location by default, with the nearest cities and
  towns (Overpass `place=city|town` nodes within ~40 miles) in the
  dropdown, plus a search box for any city in the world (backed by the
  active map provider's search).
- **Tour length** — Short (~5 mi, half an hour), Medium (~12 mi, about an
  hour), Long (~25 mi, 2+ hours), modeled on real hop-on-hop-off loops and
  scenic drives.
- **Focus** — Balanced, History & culture, Nature & views, or Food &
  entertainment; weights what gets picked.

## How planning works

1. Candidate places are fetched around the tour center (radius scales with
   the length) through the provider-aware `PlacesApi`.
2. `TourPlanLogic` scores each candidate — intrinsic category interest (the
   same weights narration priority uses), ratings, the user's preferred
   categories, all scaled by the focus — then picks the stop count for the
   length while keeping stops spread out, and orders them with a
   nearest-neighbor walk from the center.
3. The plan starts the normal navigation flow with the stops as route
   waypoints, looping back to the tour center: route preview first, then
   tap Start for guidance. Tour mode starts automatically so the guide
   narrates; the corridor registration points narration at the tour route.
4. Meanwhile every stop's script is **preloaded in tour order** through the
   content pipeline — Gemini writes the tour-guide script when a
   GEMINI_API_KEY is configured (see `ai_narration.md`), Wikipedia
   otherwise — and cached in Room, so narration is instant at each stop and
   survives dead zones mid-drive.

Planning is orchestrated by `TakeATourViewModel` (activity-scoped, so
preloading continues after the sheet closes); the pure selection logic in
`util/TourPlanLogic.kt` is unit tested on the JVM.
