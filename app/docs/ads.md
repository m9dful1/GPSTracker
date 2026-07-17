# Ads (AdMob)

The ad setup mirrors Spiritwise's PopitBlast app: Google Mobile Ads with
the UMP consent flow, initialized after the first rendered frame so app
launch never waits on the ads SDK.

Ads only serve on the **standard account tier** — premium accounts see
none (see `account_tiers.md`).

## Where ads appear

- **Banner** — at the foot of the bottom-card stack on the map screen.
  It is hidden the moment navigation guidance starts and comes back when
  the drive ends: no ads on a moving windshield.
- **Interstitial** — preloaded in the background and shown once when a
  drive ends (the user taps End navigation). If none is loaded, nothing
  is shown and nothing waits.

## Configuration

Real IDs go in `local.properties` (gitignored, like the other keys):

```
ADMOB_APP_ID=ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY
ADMOB_BANNER_AD_UNIT_ID=ca-app-pub-XXXXXXXXXXXXXXXX/YYYYYYYYYY
ADMOB_INTERSTITIAL_AD_UNIT_ID=ca-app-pub-XXXXXXXXXXXXXXXX/YYYYYYYYYY
```

Register the app and create the two ad units in the
[AdMob console](https://apps.admob.com). Until these are set, Google's
public sample app ID and test ad units fill in, so every build runs (and
shows clearly-labeled test ads) without an AdMob account. **Debug builds
always use the test ad units** regardless of `local.properties`, and mark
development devices as test devices — safe to tap.

## Consent (UMP)

`ads/ConsentManager` runs Google's User Messaging Platform flow when
MainActivity starts: in consent regions (GDPR) the consent form shows
once, and every ad request carries the answer. Ads default to
non-personalized until consent is obtained. Users can change their answer
any time via **Tour settings → Ads → Ad privacy options**. Debug builds
force EEA geography so the form is testable from anywhere.

## Moving parts

- `ads/AdsInitializer` — defers `MobileAds.initialize` to after the first
  frame (ProcessLifecycleOwner + Choreographer), then preloads the
  interstitial and runs queued work like the banner load.
- `ads/InterstitialAdManager` — keeps one interstitial cached; reloads on
  each return to the foreground; failed loads retry with exponential
  backoff (`ads/AdRetryPolicy`, unit tested).
- `MainActivity` — creates the banner `AdView`, ties it to the activity
  lifecycle, and owns the show/hide-while-guiding rules.

Unlike PopitBlast (a kids' game), this app does **not** tag requests
child-directed or cap the ad content rating — that would be inaccurate
here and would shrink fill. Rewarded ads were also left out: the app has
no reward economy to hang them on.
