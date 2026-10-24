#!/usr/bin/env python3
"""Turns a TCG Collector page saved from a logged-in browser into an anonymised test fixture.

Usage: anonymize-tcg-page.py <saved page.html> <fixture.html>

- appState: the user is replaced by "Test User" (id 1), the CSRF token and the user's list owner
  are replaced, and the site-wide variant type table is cut down to the variants on the page.
- HTML: the display name and its avatar initial are replaced, scripts other than appState (Cloudflare beacon, bundles),
  stylesheets and links to the browser's "_files" folder are dropped, and site links the
  browser made absolute are made relative again, as the server sends them.

Check the result for anything personal before committing it: the repository is public.
"""
import json
import re
import sys

TEST_NAME = "Test User"
TEST_USER_ID = 1


def matching_brace(text, start):
    depth, in_string, escaped = 0, False, False
    for i in range(start, len(text)):
        c = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif c == '"':
                in_string = False
        elif c == '"':
            in_string = True
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
    raise ValueError("appState is not closed")


def anonymize_state(state):
    user = state.get("userDto") or {}
    name = user.get("displayName")
    if user:
        user["id"] = TEST_USER_ID
        user["displayName"] = TEST_NAME
    if "csrfToken" in state:
        state["csrfToken"] = "test-csrf-token"
    card_list = (state.get("userCardListDataDto") or {}).get("userCardList")
    if card_list:
        card_list["userId"] = TEST_USER_ID
    variants = state.get("idToCardVariantTypeDtoMap")
    if isinstance(variants, dict):
        used = set()
        for ids in (state.get("cardIdToCardVariantTypeIdsMap") or {}).values():
            used.update(str(i) for i in ids)
        for card in (state.get("cardIdToCardCollectionCardDtoMap") or {}).values():
            used.update(str(e.get("cardVariantTypeId")) for e in card.get("entries", []))
        state["idToCardVariantTypeDtoMap"] = {k: v for k, v in variants.items() if k in used}
    return name


def main():
    source, target = sys.argv[1], sys.argv[2]
    html = open(source, encoding="utf-8").read()

    script = html.index("window.tcgcollector")
    start = html.index("{", html.index("appState:", script))
    end = matching_brace(html, start)
    state = json.loads(html[start:end + 1])
    name = anonymize_state(state)
    html = html[:start] + json.dumps(state, ensure_ascii=False, separators=(",", ":")) + html[end + 1:]

    if name:
        for form in {name, name.replace("&", "&amp;")}:
            html = html.replace(form, TEST_NAME)
    # Chrome's "saved from url" comment (the URL can contain the user's list ID).
    html = re.sub(r"<!-- saved from url=[^>]*-->\s*", "", html)
    html = re.sub(r'(id="navbar-button-user-avatar">)\s*[^<\s]+', r"\1 " + TEST_NAME[0], html)
    # Scripts with a src (bundles, Cloudflare beacon) and the Cloudflare challenge parameters.
    html = re.sub(r"<script\b[^>]*\bsrc=[^>]*>\s*</script>", "", html)
    html = re.sub(r"<script\b[^>]*>(?:(?!</script>).)*__CF\$cv\$params(?:(?!</script>).)*</script>", "", html, flags=re.S)
    html = re.sub(r"<link\b[^>]*>", "", html)
    html = re.sub(r"<style\b[^>]*>.*?</style>", "", html, flags=re.S)
    # The browser makes site links absolute when saving; the server sends them relative.
    html = re.sub(r'(href=")https://www\.tcgcollector\.com/', r"\1/", html)
    # References to the folder the browser saved next to the page.
    html = re.sub(r"""(src|href)="\./[^"]*_files/[^"]*\"""", r'\1=""', html)

    open(target, "w", encoding="utf-8").write(html)
    print(f"{target}: {len(html)} characters")


if __name__ == "__main__":
    main()
