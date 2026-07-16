# Map providers: OpenStreetMap ⇄ Google Maps

The whole mapping stack is a user setting. Tour settings → **Map provider**
offers:

| | OpenStreetMap (default) | Google Maps |
|---|---|---|
| Map rendering | MapLibre + OpenFreeMap styles | Google Maps SDK |
| POI discovery | Overpass | Places API (New), Nearby Search |
| Destination search | Photon | Places API (New), Text Search |
| Turn-by-turn routing | Valhalla (FOSSGIS) | Routes API |
| Layers sheet | Liberty/Bright/Positron/Dark styles | Default/Satellite/Terrain + traffic |
| API key | none | `MAPS_API_KEY` required |

Location updates, geofencing, narration (Wikipedia/Gemini), TTS, caching,
and all tour logic are provider-independent and don't change when toggling.

## Enabling Google Maps

1. Create an API key in Google Cloud Console with **Maps SDK for Android**,
   **Places API (New)**, and **Routes API** enabled.
2. Add it to `local.properties` (gitignored, next to the SDK path):

   ```
   MAPS_API_KEY=your-key-here
   ```

3. Rebuild. Without the key the Google option shows disabled in settings and
   the app runs fully keyless on the OpenStreetMap stack.

Flipping the toggle saves the choice, updates the in-process
`MapProviderHolder`, and recreates `MainActivity` so the other map view
attaches. Data services switch per call, immediately.

## How it's wired

- **`MapProvider`** (`domain/model/`) — the enum; persisted by
  `UserPreferencesRepository` (`map_provider` key), mirrored in-memory by
  `MapProviderHolder` (seeded in `GPSTrackerApplication`, so reading it never
  blocks). Google without a key normalizes back to OpenStreetMap at startup.
- **Data services** — `PlacesApi`, `GeocodingApi`, `RoutingApi` interfaces
  with one implementation per provider (`PlacesApiService` /
  `GooglePlacesApiService`, etc.). The `Switching*` facades in
  `data/api/SwitchingApis.kt` pick per call; repositories and the search
  sheet only see the interfaces. Place-details lookups route by *id shape*
  (OSM ids look like `node/123`), so markers and cached places from before a
  switch keep resolving.
- **The map view** — `ui/map/MapController` is everything `MainActivity`
  needs from a map (camera incl. the driving view, markers, route polyline,
  scout circle, styles, the blue dot, lifecycle). `MapLibreMapController`
  and `GoogleMapController` implement it; `MainActivity` picks one in
  `onCreate` and never touches a map SDK directly. Zoom levels cross the
  interface in Google units; the MapLibre side offsets by one (512px tiles).
- **Styles** — each provider keeps its own layers-sheet choice
  (`map_style` / `google_map_style` keys) so switching forgets neither. The
  Google map's night style lives in `res/raw/map_style_night.json`; traffic
  (`map_traffic`) only renders on Google.

## Cost notes (Google)

Nearby Search (New) and Text Search are billed per request; Routes API per
route; map loads per session. The app already keeps usage modest — POI
refetch only after moving 300 m, search debounced at 350 ms with 3+
characters, routes recalculated at most every 15 s when off-route — and the
monthly free tier covers typical personal use. Restrict the key to the app's
package + SHA-1 in Cloud Console; `local.properties` is gitignored, so the
key never reaches the repo.
