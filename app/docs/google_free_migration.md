# Migration plan: Google APIs → open alternatives

Goal: run the entire app — map, POI discovery, destination search, routing,
location awareness — without any Google API key, billing account, or (after
Phase 4) Google Play Services at all. All replacement services are built on
OpenStreetMap data and are free to use without registration.

**Status: all four phases are implemented.** The OpenStreetMap stack is the
default and needs no API key.

**Update (July 2026):** the Google stack later returned as an *optional*
provider behind a settings toggle — see `app/docs/map_providers.md`. The
OpenStreetMap services described here remain the default and the keyless
fallback; with no `MAPS_API_KEY` in `local.properties` the app still builds
and runs fully Google-free (the Maps SDK dependency is present but unused).

Implementation notes that differ from or refine the plan below:

- Location updates go through a small `FrameworkLocationClient` wrapper
  (`data/service/`) over the platform `LocationManager` (GPS first, the
  platform fused provider on API 31+ as fallback).
- Geofence enter/dwell/exit transitions are derived inside
  `LocationAwarenessServiceImpl` from the per-fix distance checks it already
  did, and forwarded to `TourModeService` with the exact intent the deleted
  `GeofenceBroadcastReceiver` used. Trade-off: transitions only fire while
  proximity monitoring is running — which is whenever the tour foreground
  service runs — whereas Play geofences could fire with the app fully dead.
- The map style choice is stored under a new DataStore key (`map_style`);
  the old `map_type` GoogleMap constants are ignored. The traffic toggle is
  gone from the layers sheet (no traffic data without Google).
- MapLibre zoom levels sit one below Google's for the same view (512px
  tiles); `MainActivity` applies a -1 offset to the old zoom constants.
- Marker hue/alpha styling is baked into generated pin bitmaps
  (`util/MarkerIcons.kt`) since MapLibre markers take icons, not hues.
- Photon searches are tuned: `lang` is sent (device language when Photon
  supports it, else `en`) because translated names — "Eiffel Tower" for
  "Tour Eiffel" — only match when asked in that language; the location bias
  is softened (`zoom=11`, `location_bias_scale=0.1`) so distant exact
  matches aren't buried; 30 results are fetched, same-named results within
  2 km are collapsed (OSM splits long landmarks into several elements), and
  the top 10 are shown with the country in the detail line.

## Provider choices

| Capability | Today (Google) | Replacement | Key needed |
|---|---|---|---|
| Map display | Maps SDK (`play-services-maps`) | MapLibre Android + OpenFreeMap vector tiles | No |
| POI discovery | Places SDK `searchNearby` | Overpass API (OSM tags) | No |
| Destination search | Places Autocomplete widget | Photon geocoder (komoot) | No |
| Turn-by-turn routes | Routes API `computeRoutes` | Valhalla (public FOSSGIS server) | No |
| Address geocoding | Android `Geocoder` (GMS-backed) | Keep; Nominatim as fallback option | No |
| POI narration | Wikipedia (already Google-free) | unchanged | No |
| Location updates | `FusedLocationProviderClient` | Android `LocationManager` | n/a |
| Geofencing | `GeofencingClient` | Manual distance checks (mostly exists already) | n/a |

Notes on the choices:

- **Valhalla over OSRM**: the public FOSSGIS instance
  (`https://valhalla1.openstreetmap.de`) returns human-readable instruction
  text and typed maneuver codes, which map 1:1 onto our
  `NavigationService.InstructionType` — OSRM would make us synthesize
  instruction strings ourselves. No API key; identify with a User-Agent.
  No live traffic (the old `TRAFFIC_AWARE` option is lost).
