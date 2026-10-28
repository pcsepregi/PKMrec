# Changelog

Release notes shown in the app's update dialog are taken from the matching section below.

## Unreleased

- Enable R8 code shrinking for a smaller APK.

## 2.0.6

- Debug mode: new "TCG Collector kérés-figyelő" in Settings. It opens TCG Collector in the app and records the requests the site sends while you do something by hand (e.g. add a card to a list), with passwords, tokens and e-mail addresses masked, ready to copy. This is the groundwork for changing the Pokedex list from the app.

## 2.0.5

- TCG Collector refused many searches (HTTP 403) after the app sent them too quickly, mainly in Tömeges eladás. The app now waits at least a second between TCG Collector requests and retries a refused one once after 10 seconds.
- When TCG Collector still refuses, a clear message is shown with a "TCG Collector megnyitása" button; in Tömeges eladás the remaining cards are not sent, and "Sikertelenek újrakeresése" searches the failed ones again.
- Tömeges eladás searches the same name only once per run.
- Camera recognition: when the number finds a card whose name differs from the name read on the card (e.g. a misread promo number), the cards of that name in the set are offered next to it to pick from.

## 2.0.4

- Recognition: promo numbers are read also without "EN" between the code and the number ("D SVP 209", "SVPm 209").
- Recognition: when the set code and the card name are readable but the number is not (a tiny promo number, e.g. "Pikachu at the Museum" MEP 500 on a screen), after a few frames the card is looked up by its name in that set. One card of that name in the set → it is shown; several → you pick.
- Tömeges eladás benefits from the promo number fix too ("DMEP O31").

## 2.0.3

- Tömeges eladás: the set code is now found also when OCR glues it to the regulation mark or "EN" ("DMEG", "IMEGB", "0MEG"), so a card number finds the one card of that set instead of a list to pick from.
- A card whose number is unreadable but whose set code is readable ("D MEG o0a/ 132") is looked up by its name among your cards of that set.
- Each name on the photo is the title of one card only: neighbouring cards no longer take each other's names, and a name next to a numbered card is no longer lost.

## 2.0.2

- Tömeges eladás: on table photos most card numbers are too small or shiny to read, but the card names are readable. A card whose number cannot be read is now found by its name: Pokémon names (also when "BASIC"/"STAGE", HP or "ex" is glued to them) and trainer names under the "Supporter" / "Item" / "Pokémon Tool" label. Only the cards in your collection with that name are offered; with several, you pick.
- A number that finds nothing is tried by the card's name as well; when the number finds a card with another name than the one on the photo, both are offered to pick from.
- Numbers read with the slash lost ("224182" = 224/182) are understood; cut-off readings ("110/01", "10/085") are no longer taken as numbers.

## 2.0.1

- Home screen: "Tömeges eladás" is now the last button, and all four buttons fit on the screen without scrolling.
- Tömeges eladás finds cards on real table photos: the photo is now also read in overlapping pieces enlarged twice (card numbers are only about 20 pixels tall on such a photo), and cards lying at an angle are handled (each card's name is looked for along the card's own slope). Numbers with the set code box read into them ("PBL#115/084") or with the slash read as a 1 ("1121083") are understood too.
- Debug mode: Tömeges eladás has a "Beolvasott szöveg másolása" button with everything read on the photos, for tuning.

## 2.0.0

- New main-screen menu "Tömeges eladás": take photos of the cards you sold with the camera button on the screen, or pick photos from the gallery, then press "Azonosítás". The app finds every card number on the photos and looks each card up on TCG Collector. Each card gets a row with its picture, set, number and the variants you own with their counts; pick the variant (it is preselected when you own only one) and "Levesz" removes one copy from your collection, with "Visszavonás" for 6 seconds. The same card twice on the photos is two rows. Cards that need a choice or were not found are at the end of the list; rows can be deleted.
- Cards that are in your Pokedex list are marked "A Pokedex listádban van". The list itself is never changed.
- Removals appear in the "Kollekció napló" in Settings.
- Works best with cards laid out straight, next to each other, with the number at the bottom left (Sun & Moon and newer). If no card is found, take the photo closer.

