# Account tiers

Two tiers decide what pays for the tour guide:

| | Standard (default) | Premium |
|---|---|---|
| Ads | Banner + end-of-drive interstitial (`ads.md`) | None |
| Narration | Parsed Wikipedia article intros | Gemini-written tour scripts (`ai_narration.md`), when a GEMINI_API_KEY is configured |

The routing is fully active in release builds; what release lacks is a way
to *become* premium — the persisted tier defaults to Standard until an
upgrade purchase flow (e.g. Play Billing) calls the same
`AccountTierHolder.set` + `UserPreferencesRepository.setAccountTier` pair
the debug toggle uses.

## Testing (debug builds)

Tour settings → **Account (testing)** switches tiers on Save. The
activity recreates: the banner appears or disappears immediately, and the
next narration follows the new tier. The section is hidden in release.

## Moving parts

- `domain/model/AccountTier` — STANDARD / PREMIUM, persisted in DataStore.
- `data/repository/AccountTierHolder` — the tier readable synchronously,
  seeded at startup like `MapProviderHolder`.
- Ads gating — `AdsInitializer.install` takes an `adsAllowed` predicate
  (`!isPremium`); `InterstitialAdManager` checks it on every load/show and
  MainActivity skips the banner + consent flow entirely for premium.
- Narration routing — `ContentServiceImpl` only asks Gemini when the tier
  is premium *and* a key is configured; otherwise the parsed article path
  runs, exactly as when no key exists.
- Cache — narration is cached per place with its source. A cached item
  from the other tier (AI script vs parsed article) is regenerated and
  replaced rather than served, so switching tiers audibly switches the
  narration even on roads already driven.
