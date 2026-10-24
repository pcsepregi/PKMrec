#!/usr/bin/env python3
"""Downloads the symbols of all English Pokémon TCG sets from pokemontcg.io into the app's assets.

Writes <out>/index.tsv (id, set code, name per line) and <out>/<id>.png (greyscale with alpha,
64 px tall when Pillow is available). The app compares these with the symbol printed on a card.

A copy of the symbols is kept in git (refreshed by the "Set symbols" workflow). Fresh downloads
are merged into it: a set whose symbol cannot be downloaded keeps its copy from git, so a
pokemontcg.io outage does not break the build.

Usage: fetch-set-symbols.py <out dir> [--min N]
Download problems are reported as warnings; with --min, fewer than N symbols in the result
(downloaded plus kept from git) fails the step (used for releases).
"""
import concurrent.futures
import io
import json
import os
import re
import sys
import time
import urllib.request

API = "https://api.pokemontcg.io/v2/sets?pageSize=250&orderBy=releaseDate"
HEIGHT = 64

try:
    from PIL import Image
except ImportError:
    Image = None


def get(url, attempts=3, wait=2):
    """Retries with doubling waits: wait, 2*wait, 4*wait, ..."""
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "PKMrec-build"})
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read()
        except Exception as e:
            if attempt == attempts - 1:
                raise
            print(f"{url}: {e}, retrying in {wait * 2 ** attempt} s")
            time.sleep(wait * 2 ** attempt)


def shrink(data):
    if Image is None:
        return data
    image = Image.open(io.BytesIO(data)).convert("LA")
    width, height = image.size
    image = image.resize((max(1, round(width * HEIGHT / height)), HEIGHT), Image.LANCZOS)
    out = io.BytesIO()
    image.save(out, "PNG", optimize=True)
    return out.getvalue()


def fetch(out_dir, card_set):
    set_id = card_set["id"]
    if not re.fullmatch(r"[A-Za-z0-9._-]+", set_id):
        raise ValueError(f"unexpected set id {set_id!r}")
    data = shrink(get(card_set["images"]["symbol"]))
    with open(os.path.join(out_dir, set_id + ".png"), "wb") as f:
        f.write(data)
    name = " ".join(card_set["name"].split())
    return "\t".join([set_id, (card_set.get("ptcgoCode") or "").strip(), name])


def read_index(out_dir):
    """The rows already in <out>/index.tsv (the copy from git) whose symbol file exists, by set id."""
    rows = {}
    try:
        with open(os.path.join(out_dir, "index.tsv"), encoding="utf-8") as f:
            for line in f:
                row = line.rstrip("\n")
                set_id = row.split("\t")[0]
                if row and os.path.exists(os.path.join(out_dir, set_id + ".png")):
                    rows[set_id] = row
    except FileNotFoundError:
        pass
    return rows


def main():
    out_dir = sys.argv[1]
    minimum = int(sys.argv[sys.argv.index("--min") + 1]) if "--min" in sys.argv else 0
    os.makedirs(out_dir, exist_ok=True)
    rows = read_index(out_dir)
    kept = len(rows)
    downloaded = 0
    try:
        sets = [s for s in json.loads(get(API, attempts=5, wait=5))["data"] if s.get("images", {}).get("symbol")]
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            futures = {pool.submit(fetch, out_dir, s): s["id"] for s in sets}
            for future in concurrent.futures.as_completed(futures):
                try:
                    rows[futures[future]] = future.result()
                    downloaded += 1
                except Exception as e:
                    print(f"::warning::Set symbol {futures[future]} could not be downloaded: {e}")
    except Exception as e:
        print(f"::warning::Set symbols could not be downloaded, using the copy from git ({kept}): {e}")
    with open(os.path.join(out_dir, "index.tsv"), "w", encoding="utf-8") as f:
        f.write("".join(rows[k] + "\n" for k in sorted(rows)))
    print(f"{len(rows)} set symbols in {out_dir}: {downloaded} downloaded, {kept} were in git (Pillow: {'yes' if Image else 'no'})")
    if len(rows) < minimum:
        print(f"::error::Only {len(rows)} set symbols, at least {minimum} needed")
        sys.exit(1)


if __name__ == "__main__":
    main()
