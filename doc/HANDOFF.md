# PKMrec – handoff notes (state on 2026-10-24, v0.6.0)

Notes for continuing development in a new chat. Read together with `README.md`, `doc/PLAN.md` and `CHANGELOG.md`.

## Working agreement

- Owner: Pali (GitHub `pcsepregi`). Conversation language: **Hungarian**.
- App UI: **Hungarian**. Everything in the repository (code, comments, commits, docs): **English**.
- Claude writes, builds and releases everything; Pali tests on his phone (OnePlus 13, Android 15+, arm64) and reports back.
- Plan first when asked to plan; do not change code until Pali says go.
- When recognition fails on a card, ask Pali for the camera screen's **"Nyers szöveg" → "Másolás"** report while the camera is still on the card. Real OCR output found every recognition bug so far.

## Repository

- `github.com/pcsepregi/PKMrec` (public). Package `me.csepregi.pkmrec`, single `app` module.
- Kotlin, Jetpack Compose, CameraX, ML Kit Text Recognition (bundled, on-device), OkHttp, Jsoup, kotlinx.serialization, Coil.
- `app/src/main/java/me/csepregi/pkmrec/`
  - `update/` – self-update from GitHub Releases (check, download, SHA-256, PackageInstaller)
  - `tcg/` – TCG Collector: session/cookies, appState extraction, search parsing, card matching, Pokedex list
  - `recognition/` – OCR analyzer, card text parser, frame voting (stabilizer)
  - `ui/` – screens: Home, Settings, Login (WebView), Camera, Lookup (candidates, variants, verdict, Pokedex section)
- Unit tests in `app/src/test/...` (parser, stabilizer, matcher, search page parsing, Pokédex entries, update logic).

## Build and release (important quirks)

- **No local Android build**: Google's Maven/SDK hosts are blocked from the Claude workspace. Everything builds on GitHub Actions.
- `build.yml`: every push to `main` → unit tests + debug APK. Add `[skip ci]` to a commit message to skip it (docs-only changes).
- `release.yml`: runs when the **`VERSION` file** changes on `main`. It builds the signed APK, creates the `vX.Y.Z` tag and the GitHub release itself. Release notes = the `## X.Y.Z` section of `CHANGELOG.md` (shown in the app's update dialog).
- To release: change code → push → wait for `build.yml` green → bump `VERSION`, add `## X.Y.Z` to `CHANGELOG.md` → push → wait for `release.yml`.
- From the Claude session: **git push to `main` works; pushing tags and GitHub API writes (tags, releases, secrets) are blocked**, which is why the release workflow creates tags itself. Workflow **logs cannot be read**; compiler errors and test failures are re-emitted as **annotations** by `.github/scripts/annotate-errors.sh`, readable with:
  `gh api repos/pcsepregi/PKMrec/check-runs/<job id>/annotations`
  (job id from `gh api repos/pcsepregi/PKMrec/actions/runs/<run id>/jobs`).
- Commits: author `pcsepregi <pal@csepregi.me>`, plus the Co-Authored-By / Claude-Session trailers.
- Signing: repository secrets `SIGNING_KEYSTORE_BASE64` and `SIGNING_PASSWORD` (key alias `pkmrec`). Pali keeps the keystore backup. Losing it means users must reinstall.
- APK limited to `arm64-v8a`. Version code = `X*10000 + Y*100 + Z`.

## Self-update

- On start (at most every 6 h) and from Settings → "Frissítés keresése": GitHub Releases `latest` → dialog → download → SHA-256 check → PackageInstaller with `USER_ACTION_NOT_REQUIRED`. Debug builds (`.debug` package) do not self-update.

## TCG Collector integration (read-only)

- Login: TCG Collector's own `/sign-in` page in a WebView. The app never sees the password; OkHttp shares the WebView cookies. Logged-in state = `appState.userDto` present.
- Every page embeds `window.tcgcollector = { appState: {...} }`. Used fields: `cardIdToTcgRegionIdMap`, `cardIdToCardVariantTypeIdsMap`, `idToCardVariantTypeDtoMap` (variant names), `cardIdToCardCollectionCardDtoMap` (owned entries: variant, language, count), `hasMultiplePages`, `userDto`.
- IDs: region International = 1; language English = 1. "Owned" = an English entry of the selected variant.
- Card search: `/cards?cardSearch=<number/total>&displayAs=list` (name, set name, set code, number, rarity per row: `div.card-list-item[data-card-id]`); same with `displayAs=images` for images. Results include other regions → filter region 1.
- Matching: same number (leading zeros ignored) → set code (exact, then loose: glued regulation mark "OMEG", O/0, one wrong char) → fuzzy name → else candidate picker (with "Egyik sem").
- Pokedex list: card page footer links its Pokédex entries (`/pokedex/<n>/<slug>/intl`); `.../pokedex/<n>/<slug>/intl?inUserCardList=<listId>&displayAs=list` returns exactly the list's card for that Pokémon. Default list ID 65678 ("Pokedex"), changeable in Settings; lookup can be switched off in Settings.
- For inspecting pages, Pali can log in to TCG Collector in the Claude desktop app's built-in browser; Claude then reads pages there (read-only).

## Recognition pipeline and lessons learned

- Pass 1: ML Kit on the camera frame. Pass 2 (only when needed): enlarged crop of the card's bottom strip (whole card in view) or of the area around number-like text (when no valid number), read **as is and inverted/high-contrast**.
- Close-ups of the card corner are detected by text size and skip pass 2 when pass 1 already gave a valid reading. 1x/2x zoom button.
- Voting across ~15 frames per field; a number needs ≥3 votes and at least twice the votes of any other number.
- Parser fixes found from real cards: set code glued to "EN" (`PALEN`), set code in a separate text block, separator noise, names relative to the card (not the frame) and not long ability text, numbers next to a set code must be zero-padded (`41/132` is a truncated `141/132`), italic outlined `1/` read as `V` (`14V132` → `141/132`), light-on-dark digits on full-art cards.
- Older cards without set code: number + total + name; same name in two sets (e.g. Buzzwole-GX 57/111 in 30C and CIN) → candidate picker.

## 0.6.0 – stability (done)

Errors are classified in `tcg/TcgErrors.kt` (`TcgError.of(e)`) and shown via `ui/ErrorText.kt`; a page without appState or with unreadable result rows throws `PageFormatException` ("site changed"). Local error log: `diagnostics/ErrorLog.kt` (app private file, copy button in settings, crash handler installed in `PkmrecApp`). Camera opens at 2x and shows start / camera-state errors with a retry button.

## Backlog

See `CHANGELOG.md` under "Unreleased": tests with real anonymised page samples (needs pages saved from a logged-in browser), R8 shrinking.

## Ideas for later

- More home-screen features (scan history, batch scanning).
- Set-symbol recognition for old cards, automatic holo/reverse detection (only if the candidate/variant pickers turn out to be needed often).
