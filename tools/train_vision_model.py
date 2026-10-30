#!/usr/bin/env python3
"""Train the per-theme vision templates from labelled chess screenshots.

The runtime classifier is a dependency-light template matcher.  This script
turns a labelled screenshot collection (``dataset/labels.json`` or a compatible
file produced by ``tools/build_vision_dataset.py``) into:

* one or more representative piece templates per ``(theme, colour, piece)``,
* a ``manifest.csv`` using the same format as the bundled colour templates,
* ``theme_stats.csv`` with learned background/foreground colour centroids that
  the classifier uses to pick the right theme before matching pieces.

Exemplars are chosen as the samples closest to the class median, which keeps
the templates sharp instead of averaging away thin details.  Everything is
deterministic for a given labels file.

Typical use::

    python3 tools/train_vision_model.py \
        --labels .agent-work/tmp/vision_dataset/labels.json \
        --out src/main/resources/templates/trained --exemplars 1

Requirements: ``numpy``, ``opencv-python-headless`` and ``pillow`` (see
``tools/requirements-vision.txt``).
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
from collections import defaultdict
from dataclasses import dataclass, field

import cv2
import numpy as np

from build_vision_dataset import (
    BOARD,
    CELL,
    COLOR_CODE,
    TYPES,
    TYPE_CODE,
    Sprite,
    clean_backgrounds,
    extract_sprite,
    fen_grid,
    median_sprite,
    warp_board,
)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_LABELS = os.path.join(ROOT, "dataset", "labels.json")
DEFAULT_OUT = os.path.join(ROOT, "src", "main", "resources", "templates", "trained")


@dataclass
class TrainingStats:
    images: int = 0
    occupied: int = 0
    sprites: int = 0
    rejected: int = 0
    templates: int = 0
    themes: dict = field(default_factory=dict)


def load_labels(path: str) -> list[dict]:
    if path.endswith(".json"):
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    rows = []
    with open(path, newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            row["quad"] = json.loads(row["quad"])
            rows.append(row)
    return rows


def resolve_image(label: dict, labels_path: str) -> str:
    image = label["image"]
    if os.path.isabs(image):
        return image
    candidates = [
        os.path.join(ROOT, image),
        os.path.join(os.path.dirname(os.path.abspath(labels_path)), image),
        os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(labels_path))), image),
    ]
    for candidate in candidates:
        if os.path.isfile(candidate):
            return candidate
    return candidates[0]


def splits_from_args(values: list[str]) -> set[str]:
    result: set[str] = set()
    for value in values:
        for part in value.split(","):
            part = part.strip()
            if part:
                result.add(part)
    return result or {"train"}


def sprite_distance(first: Sprite, second: Sprite) -> float:
    a = first.alpha.astype(np.float32) / 255.0
    b = second.alpha.astype(np.float32) / 255.0
    union = np.maximum(a, b).sum()
    alpha_diff = 0.0 if union == 0 else np.abs(a - b).sum() / union
    rgb_diff = np.abs(first.rgb.astype(np.float32) - second.rgb.astype(np.float32)).mean() / 255.0
    return alpha_diff + 0.35 * rgb_diff


def mean_foreground(sprite: Sprite) -> np.ndarray | None:
    opaque = sprite.alpha > 96
    if not np.any(opaque):
        return None
    return sprite.rgb.astype(np.float32)[opaque].mean(axis=0)


def select_exemplars(values: list[Sprite], count: int) -> list[Sprite]:
    if count <= 1 or len(values) <= count:
        return values[:count] if count > 1 else [median_sprite(values)]
    median = median_sprite(values)
    ranked = sorted(values, key=lambda sprite: sprite_distance(sprite, median))
    return ranked[:count]


def write_sprite(sprite: Sprite, path: str, size: int,
                 background: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    alpha = sprite.alpha.astype(np.float32) / 255.0
    rgb = (sprite.rgb.astype(np.float32) * alpha[:, :, None]
           + background.astype(np.float32) * (1 - alpha[:, :, None]))
    rgb = cv2.resize(rgb, (size, size), interpolation=cv2.INTER_AREA)
    resized_alpha = cv2.resize(sprite.alpha, (size, size), interpolation=cv2.INTER_AREA)
    rgba = np.dstack([np.clip(rgb, 0, 255).astype(np.uint8), resized_alpha])
    cv2.imwrite(path, rgba)
    return rgba[:, :, 2::-1], resized_alpha


def palette(rgba: np.ndarray, alpha: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    k = max(2, rgba.shape[0] // 8)
    corners = np.concatenate([
        rgba[:k, :k].reshape(-1, 3),
        rgba[:k, -k:].reshape(-1, 3),
        rgba[-k:, :k].reshape(-1, 3),
        rgba[-k:, -k:].reshape(-1, 3),
    ])
    bg = np.median(corners, axis=0)
    opaque = alpha > 160
    fg = rgba[opaque].mean(axis=0) if np.any(opaque) else bg
    return bg, fg


def train(labels_path: str, out_dir: str, splits: set[str], size: int,
          exemplar_count: int, min_samples: int, clean: bool) -> TrainingStats:
    labels = load_labels(labels_path)
    selected = [label for label in labels if label.get("split", "train") in splits]
    if not selected:
        raise SystemExit(f"No labels matched splits {sorted(splits)} in {labels_path}")

    samples: dict[tuple[str, str, str], list[Sprite]] = defaultdict(list)
    theme_backgrounds: dict[str, list[np.ndarray]] = defaultdict(list)
    theme_foregrounds: dict[str, dict[str, list[np.ndarray]]] = defaultdict(
        lambda: {"white": [], "black": []})
    stats = TrainingStats()

    for index, label in enumerate(selected, start=1):
        image_path = resolve_image(label, labels_path)
        image = cv2.imread(image_path)
        if image is None:
            print(f"  skip missing image: {image_path}")
            continue
        theme = label.get("theme", "unknown")
        grid = fen_grid(label["fen"])
        board = warp_board(image, label["quad"], BOARD)
        backgrounds = clean_backgrounds(board, grid)
        theme_backgrounds[theme].extend(
            background.reshape(-1, 3).mean(axis=0) for background in backgrounds.values())
        stats.images += 1
        for row in range(8):
            for col in range(8):
                symbol = grid[row][col]
                if symbol == ".":
                    continue
                stats.occupied += 1
                cell = board[row * CELL:(row + 1) * CELL, col * CELL:(col + 1) * CELL]
                sprite = extract_sprite(cell, backgrounds[(row + col) & 1], 1.0)
                if sprite is None:
                    stats.rejected += 1
                    continue
                colour = "white" if symbol.isupper() else "black"
                piece = TYPES["pnbrqk".index(symbol.lower())]
                samples[(theme, colour, piece)].append(sprite)
                foreground = mean_foreground(sprite)
                if foreground is not None:
                    theme_foregrounds[theme][colour].append(foreground)
                stats.sprites += 1
        if index % 100 == 0 or index == len(selected):
            print(f"  processed {index}/{len(selected)} images")

    if clean and os.path.isdir(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir, exist_ok=True)

    rows = []
    for (theme, colour, piece), values in sorted(samples.items()):
        if len(values) < min_samples:
            continue
        background = np.median(np.stack(theme_backgrounds[theme]), axis=0).astype(np.uint8)
        for exemplar_index, sprite in enumerate(select_exemplars(values, exemplar_count)):
            theme_dir = os.path.join(out_dir, theme)
            os.makedirs(theme_dir, exist_ok=True)
            filename = f"{colour}_{piece}_{exemplar_index}.png"
            rgba, alpha = write_sprite(sprite, os.path.join(theme_dir, filename),
                                       size, background)
            bg, fg = palette(rgba, alpha)
            rows.append({
                "theme": theme,
                "type": TYPE_CODE[piece],
                "color": COLOR_CODE[colour],
                "resource": f"{theme}/{filename}",
                "bg": bg,
                "fg": fg,
            })
            stats.templates += 1
        stats.themes[theme] = stats.themes.get(theme, 0) + 1

    manifest = os.path.join(out_dir, "manifest.csv")
    with open(manifest, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["theme", "type", "color", "resource",
                         "bg_r", "bg_g", "bg_b", "fg_r", "fg_g", "fg_b"])
        for row in rows:
            writer.writerow([
                row["theme"], row["type"], row["color"], row["resource"],
                *(f"{value:.2f}" for value in row["bg"]),
                *(f"{value:.2f}" for value in row["fg"]),
            ])

    theme_rows = []
    for theme in sorted(theme_backgrounds):
        backgrounds = np.stack(theme_backgrounds[theme])
        luminance = 0.299 * backgrounds[:, 0] + 0.587 * backgrounds[:, 1] + 0.114 * backgrounds[:, 2]
        order = np.argsort(luminance)
        half = max(1, len(order) // 2)
        dark = backgrounds[order[:half]].mean(axis=0)
        light = backgrounds[order[-half:]].mean(axis=0)
        colours = {}
        for colour in ("white", "black"):
            values = theme_foregrounds[theme][colour]
            colours[colour] = np.stack(values).mean(axis=0) if values else np.array([128.0, 128.0, 128.0])
        theme_rows.append([
            theme,
            *(f"{value:.2f}" for value in light),
            *(f"{value:.2f}" for value in dark),
            *(f"{value:.2f}" for value in colours["white"]),
            *(f"{value:.2f}" for value in colours["black"]),
        ])

    with open(os.path.join(out_dir, "theme_stats.csv"), "w", newline="",
              encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["theme",
                         "bg_light_r", "bg_light_g", "bg_light_b",
                         "bg_dark_r", "bg_dark_g", "bg_dark_b",
                         "fg_white_r", "fg_white_g", "fg_white_b",
                         "fg_black_r", "fg_black_g", "fg_black_b"])
        writer.writerows(theme_rows)

    report = {
        "labels": os.path.abspath(labels_path),
        "splits": sorted(splits),
        "images": stats.images,
        "occupied": stats.occupied,
        "sprites": stats.sprites,
        "rejected": stats.rejected,
        "templates": stats.templates,
        "themes": dict(sorted(stats.themes.items())),
        "sample_counts": {
            f"{theme}/{colour}/{piece}": len(values)
            for (theme, colour, piece), values in sorted(samples.items())
        },
    }
    with open(os.path.join(out_dir, "training_report.json"), "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2)
        handle.write("\n")

    print(f"Trained {stats.templates} templates from {stats.images} images "
          f"({stats.sprites} sprites, {stats.rejected} rejected)")
    print(f"Wrote {manifest}")
    return stats


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--labels", default=DEFAULT_LABELS,
                        help="labels.json or labels.csv with image/fen/quad/theme/split")
    parser.add_argument("--out", default=DEFAULT_OUT, help="output template directory")
    parser.add_argument("--splits", nargs="+", default=["train"],
                        help="label splits to train on (default: train)")
    parser.add_argument("--size", type=int, default=48, help="template size in pixels")
    parser.add_argument("--exemplars", type=int, default=1,
                        help="templates per piece; 1 = median sprite (default: 1)")
    parser.add_argument("--min-samples", type=int, default=1,
                        help="minimum sprites required for a template")
    parser.add_argument("--clean", action="store_true",
                        help="remove the output directory before writing")
    args = parser.parse_args()
    train(os.path.abspath(args.labels), os.path.abspath(args.out),
          splits_from_args(args.splits), args.size, max(1, args.exemplars),
          args.min_samples, args.clean)


if __name__ == "__main__":
    main()
