# Changelog

Release notes shown in the app's update dialog are taken from the matching section below.

## Unreleased

- Enable R8 code shrinking for a smaller APK.

## 1.0.1

- Pokédex search: the name field is empty again when you come back from a result.

## 1.0.0

PKMrec 1.0: the first full version. Everything planned for version 1 is done: card recognition with the camera, owned check with variants on TCG Collector, the card from your Pokedex list, Pokédex search, Cardmarket price in euros and forints, and self-update.
- No changes in how the app works since 0.12.1.
- New tests on real TCG Collector pages, so a change on the website is noticed when the app is built.

## 0.12.1

Improvements:
- Cardmarket price: if the price cannot be loaded (no internet, Cardmarket slow), the last known price is shown with its date instead of only an error.
- Cardmarket price: new "Újrakeresés" button searches for the card on Cardmarket again, in case it was matched to the wrong product.
- The forint price shows the date of the exchange rate when it is more than a week old.
- Builds no longer fail when pokemontcg.io (set symbols) is down: a copy of the symbols is kept in the repository.
- README and developer notes updated.

## 0.12.0

New: debug mode for slow recognitions.
- Settings: "Debug mód" section with a "Lassú felismerések naplózása" switch (off by default).
- When it is on, the camera screen times each recognition attempt from the moment a card is in view: at least 2 kinds of card text in one frame (HP, weakness/resistance/retreat, ability, a card number, a Pokémon name from the name list). An attempt ends when the card is recognized, a manual entry is made, the scanner is closed, or no card has been seen for 2 seconds.
- Attempts longer than 5 seconds are saved on the phone with how they ended, the readings seen meanwhile and the raw text report of the last frame with the card. The newest 50 are kept.
- "Összes másolása" copies them all to the clipboard, "Törlés" clears them.
- In debug mode a line under the live reading shows which card text the current frame has and how long the attempt has been running.

## 0.11.1

- The Cardmarket price also shows the forint value, e.g. "8,04 € (≈ 2 949 Ft)". The EUR–HUF rate is the European Central Bank's daily reference rate, downloaded when the app starts (at most every 6 hours) and kept on the phone.

## 0.11.0

New: Cardmarket price on the card screens.
- The recognition result and the Pokédex result show the card's Cardmarket price: English offers in Good or better condition, the 2 cheapest skipped, the average of the next 5. For a reverse holo variant only reverse holo offers count.
- The price loads after the rest of the screen and is kept for 24 hours; "Frissítés" loads it again, "Megnyitás a Cardmarketen" opens the filtered offers in the browser.
- With only 1–2 offers the cheapest price is shown as a guide; with fewer than 7 the note says how many there are.
- If Cardmarket asks for a check, a button shows Cardmarket so the check can pass.
- Settings: "Cardmarket ár mutatása" switch, and the test screen moved to the bottom as "Cardmarket hibakeresés".
- Settings: "Lista megtekintése" shows the Pokémon name list, with a filter by name or number.

## 0.10.1

Test build for the Cardmarket price.
- Settings: new "Cardmarket ár (próba)" screen. It opens Cardmarket in the app, applies the English / Good or better filter on a card's page and computes the price (2 cheapest skipped, average of the next 5).
- "Árlista teszt" downloads Cardmarket's public daily price guide and shows the card's average and trend prices from it.
- "Jelentés másolása" copies what the app saw, to fine-tune the price reading.

## 0.10.0

