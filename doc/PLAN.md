# PKMrec – Project plan

This document is the full plan for the first version (v1) of PKMrec. It is written in English; the app's user interface is in Hungarian.

## 1. Goal

A personal Android app that recognises an English Pokémon card through the phone camera and answers two questions using the owner's TCG Collector account:

1. **Owned?** Is this exact card – same expansion, same card number, same variant, English copy – recorded in my TCG Collector collection?
2. **Pokedex list card:** Which card of the same Pokémon is in my TCG Collector list named "Pokedex"?

### Decisions made

| Topic | Decision |
|---|---|
| Card languages scanned | English (International TCG region) only |
| "Owned" means | A collection entry exists for this exact card **and** the selected variant **and** language = English. Copies in other languages do not count (they are shown as a small grey note). |
| Variant handling (v1) | **Option A:** after recognition, show all variants of the card with an owned/not-owned marker; the user taps the variant they are holding. No automatic holo/reverse detection in v1. |
| TCG Collector access | Read-only. Login with e-mail + password on TCG Collector's own login page inside an in-app WebView. Premium account. |
| Pokedex list | User-created TCG Collector list, one card per Pokémon (currently 1025/1025). Read-only in v1. |
| Target device | OnePlus 13 (Android 15+). Personal use, sideloaded APK. |
| Languages | UI: Hungarian. Code, comments, commits, docs: English. |
| Updates | In-app self-update from GitHub Releases (see §8). |
| Development | Code is written and built by Claude; builds run on GitHub Actions. |

## 2. User flow

```
App start
  └─ Update check (background) ──► "Új verzió érhető el" dialog (if newer release)
  └─ Home screen
       ├─ [Felismerés]  (first feature; more buttons later)
       └─ status line: "Bejelentkezve" / "Nincs bejelentkezve – Belépés"

Felismerés
  ├─ Not logged in? ──► Login screen (WebView, TCG Collector login page)
  ├─ Camera screen (live preview, OCR running)
  │     └─ stable read of set code + number (+ name) ──► vibrate, continue
  ├─ Lookup on TCG Collector
  │     ├─ exactly one candidate ──► continue
  │     └─ several candidates ──► candidate picker (image + set + number)
  ├─ Variant picker: all variants, each with ✓ / ✗ (English copy owned)
  │     └─ user taps the variant in hand
  └─ Result screen
        ├─ huge MEGVAN (green) / NINCS MEG (red)
        ├─ recognised card: image, name, expansion, number, variant
        ├─ grey note if owned only in another language (e.g. "Németül megvan")
        ├─ "A Pokedex listádban:" card image, name, expansion, number
        │     (or "Ebből a Pokémonból még nincs kártya a listádban")
        └─ [Új szkennelés]  [Kártya megnyitása a TCG Collectoron]
```

## 3. Screens (UI text in Hungarian)

| Screen | Purpose | Key UI text |
|---|---|---|
| Home | Feature launcher, login status | „Felismerés", „Bejelentkezve", „Belépés", „Beállítások" |
| Login | WebView with TCG Collector login page; closes automatically once logged in | „Jelentkezz be a TCG Collectorba" |
| Camera | Live preview, framing guide over the bottom strip of the card, shows what OCR currently reads | „Tartsd a kártya alját a keretben" |
| Candidate picker | Only if several cards match | „Melyik kártya ez?" |
| Variant picker | All variants with ✓/✗ | „Melyik változat van a kezedben?" |
| Result | Owned verdict + Pokedex list card | „MEGVAN" / „NINCS MEG", „A Pokedex listádban:" |
| Settings | Pokedex list URL/ID, refresh list cache, log out, app version, manual update check | „Pokedex lista", „Lista frissítése", „Kijelentkezés", „Frissítés keresése" |
| Update dialog | Offer new version | „Új verzió érhető el (x.y.z). Frissíted?" – „Frissítés" / „Később" |

## 4. Architecture

Single-module Android app, Kotlin, Jetpack Compose, MVVM with a small repository layer.

```
app/
  ui/            Compose screens + ViewModels (home, login, camera, pickers, result, settings, update)
  camera/        CameraX preview + ImageAnalysis, ML Kit text recognizer, card-text parser
  tcg/           TCG Collector client: HTTP, session, appState JSON extraction, models
  pokedex/       Pokedex list cache (download, store, lookup by Pokédex number)
  update/        GitHub Releases check, APK download, PackageInstaller
  data/          DataStore (settings), Room or JSON file cache (Pokedex list)
```

### Main libraries

- **Jetpack Compose** + Navigation Compose – UI
- **CameraX** (preview + ImageAnalysis) – camera
- **ML Kit Text Recognition v2 (Latin, bundled model)** – on-device OCR, works offline
- **OkHttp** – HTTP; cookies shared with the WebView via `CookieManager`
- **kotlinx.serialization** – JSON parsing
- **Coil** – card images
- **DataStore** – settings; **Room** (or a JSON file) – Pokedex list cache

