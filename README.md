# PKMrec

PKMrec is a personal Android app for Pokémon TCG collectors who track their collection on [TCG Collector](https://www.tcgcollector.com).

Point the phone's camera at an English Pokémon card and the app tells you, at a glance:

1. **Do I own this exact card?** – same set, same number, same variant (e.g. Normal Holo vs. Reverse Holo), English copy, as recorded in your TCG Collector collection.
2. **Which card of this Pokémon is in my "Pokedex" list?** – the card you picked for this Pokémon in your own TCG Collector list, so you can compare it with the one in your hand.

The app's user interface is in **Hungarian**. All code, comments and documentation in this repository are in **English**.

## Status

Version 1.0.0: all milestones of the original plan are done, plus several features added since (Pokédex search, Cardmarket price, set symbol matching, debug mode). See [`CHANGELOG.md`](CHANGELOG.md) for every release and [`doc/PLAN.md`](doc/PLAN.md) for the original plan.

## Features

- Home screen with feature buttons: **Felismerés** (recognition) and **Pokédex**.
- Live camera card recognition: on-device OCR of the card number, set code and Pokémon name; for older cards and ambiguous results, the set symbol next to the number is compared with the symbols of all English sets.
- Lookup on TCG Collector using your own logged-in session.
- Variant picker: all variants of the recognised card are listed with an owned/not-owned marker; you tap the one you are holding.
- Big, clearly visible **MEGVAN** (owned) / **NINCS MEG** (not owned) result.
- The card from your "Pokedex" list for the same Pokémon is shown below the result.
- **Pokédex search**: type a Pokémon name (offline name correction for all 1025 names) and see the card from your "Pokedex" list with image, type, set, number, rarity and variants.
- **Cardmarket price** on card screens: English offers in Good or better condition, the 2 cheapest skipped, the next 5 averaged; also shown in forints using the European Central Bank's daily EUR–HUF rate. When Cardmarket cannot be reached, the last known price is shown with its date.
- Built-in self-update: on start the app checks GitHub Releases for a newer version and offers to download and install it (SHA-256 checked).
- Diagnostics kept only on the phone: an error log and an optional debug mode that records slow recognitions; both can be copied from Settings to report a problem.

## Scope and limitations

- English (International TCG region) cards only; only English copies in the collection count as "owned".
- Read-only: the app never changes your TCG Collector collection or lists.
- TCG Collector has no public API. The app reads the same pages you see in a browser, using your own session. If the website changes, the app may need an update. This is intended for personal use only.
- Your TCG Collector password is entered only on the TCG Collector login page inside the app and is never stored by the app; only the session cookie is kept on the phone.
- Cardmarket has no open API either: prices are read from Cardmarket's public pages in an invisible browser view on the phone, so they also depend on the website staying the same.

## Installing

The app is distributed as a signed APK via this repository's [Releases](../../releases) page (sideloading). After the first install, updates are offered by the app itself.

1. Download the latest `PKMrec-<version>.apk` from Releases on the phone.
2. Allow installing apps from that source when Android asks.
3. Open the app and log in to TCG Collector once.

## Building

Builds run on GitHub Actions; no local Android toolchain is needed. Every push to `main` builds the app; changing the `VERSION` file publishes a signed release APK (tag and GitHub release are created by the workflow). Details are in [`doc/PLAN.md`](doc/PLAN.md#9-build-and-release) and [`doc/HANDOFF.md`](doc/HANDOFF.md).

Set symbols come from [pokemontcg.io](https://pokemontcg.io). A copy is kept in `app/src/main/assets/set_symbols/` and refreshed weekly by the "Set symbols" workflow; every build tries a fresh download first and keeps the copy from git for any set it cannot download.

## Tech stack

Kotlin · Jetpack Compose · CameraX · Google ML Kit Text Recognition (on-device) · OkHttp · Jsoup · kotlinx.serialization · Coil · Android WebView · GitHub Actions
