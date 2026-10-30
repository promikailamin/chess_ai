#!/usr/bin/env python3
"""Export the RGB piece sprites as runtime color templates.

The generated 48x48 RGBA images keep the sprite color and alpha channel so the
Java classifier can compare both piece shape and appearance.  The manifest is
read at runtime on desktop and Android.
"""
from __future__ import annotations

import csv
import os

import cv2
import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PIECES = os.path.join(ROOT, "dataset", "pieces")
OUTPUT = os.path.join(ROOT, "src", "main", "resources", "templates", "color")
SIZE = 48
TYPES = ["pawn", "knight", "bishop", "rook", "queen", "king"]
COLORS = {"white": 0, "black": 1}


def main() -> None:
    os.makedirs(OUTPUT, exist_ok=True)
    rows = []
    for theme in sorted(os.listdir(PIECES)):
        theme_dir = os.path.join(PIECES, theme)
        if not os.path.isdir(theme_dir):
            continue
        out_dir = os.path.join(OUTPUT, theme)
        os.makedirs(out_dir, exist_ok=True)
        for color in ("white", "black"):
            for type_index, piece_type in enumerate(TYPES, start=1):
                name = f"{color}_{piece_type}.png"
                source = os.path.join(theme_dir, name)
                raw = cv2.imread(source, cv2.IMREAD_UNCHANGED)
                if raw is None:
                    continue
                rgb = raw[:, :, 2::-1]
                alpha = raw[:, :, 3]
                k = 14
                corners = np.concatenate([
                    rgb[:k, :k].reshape(-1, 3),
                    rgb[:k, -k:].reshape(-1, 3),
                    rgb[-k:, :k].reshape(-1, 3),
                    rgb[-k:, -k:].reshape(-1, 3),
                ])
                background = np.median(corners, axis=0)
                foreground = rgb[alpha > 160].mean(axis=0) if np.any(alpha > 160) else background
                rgb = cv2.resize(rgb, (SIZE, SIZE), interpolation=cv2.INTER_LINEAR)
                alpha = cv2.resize(alpha, (SIZE, SIZE), interpolation=cv2.INTER_LINEAR)
                rgba = np.dstack([rgb, alpha])
                Image.fromarray(rgba, mode="RGBA").save(os.path.join(out_dir, name))
                rows.append((
                    theme, type_index, COLORS[color], f"{theme}/{name}",
                    *(round(float(value), 2) for value in background),
                    *(round(float(value), 2) for value in foreground),
                ))

    manifest = os.path.join(OUTPUT, "manifest.csv")
    with open(manifest, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow([
            "theme", "type", "color", "resource",
            "bg_r", "bg_g", "bg_b", "fg_r", "fg_g", "fg_b",
        ])
        writer.writerows(rows)
    print(f"Wrote {len(rows)} color templates to {OUTPUT}")


if __name__ == "__main__":
    main()