## 5. Card recognition

### What is read

The bottom strip of a modern English card contains, among other things:

- **Set code** (Scarlet & Violet era and later), e.g. `PAL`, `SVI`, `30C`
- **Card number / set total**, e.g. `015/128`, or a promo number such as `SVP 123`
- **Regulation mark** (e.g. `G`, `H`, `J`)

The card **name** at the top is read as a secondary signal.

### Pipeline

1. CameraX `ImageAnalysis` frames (≈ 1280×720) go to ML Kit text recognition.
2. A parser looks for patterns:
   - number: `\b(\d{1,3})\s*/\s*(\d{2,3})\b`, promo / alphanumeric numbers (`TG05/TG30`, `GG01/GG70`, `SWSH123`, `SVP 045`)
   - set code: 2–4 uppercase letters/digits next to the number or regulation mark
   - name: largest text block in the top area
3. A read is accepted when the same (set code, number) is seen in **3 consecutive frames**; then the phone vibrates and moves on.
4. Fallback: a **"Kézi bevitel"** (manual entry) button to type the number (and optionally the set code) if OCR struggles.

### Known hard cases

- Older cards (before Scarlet & Violet) have no printed set code, only a set symbol → matched by number + set total + name (see §6).
- Holo foil glare → guidance text, the user tilts the card; torch toggle on the camera screen.
- Variant (holo / reverse holo / stamped) is **not** detected from the image in v1 → variant picker.

## 6. TCG Collector integration

### Findings (from inspecting the logged-in website)

Every TCG Collector page embeds a structured state object in an inline script:

```js
window.tcgcollector = { appState: { ... } }
```

The app extracts this JSON from the HTML instead of scraping visible markup. Relevant fields:

| Field | Meaning |
|---|---|
| `cardIds` | Cards shown on the page |
| `cardIdToCardCollectionCardDtoMap` | Per card: `entries[]` with `cardVariantTypeId`, `cardLanguageId`, `cardConditionId`, `cardCount` – i.e. what the user owns |
| `cardIdToCardVariantTypeIdsMap` | Per card: the variant type IDs that exist for this card |
| `idToCardVariantTypeDtoMap` | Variant type ID → name (e.g. `363` = "Normal Holo") and flags |
| `cardIdToTcgRegionIdMap` | Per card: TCG region (`1` = International / English) |
| `totalCardCount`, `hasMultiplePages` | Paging of card lists |
| `userDto` | Logged-in user (used only to verify that the session is valid) |

Other useful facts:

- **Card search:** `GET /cards?cardSearch=<query>&displayAs=list` – searching the card number (e.g. `015/128`) returns all matching cards across regions; each result link carries the full card title, e.g. "Fuecoco ex (30th Celebration 015/128)", and the list row shows the set code (e.g. `30C`).
- **Card page:** `GET /cards/<id>/<slug>` – contains expansion name + set code, card number, rarity and the **Pokédex number** (e.g. `#0909`).
- **List page:** `GET /lists/<listId>/<slug>?displayAs=list&page=N` – 240 cards per page; supports `cardSearch` and sorting (including by Pokédex number).
- The English language ID must be confirmed at implementation time (`cardLanguageId` of English entries; expected `1`).

### Lookup algorithm

1. Search `cardSearch=<number>/<total>` (or the promo number).
2. Keep only results with region = International (`1`).
3. Filter by set code if one was read; otherwise by set total and fuzzy name match.
4. One result → selected; several → candidate picker; none → "Nem találtam ilyen kártyát" + manual entry.
5. From the search page's `appState` (or the card page if needed): variants of the card and the user's collection entries.
6. Variant picker: for each variant, **owned** = an entry exists with that `cardVariantTypeId`, `cardLanguageId` = English and `cardCount > 0`. Entries in other languages produce the grey note.

### Pokedex list

