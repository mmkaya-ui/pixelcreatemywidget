# Afterdrop

Describe a widget in plain words and get a real home-screen widget on a Pixel 7, 8 or 9: an
independent alternative to Google's "Create My Widget", which those phones do not get.
Not affiliated with Google.

**Status: test build 0.5.0, not a stable release.** It is tested on Android 16 and 17 emulators,
including the launcher's own "Add to home screen" flow. It has not yet been run on a real phone.
See [ROADMAP.md](ROADMAP.md) for what is left.

## What it does today

- **Gallery:** 18 ready-made widgets, each added with one tap.
- **Describe a widget:** type "countdown to 24 December" or "track 8 glasses of water a day".
  Keyword rules on the phone build it, with no AI and no internet.
- **Create with AI:** with your own Gemini API key, a model writes the widget. Every answer is
  checked; a rejected one is sent back with the reasons, up to twice, then the offline rules take over.
- **Change with AI:** rewrite a widget you created, for example "bigger number, secondary colour".
- **Any chatbot:** copy a ready prompt into any assistant and paste its answer back.
- **Live data:** battery, storage, weather (Open-Meteo), exchange rates (Frankfurter), news feeds
  (RSS and Atom) and any https address that returns JSON.
- **Any size:** from 1 x 5 to 5 x 1 cells. What does not fit is dropped whole, controls last.

## What it does not do yet

- Calendar, alarm, health, now-playing and air-quality data show a dash.
- Icons are emoji stand-ins; images are blank.
- No preview before adding, and a widget already on the home screen keeps its look after an edit.
- No model runs on the phone. Without a key, "Create" uses rules.
- It cannot read other apps' private data. Android does not allow that.

## Requirements

Android 16 or newer. Built for Pixel 7, 8 and 9; other phones are untested.

## Privacy

- No account, no analytics, no ads.
- One permission: internet. No location permission; you type a city for weather.
- The app contacts only: `api.open-meteo.com` and `geocoding-api.open-meteo.com` (weather, and the
  city name you type), `api.frankfurter.dev` (exchange rates), the feed or JSON addresses of widgets
  you add (each widget lists its hosts), and `generativelanguage.googleapis.com` when you use your
  own Gemini key (your description, and for an edit the widget itself, are sent).
- Your Gemini key is stored in the app's private storage and sent only to Google's API.
- A widget's parameters for internet sources cannot read calendar, health, notes or media data,
  directly or through a formula, a variable or a list item. This is enforced when a widget is read.

## Build and test

```sh
scripts/check.sh              # core tests with only kotlinc and a JDK
./gradlew check               # the same through Gradle
./gradlew :app:assembleDebug  # the APK, in app/build/outputs/apk/debug
```

CI also runs the app on Android 16 and 17 emulators: every gallery widget at five sizes, dark theme
with a 1.5x font, live calls to the data services, and pinning a widget through the launcher.
If the repository has a `GEMINI_API_KEY` secret, CI also holds a real conversation with Gemini.

## Modules

| Module | What it does |
| --- | --- |
| `formula` | The formula language inside widgets: parser, static checker, evaluator, 37 functions. Evaluation never throws. |
| `spec` | The widget format (WidgetSpec v1): JSON reader, schema check, lint, version migration, typed model. |
| `engine` | Turns a spec plus data into visible, evaluated nodes; applies controls; fits content to the widget's size. |
| `composer` | Expands a short brief into a full spec; the offline describe rules; the chatbot prompt. |
| `ai` | The ask-validate-repair loop and a small Gemini client with model fallback. |
| `data` | Weather, exchange rates, feeds and JSON: request building and parsing. |
| `app` | The Android app: gallery, describe screen, one Glance widget that renders any spec. |
| `testkit` | A dependency-free test harness. |
| `golden/valid` | 18 valid widget specs, which double as the gallery. |
| `docs/schema` | JSON Schemas for a widget and for a brief. |

The core modules have no dependencies beyond the Kotlin standard library and the JDK.

## Licence

Copyright (c) 2026 Mert Mercankaya. All rights reserved.

The source is visible for reference, but no licence is granted to use, copy, modify or distribute
it without written permission. Versions published before 12 November 2026 were released under
Apache 2.0, which still applies to those versions. See `LICENSE` and `NOTICE`.
