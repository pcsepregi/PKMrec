# PKMrec

PKMrec is a personal Android app for Pokémon TCG collectors who track their collection on [TCG Collector](https://www.tcgcollector.com).

Point the phone's camera at an English Pokémon card and the app tells you, at a glance:

1. **Do I own this exact card?** – same set, same number, same variant (e.g. Normal Holo vs. Reverse Holo), English copy, as recorded in your TCG Collector collection.
2. **Which card of this Pokémon is in my "Pokedex" list?** – the card you picked for this Pokémon in your own TCG Collector list, so you can compare it with the one in your hand.

The app's user interface is in **Hungarian**. All code, comments and documentation in this repository are in **English**.

## Status

Planning phase. See the full plan: [`doc/PLAN.md`](doc/PLAN.md).

## Features (planned for v1)

- Simple home screen with feature buttons (first feature: **Felismerés** / Recognition); more features will be added here later.
- Live camera card recognition (on-device OCR of the set code and card number printed at the bottom of the card).
- Lookup on TCG Collector using your own logged-in session.
- Variant picker: all variants of the recognised card are listed with an owned/not-owned marker; you tap the one you are holding.
- Big, clearly visible **MEGVAN** (owned) / **NINCS MEG** (not owned) result.
- The card from your "Pokedex" list for the same Pokémon is shown below the result.
- Built-in self-update: on start the app checks GitHub Releases for a newer version and offers to download and install it.

## Scope and limitations

- English (International TCG region) cards only; only English copies in the collection count as "owned".
- Read-only: the app never changes your TCG Collector collection or lists.
- TCG Collector has no public API. The app reads the same pages you see in a browser, using your own session. If the website changes, the app may need an update. This is intended for personal use only.
- Your TCG Collector password is entered only on the TCG Collector login page inside the app and is never stored by the app; only the session cookie is kept on the phone.

## Installing

The app is distributed as a signed APK via this repository's [Releases](../../releases) page (sideloading). After the first install, updates are offered by the app itself.

1. Download the latest `PKMrec-<version>.apk` from Releases on the phone.
2. Allow installing apps from that source when Android asks.
3. Open the app and log in to TCG Collector once.

## Building

Builds run on GitHub Actions; no local Android toolchain is needed. Every push to `main` builds the app; changing the `VERSION` file publishes a signed release APK (tag and GitHub release are created by the workflow). Details are in [`doc/PLAN.md`](doc/PLAN.md#9-build-and-release).

## Tech stack

Kotlin · Jetpack Compose · CameraX · Google ML Kit Text Recognition (on-device) · OkHttp · kotlinx.serialization · Room/DataStore · GitHub Actions
