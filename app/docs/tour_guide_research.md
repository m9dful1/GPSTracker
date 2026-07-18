# How real tour guides work — research and what the app does about it

The tour guide behavior in this app is checked against two bodies of
practice: how professional guides are trained (certification curricula and
the academic interpretation literature), and how commercial GPS audio-tour
products design triggering and narration. This file records the useful
findings, where the app already agreed, and what was changed to close the
gaps.

## Sources worth knowing

- **Freeman Tilden, _Interpreting Our Heritage_ (1957)** — the six
  principles every interpretation course still teaches. The load-bearing
  ones here: *relate* to the listener's own experience, *reveal* rather
  than recite information, and "the chief aim is not instruction but
  **provocation**."
- **Sam Ham, _Environmental Interpretation_ (1992)** — thematic
  interpretation (TORE: Thematic, Organized, Relevant, Enjoyable). A
  commentary is built on **one theme sentence**, carries **five or fewer
  main ideas** (three while moving), and reaches a "noncaptive audience"
  that will simply stop listening if the effort outweighs the reward.
- **Coach-guiding practice** (UK Blue Badge's dedicated moving-vehicle
  exam, WFTGA curricula): "on your left / on your right" callouts, point
  it out **before** it appears, and finish talking **before the site is
  behind the vehicle**.
- **VoiceMap publisher docs** — the most quantified public rules: budget
  **150 spoken words per minute**, keep every segment under 750 words,
  audio must finish before the next trigger zone, bearing-filtered
  triggers for driving tours, and "write for the ear: short sentences, no
  brackets — they work on the page but confuse the ear."
- **izi.TRAVEL production docs** — trigger zones sized to 1–1.5 minutes of
  travel, name the object in the first line, and **suffix stories**: after
  a narration, tell the listener what's next so silence never reads as
  breakage.
- **Product reviews** (GuideAlong, Shaka Guide, Autio): what users hate is
  stable across products — narration that fires after the place is passed,
  left/right mix-ups, repeated content, silence that feels like a crash,
  and talking over navigation prompts.

## Where the app already matched practice

- **Positional intro first** — "On your left, about 500 feet: Fort Point."
  names the place, the side, and when to look before any facts arrive
  (coach practice; izi.TRAVEL's name-it-first rule).
- **Speed-scaled triggering** — geofence radii grow up to 8× with speed,
  and the narration detail level drops at driving speeds (VoiceMap's
  budget-by-speed rule, coarsely).
- **Restraint** — hourly narration cap, 14-day revisit cooldown, and
  visited-place penalties line up with the repeated-content and
  info-overload complaints.
- **Navigation prompts win** — narration pauses for turn instructions and
  resumes at the interrupted sentence (the top coexistence complaint about
  commercial apps).
- **Framing** — a spoken start announcement, a route preview, and an
  arrival summary already sketch the welcome/body/close arc.
- **No invented history** — the AI script writer only speaks facts from
  the fetched article, and undocumented places never reach it.

## What the research changed

1. **Stale narrations are dropped** (`TourLogic.narrationIsStale`,
   checked at delivery). A queued story whose place is already more than
   250 m behind the car is skipped — "don't play stale you-are-passing
   audio late" is the audio-tour failure users complain about most. The
   place stays unvisited so a future pass can still tell it.
2. **Encyclopedia text is cleaned for the ear**
   (`ContentServiceImpl.cleanForSpeech`). Wikipedia intros carry IPA
   pronunciations, translations, and reference markers in parentheses and
   brackets; a TTS voice reads all of it. Parentheticals and bracketed
   references are cut and whitespace healed — for both the standard-tier
   narration body and the reference notes sent to Gemini.
3. **The triple name-echo is gone.** Content no longer starts with
   "You are near X." — the spoken intro already names the place, so the
   guide stopped saying "On your left: Fort Point. You are near Fort
   Point. Fort Point is…".
4. **The AI prompt encodes guide craft** (Ham/Tilden, in
   `GeminiApiService.SYSTEM_PROMPT`): one idea per stop with at most two
   supporting details, open with the feature visible from the road, people
   and feeling over dates and measurements, folklore labeled ("legend has
   it"), and a closing line that gives the listener something to look for
   — provocation, not a trailing fact.
5. **A spoken "Up next" bridge** (izi.TRAVEL's suffix story) rides the end
   of a narration when another place is already queued, so the silence
   after it never reads as the app breaking.
6. **Breathing room between stories** — 8 seconds of scheduled quiet
   between back-to-back narrations instead of wall-to-wall lecturing
   (guides are trained to leave time to look and talk).
7. **Planned tours open with a welcome** — "Welcome to your Las Vegas
   Strip tour. There are 8 interesting places along the way…" instead of
   the generic route line (the welcome/orientation phase every curriculum
   teaches). Reroutes no longer re-announce the corridor.
8. **The closing summary calls back the highlight** — "…you heard about 7
   places along the way, including Fort Point." Guides close by paying off
   the best story, not just counting stops
   (`TourLogic.highlightWorthiness` picks it).
9. **Way-of-life filler on long empty stretches.** Coach guides fill dead
   highway with regional color — how people live here, what the area is
   known for — rather than site facts without a site. After 4+ minutes of
   silence while driving (`TourLogic.shouldPlayWayOfLife`), the guide
   tells one segment about the nearest city or town: "While the road is
   quiet, a little about Reno. …" The material is the region's own
   Wikipedia article (found by title with a coordinate check, so "Reno"
   resolves to the city and never a disambiguation page), routed through
   the same tier split as place narration — premium gets a Gemini
   way-of-life script, standard the parsed article — and cached under a
   `region:` key. Restraint rules: sights always outrank filler, at most
   one segment per 10 minutes, each region once per tour session, it
   counts against the hourly narration cap, and it never plays at walking
   speed (quiet on foot is just a walk in the park).

## Considered and deliberately not done

- **Human-style voice / SSML prosody** — every commercial product uses
  human narrators and users consistently dislike synthetic voices; the
  fix within a TTS budget is better text (done above), since Android TTS
  SSML support is too uneven to rely on.
- **Music or ambient beds between stories** (Shaka Guide's approach) — a
  licensing and audio-focus project of its own; the up-next bridge and
  scheduled quiet address the same dead-air anxiety.
- **A chime before narration** (Autio) — worth revisiting if narration
  startling drivers turns out to be a real complaint.
- **Callbacks between stops in AI scripts** — scripts are cached per
  place, and a cached "like the fort we passed earlier" would be wrong on
  any drive that didn't pass the fort.