New: set symbol recognition, for older cards without a printed set code.
- When the number fits cards from several sets (e.g. 25/73 is in both Shining Legends and Champion's Path), the set symbol printed next to the number is compared with the symbols of those sets. If one clearly matches, that card is chosen automatically ("Másik kártya" goes back to the list).
- Otherwise the list shows each set's symbol and how well it matched the card.
- The symbols of all English sets are built into the app.
- Fix: other text in view (e.g. a web address or prices when a card is shown on a screen) is no longer taken for the card number. A number without a slash ("2573") is only read from the card's bottom strip, and that strip ends closer to the weakness line.
- The camera's raw text report also shows the line positions and whether the symbol area was cut out, to help fix recognition problems.

## 0.9.0

Faster and more reliable card recognition, mostly for older cards (before Scarlet & Violet) that have no set code.
- The enlarged second reading looks at the card's own bottom strip (below the weakness / retreat line), not at text below the card.
- The set symbol in front of the number no longer spoils it when it is read as a letter or digit (e.g. "F0109/172" → 109/172).
- A number whose slash was not read ("2573") is understood as 25/73 when only one reading makes sense.
- The card name must be a Pokémon's name (from the built-in name list), so attack and ability names ("Blessings of the Deep", "Cablegram") are no longer taken as the name. Trainer cards keep working as before.
- Older cards are confirmed sooner: cards from sets of under 100 cards (e.g. 25/73) are confirmed without waiting for a set code, and other cards without one after 4 readings instead of 6.

## 0.8.0

New look: dark neon blue theme for the whole app.
- Home screen: bigger logo with a two-line title, round settings button, login card with the premium badge, glowing feature buttons with arrows.
- Dark blue background with a faint Poké Ball behind every screen; light status bar icons.

## 0.7.1

- Home screen: new icons for the two buttons (scanner frame for card recognition, magnifier "P" for Pokédex), and both buttons are the same height.

## 0.7.0

New: Pokédex search.
- New "Pokédex" button on the home screen: type a Pokémon's name and see which card of it is in your Pokedex list.
- Typos and Hungarian spelling are corrected while you type (e.g. "Eve" → Eevee, "Pikacsu" → Pikachu, "Csarmander" → Charmander); the Pokédex number works too (e.g. 133).
- The result shows the card image (tap to enlarge), name, type (e.g. Fire, Psychic), HP, set, number, rarity and how many copies you own of each variant.
- The list of Pokémon names is built into the app, so the correction works offline. Settings: "Pokémon névlista frissítése" downloads the current list from TCG Collector when a new generation comes out.

## 0.6.0

Stability release.

Camera:
- Opens at 2x zoom (the 1x/2x button stays to switch back).
- Camera start failures (camera busy, used by another app or disabled) show a message with a retry button instead of crashing.
- Fewer bitmaps per frame in the second recognition pass: no rotated copy of the whole frame, the enlarged crop and its inverted copy reuse the same memory.

Error handling:
- The Pokedex list lookup checks the login too; when the session has expired it asks to log in again instead of wrongly showing "no card in your list".
- Clear Hungarian error messages instead of raw technical text: no internet, timeout, too many requests to TCG Collector (HTTP 429), TCG Collector unavailable or under maintenance.
- A changed TCG Collector page format (no appState, or results that cannot be read) is reported as "TCG Collector changed, the app needs an update" instead of "card not found".
- Local error log on the phone (nothing is sent anywhere): settings has a "Hibanapló másolása" button that copies it with the app version and phone model.
- Unit tests for error classification, page format changes and the error log.

## 0.5.6

- Fixes Clawitzer MEG 141/132 and similar full-art cards: the italic "1/" that the text recognizer reads as "V" ("14V132") is now understood as "141/132".

## 0.5.5

- Light-on-dark card numbers (white outlined digits on full-art cards, e.g. Clawitzer MEG 141/132) are re-read from an enlarged, inverted high-contrast copy when the first reading fails.

## 0.5.4

- Better recognition of full-art cards (e.g. Illustration Rare): partially read numbers ("41/132" instead of "141/132") are ignored, a set code glued to the regulation mark ("OMEG") still finds the card, ability text is no longer taken as the card name.

## 0.5.3

- Card rarity (e.g. "Double Rare") is shown next to the card number, also for the Pokedex list card.

## 0.5.2

- Camera screen: all buttons are now at the bottom, the explanation at the top.
- Card picker: new "Egyik sem" option, back to the camera.
- Settings: switch to turn the Pokedex list lookup on or off (on by default).

## 0.5.1

- Faster recognition: the second, enlarged reading only runs when the whole card is in view.
- Works with a close-up of just the card's bottom-left corner (set code and number).
- Fewer misreads: a number is only accepted with a clear majority of the camera frames.
- 1x/2x zoom button for sharp close-ups.

## 0.5.0

- The result shows which card of the same Pokémon is in your "Pokedex" list (image, set, number), or that there is none yet.
- Marks when the scanned card is exactly the one in your list; cards with several Pokémon show each.
- Settings: choose the Pokedex list by link or ID, open it on TCG Collector.

## 0.4.0

- After scanning, the card is looked up on TCG Collector (English cards only).
- If several cards match (e.g. older cards without set code), you pick the right one by image.
- Pick the variant you hold; each variant shows whether you own it in English.
- Big MEGVAN / NINCS MEG result with the official card name and image; copies in other languages are noted.

## 0.3.3

- New app icon and logo on the home screen.

## 0.3.2

- Better reading of cards in sleeves and binders: frames are combined field by field, so glare in one frame is compensated by another.
- The bottom strip of the card is read a second time, enlarged.
- Slightly darker exposure against glare, focus on the card's bottom strip, tap to focus.

## 0.3.1

- Better set code reading (also when the code is glued to "EN" or in a separate text block).
- Better card name reading.
- New "Nyers szöveg" button on the camera screen: shows what the text recognizer sees and can copy it.
- Smaller download (64-bit ARM only).

## 0.3.0

- "Felismerés" opens the camera and reads the set code and card number from the bottom of the card.
- The phone vibrates when the card is recognised; torch toggle and manual entry.
- TCG Collector lookup comes in the next version.

## 0.2.0

- Log in to TCG Collector inside the app (on TCG Collector's own sign-in page; the app never sees the password).
- Login status on the home screen and in settings; logout in settings.
- "Felismerés" opens the login screen when you are not logged in.

## 0.1.1

- Test release for the in-app self-update.

## 0.1.0

- First release: home screen with the "Felismerés" feature (coming soon), settings screen.
- In-app self-update from GitHub Releases.
