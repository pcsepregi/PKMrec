# PKMrec – handoff notes (state on 2026-10-24, v0.7.0)

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
  - `pokedex/` – Pokémon name list (bundled `assets/pokemon_names.tsv`, refreshable from TCG Collector) and name correction
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
- Pokédex search (0.7.0): names come from `/pokedex` (`a.pokedex-grid-item-name`, href `/pokedex/<n>/<slug>`; 1025 entries in Oct 2026). Lookup goes straight to `/pokedex/<n>/<slug>/intl?inUserCardList=<listId>`; the card page header gives the energy type (`#card-energy-types img[alt]`) and HP (`#card-hit-points-value`). Name correction (`NameMatcher`): accents/punctuation dropped, Hungarian spelling (dzs→j, cs→ch, sz→s), prefix match, then edit distance with transpositions.
- For inspecting pages, Pali can log in to TCG Collector in the Claude desktop app's built-in browser; Claude then reads pages there (read-only).

## Recognition pipeline and lessons learned

- Pass 1: ML Kit on the camera frame. Pass 2 (only when needed): enlarged crop of the card's bottom strip (whole card in view) or of the area around number-like text (when no valid number), read **as is and inverted/high-contrast**.
- Close-ups of the card corner are detected by text size and skip pass 2 when pass 1 already gave a valid reading. 1x/2x zoom button.
- Voting across ~15 frames per field; a number needs ≥3 votes and at least twice the votes of any other number.
- Parser fixes found from real cards: set code glued to "EN" (`PALEN`), set code in a separate text block, separator noise, names relative to the card (not the frame) and not long ability text, numbers next to a set code must be zero-padded (`41/132` is a truncated `141/132`), italic outlined `1/` read as `V` (`14V132` → `141/132`), light-on-dark digits on full-art cards.
- Older cards without set code: number + total + name; same name in two sets (e.g. Buzzwole-GX 57/111 in 30C and CIN) → candidate picker.
- 0.9.0 (from Pali's OCR reports, cards shown on a monitor): the bottom strip is anchored on the weakness/retreat line (text below the card pulled it off the card); the set symbol is read as glued characters ("F0109/172", "A A SVI4/SV9"); slash-less numbers ("2573"); ability/attack names taken as the card name → names must match the bundled Pokémon list (`PokemonNameFinder`) unless trainer/energy markers are present; two-digit totals (25/73) confirm without waiting for a code.
- Same number/total in two sets with no name visible (corner close-up), e.g. 25/73 in Shining Legends and Champion's Path: only the set symbol can decide.
- 0.10.0 set symbols: the build downloads all English set symbols from pokemontcg.io (`.github/scripts/fetch-set-symbols.py` → `assets/set_symbols/`, gitignored; releases need at least 100). `CardTextAnalyzer` cuts a greyscale crop next to the number line from the camera's Y plane (`symbolArea`, scaled so the number is 20 px tall); `CameraScreen` passes the crop of the confirmed number via `CurrentScan.symbol`. Only for ambiguous results, `SetSymbolChoice` maps candidates to symbols (`SetSymbolIndex`: set code, then name) and `SymbolMatcher` (normalised cross-correlation, several sizes, over white and black) scores them; auto-pick needs score ≥ 0.5 and a 0.1 lead. Thresholds are untested on real cards: tune from the scores shown in the picker. The symbol is next to the number from Sun & Moon on; older layouts are not covered.

## 0.6.0 – stability (done)

Errors are classified in `tcg/TcgErrors.kt` (`TcgError.of(e)`) and shown via `ui/ErrorText.kt`; a page without appState or with unreadable result rows throws `PageFormatException` ("site changed"). Local error log: `diagnostics/ErrorLog.kt` (app private file, copy button in settings, crash handler installed in `PkmrecApp`). Camera opens at 2x and shows start / camera-state errors with a retry button.

## 0.7.0 – Pokédex search (done)

Home screen "Pokédex" button → `PokedexSearchScreen` (suggestions while typing) → `PokedexResultScreen` (list card with image, type, HP, set, number, rarity, variants). Settings: "Pokémon névlista frissítése". Decisions by Pali: only the Pokedex list, English names only, design to be polished in a later release.

## 0.8.0 – neon theme (done)

Whole app uses one dark colour scheme (`ui/Theme.kt`, `Neon` colours); the scheme's background is transparent and `NeonBackground` (`ui/NeonDecor.kt`) is drawn once behind the NavHost, so screen transitions are switched off. Home screen follows Pali's mockup; icons from `material-icons-core`.

## 0.11.0 – Cardmarket price (done)

Pali's rule: English offers, Good or better, skip the 2 cheapest, average the next 5 (TCG Collector prices are not acceptable to him). The Cardmarket API is closed to new users and Cloudflare stops plain HTTP, so `cardmarket/CardmarketFetcher` loads pages in an invisible WebView on the phone and reads them with `CARDMARKET_EXTRACT_JS` (rows `.article-row`, condition badge, language from `aria-label`, reverse holo from an attribute "Reverse Holo" — the last one unverified). Product: name search (`/Products/Search?searchString=<name>&site=<n>`, Cardmarket ignores codes and numbers), then the result labelled "Name (CODE NUM)" with the card's set code and number (numbers compared without leading zeros; `CardmarketMatcher.CODE_ALIASES` for codes that differ). Offers: product URL + `?language=1&minCondition=4`. Product links are kept for good, prices for 24 h (`CardmarketPrices`). Settings → "Cardmarket hibakeresés" opens the visible debug screen with a copyable report (first row HTML) to fix selectors.

## Backlog

See `CHANGELOG.md` under "Unreleased": tests with real anonymised page samples (needs pages saved from a logged-in browser), R8 shrinking.

## Ideas for later

- More home-screen features (scan history, batch scanning).
- Set-symbol recognition for old cards, automatic holo/reverse detection (only if the candidate/variant pickers turn out to be needed often).
