#!/usr/bin/env python3
"""Merge a trained template set into a runtime template directory.

``train_vision_model.py`` writes a self-contained directory containing only the
themes it trained.  The bundled classifier loads one directory (``color`` by
default), so new themes have to be merged into it.  This script:

* copies the theme sprite directories,
* appends/replaces the matching rows in ``manifest.csv``,
* merges ``theme_stats.csv`` and ``theme_options.csv`` when present.

Example::

    python3 tools/merge_template_sets.py \
        --source .agent-work/tmp/lichess_trained \
        --dest src/main/resources/templates/color \
        --themes lichess-brown
"""
from __future__ import annotations

import argparse
import csv
import os
import shutil

MANIFEST_HEADER = ["theme", "type", "color", "resource",
                   "bg_r", "bg_g", "bg_b", "fg_r", "fg_g", "fg_b"]
STATS_HEADER = ["theme",
                "bg_light_r", "bg_light_g", "bg_light_b",
                "bg_dark_r", "bg_dark_g", "bg_dark_b",
                "fg_white_r", "fg_white_g", "fg_white_b",
                "fg_black_r", "fg_black_g", "fg_black_b"]
OPTIONS_HEADER = ["theme", "pawn_rerank_area"]


def read_csv(path: str) -> tuple[list[str], list[dict]]:
    if not os.path.isfile(path):
        return [], []
    with open(path, newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        return list(reader.fieldnames or []), list(reader)


def write_csv(path: str, header: list[str], rows: list[dict]) -> None:
    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=header, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def merge_rows(dest_rows: list[dict], source_rows: list[dict],
               themes: set[str], key) -> list[dict]:
    if themes:
        dest_rows = [row for row in dest_rows if row.get("theme") not in themes]
        source_rows = [row for row in source_rows if row.get("theme") in themes]
    merged = dest_rows + source_rows
    unique = {}
    for row in merged:
        unique[key(row)] = row
    return list(unique.values())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", required=True,
                        help="trained template directory (contains manifest.csv)")
    parser.add_argument("--dest", required=True,
                        help="runtime template directory to update")
    parser.add_argument("--themes", default="",
                        help="comma-separated themes to merge; default: every source theme")
    args = parser.parse_args()

    source = os.path.abspath(args.source)
    dest = os.path.abspath(args.dest)
    themes = {part.strip() for part in args.themes.split(",") if part.strip()}
    os.makedirs(dest, exist_ok=True)

    _, source_manifest = read_csv(os.path.join(source, "manifest.csv"))
    if not source_manifest:
        raise SystemExit(f"No manifest rows found in {source}")
    source_themes = {row["theme"] for row in source_manifest}
    selected = themes or source_themes
    unknown = selected - source_themes
    if unknown:
        raise SystemExit(f"Themes not present in source: {', '.join(sorted(unknown))}")

    for theme in sorted(selected):
        source_dir = os.path.join(source, theme)
        if not os.path.isdir(source_dir):
            continue
        target_dir = os.path.join(dest, theme)
        if os.path.isdir(target_dir):
            shutil.rmtree(target_dir)
        shutil.copytree(source_dir, target_dir)
        print(f"  copied {theme} ({len(os.listdir(target_dir))} sprites)")

    dest_manifest_path = os.path.join(dest, "manifest.csv")
    dest_header, dest_manifest = read_csv(dest_manifest_path)
    header = dest_header or MANIFEST_HEADER
    merged = merge_rows(dest_manifest, source_manifest, selected,
                        key=lambda row: (row["theme"], row["type"], row["color"],
                                         row["resource"]))
    merged.sort(key=lambda row: (row["theme"], int(row["color"]), int(row["type"]),
                                 row["resource"]))
    write_csv(dest_manifest_path, header, merged)
    print(f"  manifest rows: {len(dest_manifest)} -> {len(merged)}")

    for name, header in (("theme_stats.csv", STATS_HEADER),
                         ("theme_options.csv", OPTIONS_HEADER)):
        source_path = os.path.join(source, name)
        if not os.path.isfile(source_path):
            continue
        dest_path = os.path.join(dest, name)
        _, source_rows = read_csv(source_path)
        _, dest_rows = read_csv(dest_path)
        merged_rows = merge_rows(dest_rows, source_rows, selected,
                                 key=lambda row, name=name: (row.get("theme"),))
        write_csv(dest_path, header, merged_rows)
        print(f"  {name}: {len(dest_rows)} -> {len(merged_rows)} rows")


if __name__ == "__main__":
    main()