## 1.4.0

- Pokédex: when the card in your Pokedex list is Common, Uncommon or Rare, a new "Legújabb full art / double rare lapok" section under it shows the Pokémon's 5 newest full art and double rare cards from TCG Collector (Double Rare, Ultra Rare, Illustration Rare, Special Illustration Rare, Hyper Rare, and the older full arts, V, VMAX, VSTAR, GX, EX, Secret, Rainbow and Shiny cards), each with picture, set, number, rarity and a ✓ when you own it. Tap a card to open it on TCG Collector.

## 1.3.0

- Recognition result: new "Hozzáadás a kollekcióhoz" button adds one copy of the shown variant to your TCG Collector collection, in English and in your account's default condition. After adding, the count on the screen updates and "Visszavonás" undoes it for 6 seconds. The app reloads the card before and after every change and only says it is done when TCG Collector shows the new count. This is the first feature that changes your collection: please try it first on a cheap card.
- Settings: new "Kollekció napló" with every card the app added (or undid), with date, set, number, variant and the count before and after. It can be viewed, copied and cleared.
- Home screen title: "Pokémon kártya toolbox".

## 1.2.1

- Sealed catalog: a new installation starts with an empty list. The starting list that came with the app is removed from the app and the repository; lists already on a phone are not changed.

## 1.2.0

- Sealed catalog: new "Excel export" in Settings. It saves an .xlsx file with one row per product: its picture, count, purchase price per piece and in total, Cardmarket price per piece in euros and forints, total value, difference, price date and a Cardmarket link, plus a total row. The file is for viewing; the JSON export stays the backup that can be imported.

## 1.1.4

Sealed catalog:
- Sorting by "Érték" now uses the price of one piece, not the price times the count.
- A filter field next to the sorting chips narrows the list to the products whose name, set or type match.

## 1.1.3

- Sealed catalog: "Árak frissítése" waits 8 seconds between Cardmarket pages (was 4), so the Cloudflare check comes even less often. A full refresh takes longer.
- Sealed catalog: when Cardmarket asks for its Cloudflare check during a price refresh, the check opens full screen, so the checkbox can be reached. After ticking it the window closes and the refresh continues. The small Cardmarket panel under the progress bar is gone.

## 1.1.2

Sealed catalog fixes:
- "Árak frissítése" now waits a few seconds between Cardmarket pages, so Cardmarket asks for its Cloudflare check much less often. A full refresh takes a few minutes.
- While the refresh runs, Cardmarket is shown on the screen. If it asks for the check, tick it there and the refresh continues by itself.
- Product pictures are now downloaded through the app's Cardmarket browser and saved on the phone. If a picture cannot be downloaded, the product screen says why.

## 1.1.1

Sealed catalog improvements:
- If Cardmarket asks for its Cloudflare check during "Árak frissítése", the list shows a button to open it. After the check the refresh continues where it stopped.
- Product pictures from Cardmarket on the list and on the product screen. A picture is saved with the price, so it appears after the next price refresh.

## 1.1.0

New: Sealed catalog, your sealed Pokémon products (ETB, booster bundle, box, tin, blister...).
- New "Sealed catalog" button on the home screen. It works without a TCG Collector login.
- Add products by typing their name: the app suggests products from Cardmarket's English sealed list (about 4000 products). Word order does not matter, "etb" means Elite Trainer Box, and small typos are fine.
- Each product has a count and an average purchase price per piece in forints. When you add more pieces with a price, the average is updated.
- Cardmarket price by your rule: English offers, skip the 2 cheapest, average the next 5. "Árak frissítése" loads the price of every product, one after the other.
- Totals at the top: pieces, purchase total, current value in euros and forints, and the difference.
- Settings: refresh the product list, export and import your own list as a file.

## 1.0.2

Test build for the upcoming Sealed catalog (1.1.0):
- Settings → Cardmarket debug screen: new "Sealed próba" button. It checks how sealed products (ETB, mini tin) can be found and priced on Cardmarket and adds the results to the report to copy.
- No other changes.

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