1. On first use (and on "Lista frissítése" in settings) the app downloads all pages of the configured list and stores, for every card: card ID, name, expansion, number, image URL and Pokédex number.
2. The Pokédex number of each list card is taken from the list sorted by Pokédex number, verified via the card page where ambiguous (cards of non-Pokémon or multi-Pokémon cards are skipped).
3. Lookup: Pokédex number of the scanned card (from its card page) → list card with the same Pokédex number.
4. The cache is refreshed automatically if older than 7 days, and manually from settings.
5. The list is configured by URL/ID in settings (default: the owner's "Pokedex" list).

### Session and login

- The Login screen opens TCG Collector's login page in a WebView; the user types e-mail and password there. The app never reads or stores the password.
- After login the WebView's cookies are kept by Android's `CookieManager`; OkHttp uses the same cookies.
- Session check: fetch any page, verify `appState.userDto` is present. If the session expired → back to the Login screen.
- If plain HTTP requests are ever blocked (e.g. bot protection), fallback: load pages in a hidden WebView and read `window.tcgcollector.appState` via JavaScript.
- Politeness: requests only on user action (plus the weekly list refresh), short in-memory cache, normal browser user agent, no parallel bursts.

### Risks

- TCG Collector has no public API; markup or `appState` structure can change → the parser is isolated in `tcg/` and covered by tests on saved HTML samples, so a fix is quick.
- Automated access may not be covered by TCG Collector's terms of service; this app is for personal, low-volume, read-only use of the owner's own account.

## 7. Result logic

| Situation | Display |
|---|---|
| Selected variant owned in English | Green **MEGVAN** |
| Not owned in English, owned in another language | Red **NINCS MEG** + grey "…nyelven megvan" |
| Not owned at all | Red **NINCS MEG** |
| Pokedex list has a card with the same Pokédex number | Card image + "A Pokedex listádban: <name> (<expansion> <number>)"; if it is the very same card: "Ez van a listádban" |
| No list card for this Pokémon / not a Pokémon card | "Ebből a Pokémonból még nincs kártya a listádban" / section hidden for Trainer and Energy cards |

## 8. In-app self-update

### Behaviour

1. On app start (at most once every 6 hours, and on "Frissítés keresése" in settings) the app calls the GitHub Releases API:
   `GET https://api.github.com/repos/pcsepregi/PKMrec/releases/latest` (public repo, no token needed).
2. It compares the release tag `vX.Y.Z` with the installed `versionName` (and the `versionCode` encoded in the release, see below).
3. If newer, a dialog appears: **„Új verzió érhető el (X.Y.Z). Frissíted?"** with the release notes, buttons **„Frissítés"** / **„Később"**.
4. On „Frissítés": the APK asset is downloaded with a progress bar, its SHA-256 is checked against the checksum published in the release, and it is installed via Android's `PackageInstaller` session API.

### Android constraints (honest expectations)

- The app needs the `REQUEST_INSTALL_PACKAGES` permission; the first time, Android asks the user to allow "Install unknown apps" for PKMrec (one-time setting).
- Android normally shows a system confirmation before installing. On Android 12+ the app requests `USER_ACTION_NOT_REQUIRED`; this lets updates install without the extra confirmation once PKMrec itself is the app's installer of record – typically from the second self-update on. A fully silent first update is not possible for a sideloaded app.
- Updates only install if signed with the **same signing key** → the release keystore is generated once and stored as encrypted GitHub Actions secrets (§9). Losing it would require uninstalling and reinstalling the app.

### Versioning

- `versionName` = `X.Y.Z` from the git tag; `versionCode` = `X*10000 + Y*100 + Z`.
- Release assets: `PKMrec-X.Y.Z.apk` and `PKMrec-X.Y.Z.apk.sha256`.

## 9. Build and release

- **Build system:** Gradle (Kotlin DSL), version catalog, JDK 17, `compileSdk`/`targetSdk` = latest stable, `minSdk` = 31.
- **CI (GitHub Actions):**
  - `build.yml` – on every push / PR: build debug APK, run unit tests, lint; APK attached as a workflow artifact.
  - `release.yml` – on tag `v*`: build signed release APK, compute SHA-256, create a GitHub Release with notes and both assets.
- **Signing:** keystore generated once; stored as repository secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. Never committed.
- **Tests:** unit tests for the OCR text parser and for the `appState` extractor, using saved (anonymised) HTML samples in `app/src/test/resources`.

## 10. Milestones

Each milestone ends with a release APK the owner can install and try.

| # | Milestone | Content | Done when |
|---|---|---|---|
| M1 | Skeleton + CI + self-update | Project, home screen with „Felismerés" button (placeholder), settings screen, GitHub Actions build/release with signing, in-app update check + install | v0.1.0 installs; v0.1.1 is offered and installed by the app itself |
| M2 | Login + session | WebView login, cookie sharing, session check, status on home screen | Home shows „Bejelentkezve" after login and survives restarts |
| M3 | Camera + OCR | CameraX screen, ML Kit, parser, stable-read logic, torch, manual entry | The app shows the correct set code + number for test cards |
| M4 | Lookup + owned check | Search, candidate picker, variant picker, MEGVAN / NINCS MEG | Correct verdict for owned and not-owned test cards, incl. variants and other-language copies |
| M5 | Pokedex list | List download + cache, Pokédex number lookup, result section, refresh in settings | Correct list card shown for test cards |
| M6 | Polish | Error states (no network, session expired, not found), Hungarian texts review, icon | v1.0.0 |

## 11. Future ideas (not in v1)

- More home-screen features (e.g. scan history, batch scanning, adding a card to the collection or a list).
- Automatic variant detection (holo / reverse holo) from the image.
- Set-symbol recognition for older cards without a printed set code.
- Other languages / TCG regions.
