#!/usr/bin/env python3
"""Builds the Sealed catalog product list from Cardmarket's product catalogue.

Cardmarket publishes its catalogue of Pokémon non-singles (boosters, displays, ETBs, tins, ...)
as a free daily download. This keeps the English sealed products and writes them to
app/src/main/assets/sealed_products.tsv, one per line:

    idProduct <TAB> type <TAB> set <TAB> name

The catalogue has no set names, only expansion ids: a set's name is taken from its booster
(the shortest "<set> Booster" of the expansion), or else from the "Set: ..." prefix of a product name.

Usage: fetch-sealed-products.py <out file> [--min N] [--catalogue FILE]
With --catalogue, a downloaded products_nonsingles_6.json is read instead of downloading it.
Fewer than N products in the result fails the step.
"""
import json
import re
import sys
import time
import urllib.request

URL = "https://downloads.s3.cardmarket.com/productCatalog/productList/products_nonsingles_6.json"

# Cardmarket category -> type shown in the app. Coins, lots and PCG sets (old Japanese) are left out.
TYPES = {
    "Pokémon Booster": "Booster",
    "Pokémon Display": "Display",
    "Pokémon Elite Trainer Boxes": "Elite Trainer Box",
    "Pokémon Box Set": "Box Set",
    "Pokémon Tins": "Tin",
    "Pokémon Theme Decks": "Theme Deck",
    "Pokémon Blisters": "Blister",
    "Pokémon Trainer Kits": "Trainer Kit",
}

# Chinese set codes ("151C:", "CSV9.5C:", "CBB3C:") and products named after their language.
NOT_ENGLISH = re.compile(
    r"^(151C|C[SB][A-Za-z0-9.]*C):|\b(Japan|Japanese|Chinese|Korean|Thai|Indonesian)\b",
)
BOOSTER = re.compile(r"^(.+?) Booster$")
SET_PREFIX = re.compile(r"^([^:]{3,40}): ")


def get(url, attempts=4, wait=5):
    """Retries with doubling waits: wait, 2*wait, 4*wait, ..."""
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "PKMrec-build"})
            with urllib.request.urlopen(request, timeout=120) as response:
                return response.read()
        except Exception as e:
            if attempt == attempts - 1:
                raise
            print(f"warning: {url}: {e}; retrying in {wait * 2 ** attempt} s", file=sys.stderr)
            time.sleep(wait * 2 ** attempt)


def clean(text):
    return " ".join(str(text).replace("\t", " ").split())


def build(products):
    kept = [p for p in products if p.get("categoryName") in TYPES and not NOT_ENGLISH.search(p.get("name", ""))]

    # The shortest booster name of an expansion is its plain booster ("Perfect Order Booster",
    # not "Perfect Order Sleeved Booster").
    set_names = {}
    for p in kept:
        match = BOOSTER.match(p["name"])
        if p["categoryName"] != "Pokémon Booster" or not match:
            continue
        name = match.group(1)
        old = set_names.get(p["idExpansion"])
        if old is None or len(name) < len(old):
            set_names[p["idExpansion"]] = name

    rows = []
    for p in kept:
        name = clean(p["name"])
        prefix = SET_PREFIX.match(name)
        set_name = set_names.get(p["idExpansion"]) or (prefix.group(1) if prefix else "")
        rows.append((p["idProduct"], TYPES[p["categoryName"]], clean(set_name), name))
    rows.sort(key=lambda r: (r[3].lower(), r[0]))
    return rows


def main(argv):
    if not argv or argv[0].startswith("-"):
        print(__doc__, file=sys.stderr)
        return 2
    out = argv[0]
    minimum = int(argv[argv.index("--min") + 1]) if "--min" in argv else 0
    if "--catalogue" in argv:
        with open(argv[argv.index("--catalogue") + 1], "rb") as f:
            data = f.read()
    else:
        data = get(URL)
    catalogue = json.loads(data)
    rows = build(catalogue["products"])
    if len(rows) < minimum:
        print(f"error: only {len(rows)} sealed products, expected at least {minimum}", file=sys.stderr)
        return 1
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# Cardmarket sealed Pokémon products (English), catalogue {catalogue.get('createdAt', '?')}\n")
        f.write("# idProduct\ttype\tset\tname\n")
        for row in rows:
            f.write("\t".join(str(v) for v in row) + "\n")
    print(f"{len(rows)} sealed products written to {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