- **OpenFreeMap** serves ready-made MapLibre style JSON
  (`https://tiles.openfreemap.org/styles/{liberty|bright|positron|dark}`)
  with no key and no hard usage limits. Attribution required:
  "OpenFreeMap © OpenMapTiles, Data from OpenStreetMap" (MapLibre renders the
  style's built-in attribution automatically).
- **Overpass** (`https://overpass-api.de`) queries raw OSM tags
  (`tourism=*`, `historic=*`, `leisure=*`, `amenity=*`, `shop=*`) around a
  point. Strong for landmarks/sights (what a tour guide needs), weaker than
  Google for business metadata (ratings, price levels are gone; opening
  hours/phone/website exist when mapped).
- **Photon** (`https://photon.komoot.io`) is a typo-tolerant search-as-you-type
  geocoder with location bias — the closest free match for the Autocomplete
  widget.
- All public servers above are fair-use community infrastructure. Every base
  URL is a single constant so we can point at paid or self-hosted instances
  later without code changes.

## Phases

### Phase 1 — Routing (small, done first)
`NavigationServiceImpl.getRouteFromRoutesApi` → new `RoutingApiService`
(`data/api/`, mirrors `WikipediaApiService`'s shape: OkHttp + testable
parsing in the companion).

- Request: POST `{baseUrl}/route` with `locations` (origin/destination as
  `break`, waypoints as `through`), `costing: "auto"`, `units: "kilometers"`.
- Response: `trip.legs[].shape` is **polyline6** → `Polyline.decode` gains a
  precision parameter. `maneuvers[]` give instruction text, typed codes
  (mapped to `InstructionType`), `begin_shape_index` → maneuver point,
  `length` (km → meters), `time` (s → ms).
- `BuildConfig.MAPS_API_KEY` no longer used for routing; `isValidApiKey`
  deleted. Unit tests for request building, maneuver mapping, response
  parsing.

### Phase 2 — Places (medium)
Rewrite `PlacesApiService` against Overpass; replace the Autocomplete flow
with Photon.

- `getNearbyPlaces(center, radius)`: one Overpass query over the tag families
  above, `out center` for way/relation centroids, results filtered to named
  elements, tags mapped to the existing category buckets
  (CULTURAL/HISTORICAL/NATURAL/ENTERTAINMENT/DINING/SHOPPING). POI id becomes
  the stable OSM id (`node/123`, `way/456`, `relation/789`).
- `getPlaceDetails(id)`: Overpass id lookup; description assembled from OSM
  tags (opening hours, phone, website, wheelchair, ...). Ratings/price levels
  no longer exist — UI already treats them as optional.
- Destination search in `MainActivity`: `PlaceAutocomplete` intent →
  lightweight search UI backed by Photon (`/api/?q=&lat=&lon=&limit=`),
  biased to the map location.
- `Places.initializeWithNewPlacesApiEnabled`, `PlacesClient`, and the
  `com.google.android.libraries.places` dependency are removed.
- Cache note: previously visited POIs keep their Google place ids in Room and
  still display; the same physical place discovered via OSM gets a new id, so
  old visited-state won't transfer onto it. Accepted.

### Phase 3 — Map (large)
Two parts done together:

1. **Neutral coordinate type.** New `domain/model/LatLng.kt` —
   `data class LatLng(val latitude: Double, val longitude: Double)` — a
   drop-in for the Google class (25 files change only their import).
   Gson serializes the same field names, so the Room `LatLngConverter` JSON
   stays compatible with existing databases.
2. **MapLibre swap** (`org.maplibre.gl:android-sdk`). `SupportMapFragment` →
   `MapView` in `activity_main.xml`; `GoogleMap` → `MapLibreMap` in
   `MainActivity`; markers/polyline/circle via the annotations API; camera
   moves (`CameraUpdateFactory` equivalents exist, incl. bounds + tilt/bearing
   follow-cam); my-location via MapLibre's `LocationComponent`.
   - Map layers: Google's Normal/Satellite/Terrain → OpenFreeMap styles
     Liberty (default) / Bright / Positron / Dark. **Behavior change: no
     satellite imagery** (would need a keyed provider like MapTiler; can be
     added later). `UserPreferencesRepository` re-keys the persisted map-type
     int; stored Google values fall back to the default style.
   - Night mode: switch to the Dark style (replaces
     `R.raw.map_style_night`, which is deleted).
   - `MapsInitializer`/`OnMapsSdkInitializedCallback` removed from
     `GPSTrackerApplication`; `com.google.android.geo.API_KEY` meta-data and
     `play-services-maps` removed.

### Phase 4 — Location & geofencing (removes Play Services entirely)
- `FusedLocationProviderClient` → framework `LocationManager`
  (`FUSED_PROVIDER` on API 31+, else GPS+NETWORK) behind a small shared
  helper, in: `NavigationServiceImpl`, `LocationAwarenessServiceImpl`,
  `TourModeService`, `MainActivity`.
- `GeofencingClient` → nothing new: `LocationAwarenessServiceImpl` already
  computes per-POI distance/bearing on every fix. Enter/dwell/exit
  transitions are derived there and forwarded along the same path
  `GeofenceBroadcastReceiver` used (receiver + gms Geofence imports deleted).
- `play-services-location`, `kotlinx-coroutines-play-services`, and
  `MAPS_API_KEY` plumbing (gradle, manifest placeholder, docs) are removed.
  After this phase the app runs on de-Googled devices.

### Verification (after every phase)
`./gradlew assembleDebug test` plus an on-device/emulator pass: map renders,
POIs appear around the location, destination search works, a route draws and
navigates — with no `MAPS_API_KEY` in `local.properties`.

## Risks / accepted trade-offs

- Public OSM servers are fair-use, no SLA. Mitigation: base URLs are
  constants; can self-host or move to keyed tiers (MapTiler, Stadia,
  GraphHopper) without redesign.
- POI quality shifts: fewer chain-business hits, better landmark coverage;
  no ratings/price data.
- No satellite layer, no traffic-aware ETAs (were only used for the ETA
  estimate).
- Wikipedia enrichment unchanged and unaffected.
