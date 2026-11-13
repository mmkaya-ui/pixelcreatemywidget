# Roadmap to a public release

Status on 12 November 2026: version 1.0.0. Not ready to publish. Every "done" item below is
covered by tests that run in CI, including emulator runs on Android 16 and 17. Android 16 is the minimum.

## Done

- [x] Widget format, validator and privacy rules (formula, spec)
- [x] Render engine with a fit pass for any size from 1 x 5 to 5 x 1 (engine)
- [x] Android app: gallery, one widget that renders any spec, five starting sizes
- [x] Describe a widget with offline rules; chatbot-prompt route
- [x] Create with AI using your own Gemini key, with repair loop and offline fallback
- [x] Live data: battery, storage, weather, exchange rates, news feeds, generic JSON
- [x] Emulator tests on four Android versions, including live calls to the data services

## Step 1: first run on a real phone (blocks everything else)

- [ ] Install on a Pixel 8 Pro and add widgets through the Pixel Launcher
- [ ] Check pinning, resizing by dragging, dark theme, large font, tap-through actions
- [x] One real Create with AI call with a real key
- [ ] Fix what that finds

## Step 2: finish the core promise

- [x] Edit a widget by AI ("make the text bigger, use blue")
- [ ] Preview before adding, and a way to change or delete a placed widget's design
- [x] Real icons instead of emoji stand-ins
- [ ] Images
- [x] The `ask` action (type a value into a widget)
- [x] An AI edit updates widgets already on the home screen
- [x] Sources declare their fields; a widget reading a field that does not exist is refused
- [x] Calendar source (needs the calendar permission, asked only when a widget uses it)
- [x] Approval screen for each new host a pasted or generated widget wants to contact

## Step 3: the extras asked for

- [x] Stack: one widget holding up to six, flipped by a tap
- [x] Next alarm and air quality sources
- [ ] Now-playing and health sources (each behind its own permission)
- [ ] On-device model (Gemma or Gemini Nano) once measured on the phone
- [ ] Share a widget as a link or file, with the same checks on import

## Step 4: release hygiene

- [x] Licence decided: all rights reserved, copyright Mert Mercankaya
- [ ] Final name check for trademark and package-name clashes
- [x] A release signing key kept out of the repository; signed release builds in CI
- [ ] App icon, screenshots, README with honest limits, privacy note listing every host contacted
- [ ] Translations of the app's own text (English, German, Turkish first)
- [ ] 24-hour battery measurement with four widgets
- [ ] Version 1.0 tag and GitHub release

## Known limits that will not change

- Android does not let an app read other apps' private data. Sources are limited to what the
  system exposes with permission, plus public internet data.
- The Pixel Launcher, not this app, decides whether widgets can be stacked by dragging.
- A home-screen widget cannot scroll or animate freely; content that does not fit is dropped.
