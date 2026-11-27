#!/usr/bin/env python3
"""Builds the list of TCGplayer sealed Pokémon products from TCGCSV.

TCGCSV (tcgcsv.com) republishes TCGplayer's catalogue and prices as free daily JSON files. The
Sealed catalog takes its prices from there (the TCGplayer market price, the same number Collectr
shows) and pairs each of the user's products with a TCGplayer product from this list. It is
written to app/src/main/assets/tcgplayer_sealed.tsv, one product per line:

    productId <TAB> groupId <TAB> set <TAB> name

Sealed products are the ones without a card number. Code cards are left out. The set is the
group name without its code prefix ("ME: Ascended Heroes" -> "Ascended Heroes").

Usage: fetch-tcgplayer-sealed.py <out file> [--min N] [--dir DIR]
With --dir, groups.json and <groupId>.json files in DIR are read instead of downloading them.
Fewer than N products in the result fails the step.
"""
import json
import os
import re
import sys
import time
import urllib.request

BASE = "https://tcgcsv.com/tcgplayer/3"
# Pause between two downloads, to be polite to TCGCSV (about 220 groups).
GAP_S = 0.2

SET_PREFIX = re.compile(r"^[A-Za-z0-9]+: ")
CODE_CARD = re.compile(r"^Code Card\b", re.IGNORECASE)


def get(url, attempts=4, wait=5):
    """Retries with doubling waits: wait, 2*wait, 4*wait, ..."""
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "PKMrec-build"})
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.loads(response.read())
        except Exception as e:
            if attempt == attempts - 1:
                raise
            print(f"warning: {url}: {e}; retrying in {wait * 2 ** attempt} s", file=sys.stderr)
            time.sleep(wait * 2 ** attempt)


def clean(text):
    return " ".join(str(text).replace("\t", " ").split())


def set_name(group_name):
    return SET_PREFIX.sub("", clean(group_name), count=1)


def is_sealed(product):
    extended = product.get("extendedData") or []
    return not any(e.get("name") == "Number" for e in extended) and not CODE_CARD.search(product.get("name", ""))


def build(groups, products_of):
    """groups: TCGCSV group objects; products_of(groupId) -> that group's product objects."""
    rows = []
    for group in groups:
        for p in products_of(group["groupId"]):
            if is_sealed(p):
                rows.append((p["productId"], group["groupId"], set_name(group["name"]), clean(p["name"])))
    rows = list({r[0]: r for r in rows}.values())
    rows.sort(key=lambda r: (r[3].lower(), r[0]))
    return rows


def main(argv):
    if not argv or argv[0].startswith("-"):
        print(__doc__, file=sys.stderr)
        return 2
    out = argv[0]
    minimum = int(argv[argv.index("--min") + 1]) if "--min" in argv else 0
    local = argv[argv.index("--dir") + 1] if "--dir" in argv else None

    def load(name, url):
        if local:
            with open(os.path.join(local, name), encoding="utf-8") as f:
                return json.load(f)
        time.sleep(GAP_S)
        return get(url)

    groups = load("groups.json", f"{BASE}/groups")["results"]
    rows = build(groups, lambda gid: load(f"{gid}.json", f"{BASE}/{gid}/products")["results"])
    if len(rows) < minimum:
        print(f"error: only {len(rows)} sealed products, expected at least {minimum}", file=sys.stderr)
        return 1
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# TCGplayer sealed Pokémon products (from tcgcsv.com), {time.strftime('%Y-%m-%d', time.gmtime())}\n")
        f.write("# productId\tgroupId\tset\tname\n")
        for row in rows:
            f.write("\t".join(str(v) for v in row) + "\n")
    print(f"{len(rows)} TCGplayer sealed products written to {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
