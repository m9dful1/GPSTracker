# AI tour-guide narration

Tour narration can be written by Gemini instead of read verbatim from
Wikipedia. When enabled, the guide picks the one or two most interesting
details about a place and delivers them like a live tour guide — brief,
conversational, and front-loaded — right after the positional intro the app
already speaks ("On your left: Fort Point. — Built in 1861, this fort never
fired a shot in battle…").

## Enabling it (development / personal use)

1. Create a free API key at https://aistudio.google.com/apikey — no billing
   account needed; the free tier is far more than a day of touring uses.
2. Add it to `local.properties` (gitignored, next to the SDK path):

   ```
   GEMINI_API_KEY=your-key-here
   ```

3. Rebuild. Without the key the app builds and runs normally and narrates
   straight from Wikipedia — the rest of the app remains fully key-free.

Gemini narration is also a **premium account** feature: standard accounts
narrate from the parsed article even when a key is configured (see
`account_tiers.md`; debug builds can switch tiers in Tour settings).

## How it works

- `GeminiApiService` (`data/api/`) sends the place's **verified reference
  notes** — the Wikipedia intro plus OSM details — and asks for at most four
  spoken sentences, thinking disabled for speed. The prompt encodes trained
  guide craft (see `tour_guide_research.md`): one idea per stop, the
  road-visible feature first, people and feeling over dates, folklore
  labeled as folklore, and a closing line that gives the listener something
  to look for. The model is instructed to use only the provided facts and to
  say less when the notes are thin, so it cannot invent history for
  undocumented places. Places with no Wikipedia article never reach the
  model at all.
- `ContentServiceImpl` tries the AI script first, caches the result in Room
  (one generation per place, ever), and falls back to the plain Wikipedia
  intro when the key is missing, the device is offline, the request times out
  (10 s cap), or the output fails validation (markdown, URLs, degenerate
  length).
- Narration priority (`TourLogic.contentPriorityFor`) decides what gets read
  when several places are in range: intrinsic category interest (historical/
  cultural > natural/architectural > entertainment > dining/shopping), having
  real facts to tell, user ratings and preferred categories, minus a penalty
  for recently narrated places. The hourly narration cap still applies.

## Releasing on the Play Store

Do **not** ship this key in a release APK — anything embedded in the app can
be extracted, and the free-tier quota is per-project (shared by every user of
the key), not per user. For a store release, swap the transport to **Firebase
AI Logic** with App Check: no key ships in the app, abuse is blocked, and
billing/quota move to your Firebase project. Only `GeminiApiService` changes;
the prompt, validation, caching, priority, and fallback logic all stay as-is.
