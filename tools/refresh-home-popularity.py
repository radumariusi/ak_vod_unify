"""Build a compact index from the published Netflix country Top 10 TSV."""

import argparse
import csv
import json
from pathlib import Path
import unicodedata
from urllib.request import Request, urlopen


SOURCE = "https://www.netflix.com/tudum/top10/data/all-weeks-countries.tsv"
MARKETS = {"RO", "US", "GB", "FR", "DE"}
TYPES = {"Films": "movie", "TV": "tv"}


def normalized(title):
    text = unicodedata.normalize("NFD", title.lower())
    text = "".join(c for c in text if unicodedata.category(c)[0] != "M")
    return " ".join("".join(c if c.isalnum() else " " for c in text).split())


def index(lines):
    hits = {}
    last_week = ""
    reader = csv.DictReader(lines, delimiter="\t")
    for row in reader:
        kind = TYPES.get(row.get("category"))
        market = row.get("country_iso2")
        if market not in MARKETS or not kind or not row.get("weekly_rank", "").isdigit():
            continue
        name = normalized(row["show_title"])
        rank = int(row["weekly_rank"])
        if not name or not 1 <= rank <= 10:
            continue
        last_week = max(last_week, row["week"])
        ranks = hits.setdefault(f"{kind}:{name}", {})
        ranks[market] = min(rank, ranks.get(market, rank))
    titles = {key: ranks for key, ranks in hits.items() if len(ranks) >= 2 or min(ranks.values()) <= 3}
    if not last_week or len(titles) < 100:
        raise ValueError("Unexpected or incomplete Netflix country chart")
    return {"week": last_week, "titles": titles}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, help="Use a downloaded official TSV")
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/home_popularity.json"))
    args = parser.parse_args()
    if args.input:
        with args.input.open(encoding="utf-8-sig", newline="") as lines:
            data = index(lines)
    else:
        request = Request(SOURCE, headers={"User-Agent": "AK VOD Unify (weekly chart index)"})
        with urlopen(request, timeout=45) as response:
            data = index(line.decode("utf-8-sig") for line in response)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
    temporary.replace(args.output)
    print(f"Netflix regional Top 10 through {data['week']}: {len(data['titles'])} titles")


if __name__ == "__main__":
    main()
