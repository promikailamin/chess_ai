#!/usr/bin/env python3
"""Build the chess-vision training dataset.

The script uses the bundled reference screenshots as labelled examples.  For
every reference it:

* warps the playing area to a canonical 768x768 board,
* learns clean light/dark square backgrounds and one sprite per piece,
* writes the learned templates consumed by the Java classifier,
* renders new random chess positions with the same pieces and themes, and
* writes 140 labelled screenshot puzzles (100 train / 20 validation / 20 test).

Everything is deterministic for a given ``--seed`` so the committed dataset can
be reproduced.  Requirements are listed in ``tools/requirements-vision.txt``.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import random
import shutil
from dataclasses import dataclass

import chess
import cv2
import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATASET = os.path.join(ROOT, "dataset")
PUZZLES = os.path.join(DATASET, "puzzles")
PIECES = os.path.join(DATASET, "pieces")
TEMPLATES = os.path.join(ROOT, "src", "main", "resources", "templates", "learned")
WORK_OUTPUTS = os.path.join(ROOT, ".agent-work", "outputs")

BOARD = 768
CELL = BOARD // 8
CROPS = [0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 1.0]
TYPES = ["pawn", "knight", "bishop", "rook", "queen", "king"]
TYPE_CODE = {name: index + 1 for index, name in enumerate(TYPES)}
COLOR_CODE = {"white": 0, "black": 1}


@dataclass
class Sprite:
    rgb: np.ndarray
    alpha: np.ndarray
    mean_rgb: tuple[float, float, float]

    @property
    def mean_lum(self) -> float:
        r, g, b = self.mean_rgb
        return 0.299 * r + 0.587 * g + 0.114 * b

    def rgba(self) -> np.ndarray:
        return np.dstack([self.rgb, self.alpha])


def warp_board(image: np.ndarray, quad: list[list[float]], size: int = BOARD) -> np.ndarray:
    source = np.float32(quad)
    target = np.float32([[0, 0], [size, 0], [size, size], [0, size]])
    homography = cv2.getPerspectiveTransform(source, target)
    return cv2.warpPerspective(image, homography, (size, size))


def fen_grid(fen: str) -> list[list[str]]:
    board = chess.Board(fen)
    grid = [["." for _ in range(8)] for _ in range(8)]
    for square in chess.SQUARES:
        piece = board.piece_at(square)
        if piece is None:
            continue
        row = 7 - chess.square_rank(square)
        col = chess.square_file(square)
        grid[row][col] = piece.symbol()
    return grid


def clean_backgrounds(board: np.ndarray, grid: list[list[str]]) -> dict[int, np.ndarray]:
    cells = {0: [], 1: []}
    for row in range(8):
        for col in range(8):
            if grid[row][col] != ".":
                continue
            parity = (row + col) & 1
            cells[parity].append(board[row * CELL:(row + 1) * CELL, col * CELL:(col + 1) * CELL])
    if not cells[0] or not cells[1]:
        fallback = [cell for values in cells.values() for cell in values]
        if not fallback:
            raise RuntimeError("reference has no empty squares")
        median = np.median(np.stack(fallback), axis=0).astype(np.uint8)
        return {0: median, 1: median}
    return {
        parity: np.median(np.stack(values), axis=0).astype(np.uint8)
        for parity, values in cells.items()
    }


def extract_sprite(cell: np.ndarray, background: np.ndarray,
                   crop: float = 1.0) -> Sprite | None:
    rgb = cell.astype(np.float32)
    bg = background.astype(np.float32)
    diff = np.max(np.abs(rgb - bg), axis=2)
    lum = 0.299 * rgb[:, :, 0] + 0.587 * rgb[:, :, 1] + 0.114 * rgb[:, :, 2]
    bg_lum = 0.299 * bg[:, :, 0] + 0.587 * bg[:, :, 1] + 0.114 * bg[:, :, 2]
    lum_diff = np.abs(lum - bg_lum)

    mask = ((diff > 26) | (lum_diff > 30)).astype(np.uint8)
    mask[:2] = 0
    mask[-2:] = 0
    mask[:, :2] = 0
    mask[:, -2:] = 0
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    cutoff = max(1, min(CELL, int(round(crop * CELL))))
    mask[cutoff:] = 0

    count, labels, stats, centroids = cv2.connectedComponentsWithStats(mask, 8)
    keep = np.zeros_like(mask)
    for index in range(1, count):
        area = int(stats[index, cv2.CC_STAT_AREA])
        if area < 45:
            continue
        cx, cy = centroids[index]
        if not (0.05 * CELL <= cx <= 0.95 * CELL and 0.02 * CELL <= cy <= cutoff):
            continue
        keep[labels == index] = 1
    if keep.sum() < 60:
        return None

    alpha = np.clip((diff - 12.0) / 26.0, 0.0, 1.0) * keep
    alpha = cv2.GaussianBlur(alpha, (3, 3), 0)
    alpha = np.clip(alpha, 0.0, 1.0)
    alpha[cutoff:] = 0.0
    alpha_u8 = (alpha * 255).astype(np.uint8)

    opaque = alpha_u8 > 96
    if opaque.sum() < 60:
        return None
    mean_rgb = tuple(float(rgb[:, :, channel][opaque].mean()) for channel in range(3))
    return Sprite(rgb.astype(np.uint8), alpha_u8, mean_rgb)


def median_sprite(sprites: list[Sprite]) -> Sprite:
    if len(sprites) == 1:
        return sprites[0]
    stack = np.stack([sprite.rgba() for sprite in sprites])
    median = np.median(stack, axis=0)
    alpha = median[:, :, 3].astype(np.uint8)
    rgb = median[:, :, :3].astype(np.uint8)
    opaque = alpha > 96
    if opaque.sum() == 0:
        mean_rgb = (128.0, 128.0, 128.0)
    else:
        mean_rgb = tuple(float(rgb[:, :, channel][opaque].mean()) for channel in range(3))
    return Sprite(rgb, alpha, mean_rgb)


def normalize_mask(alpha: np.ndarray, size: int = 48) -> np.ndarray:
    mask = (alpha > 96).astype(np.uint8) * 255
    ys, xs = np.nonzero(mask)
    out = np.zeros((size, size), np.uint8)
    if len(xs) == 0:
        return out
    x0, x1 = int(xs.min()), int(xs.max())
    y0, y1 = int(ys.min()), int(ys.max())
    crop = mask[y0:y1 + 1, x0:x1 + 1]
    height, width = crop.shape
    scale = (size - 1.0) / max(width, height)
    new_w = max(1, int(round(width * scale)))
    new_h = max(1, int(round(height * scale)))
    resized = cv2.resize(crop, (new_w, new_h), interpolation=cv2.INTER_NEAREST)
    ox = (size - new_w) // 2
    oy = (size - new_h) // 2
    out[oy:oy + new_h, ox:ox + new_w] = resized
    return out


def dice_score(first: np.ndarray, second: np.ndarray) -> float:
    a = first > 0
    b = second > 0
    total = int(a.sum() + b.sum())
    if total == 0:
        return 0.0
    return 2.0 * float(np.logical_and(a, b).sum()) / total


def composite(sprite: Sprite, background: np.ndarray) -> np.ndarray:
    alpha = sprite.alpha.astype(np.float32)[:, :, None] / 255.0
    return (sprite.rgb.astype(np.float32) * alpha
            + background.astype(np.float32) * (1.0 - alpha)).astype(np.uint8)


def build_reference(reference: dict, crop: float = 1.0
                    ) -> tuple[dict[str, dict[str, Sprite]], dict[int, np.ndarray]]:
    image = cv2.imread(os.path.join(ROOT, reference["file"]))
    if image is None:
        raise FileNotFoundError(reference["file"])
    board = warp_board(image, reference["quad"])
    grid = fen_grid(reference["fen"])
    backgrounds = clean_backgrounds(board, grid)

    samples: dict[tuple[str, str], list[Sprite]] = {}
    for row in range(8):
        for col in range(8):
            symbol = grid[row][col]
            if symbol == ".":
                continue
            cell = board[row * CELL:(row + 1) * CELL, col * CELL:(col + 1) * CELL]
            background = backgrounds[(row + col) & 1]
            sprite = extract_sprite(cell, background, crop)
            if sprite is None:
                continue
            color = "white" if symbol.isupper() else "black"
            piece_type = TYPES["pnbrqk".index(symbol.lower())]
            samples.setdefault((color, piece_type), []).append(sprite)

    sprites: dict[str, dict[str, Sprite]] = {"white": {}, "black": {}}
    theme = reference["theme"]
    theme_dir = os.path.join(PIECES, theme)
    os.makedirs(theme_dir, exist_ok=True)
    for (color, piece_type), values in samples.items():
        sprite = median_sprite(values)
        sprites[color][piece_type] = sprite
        cv2.imwrite(os.path.join(theme_dir, f"{color}_{piece_type}.png"),
                    sprite.rgba())
    return sprites, backgrounds


def choose_crop(reference: dict) -> float:
    image = cv2.imread(os.path.join(ROOT, reference["file"]))
    if image is None:
        raise FileNotFoundError(reference["file"])
    board = warp_board(image, reference["quad"])
    grid = fen_grid(reference["fen"])
    backgrounds = clean_backgrounds(board, grid)
    best_crop = 1.0
    best_correct = -1
    for crop in CROPS:
        samples: dict[str, list[np.ndarray]] = {}
        positions: list[tuple[str, int]] = []
        for row in range(8):
            for col in range(8):
                symbol = grid[row][col]
                if symbol == ".":
                    continue
                cell = board[row * CELL:(row + 1) * CELL, col * CELL:(col + 1) * CELL]
                sprite = extract_sprite(cell, backgrounds[(row + col) & 1], crop)
                if sprite is None:
                    continue
                key = ("white" if symbol.isupper() else "black") + "_" + \
                      TYPES["pnbrqk".index(symbol.lower())]
                mask = normalize_mask(sprite.alpha)
                positions.append((key, len(samples.setdefault(key, []))))
                samples[key].append(mask)
        correct = 0
        total = 0
        for key, index in positions:
            templates = {}
            for name, values in samples.items():
                pool = [value for position, value in enumerate(values)
                        if not (name == key and position == index)]
                if pool:
                    templates[name] = np.median(np.stack(pool), axis=0).astype(np.uint8)
            if key not in templates:
                continue
            scores = {name: dice_score(mask, template)
                      for name, template in templates.items()}
            mask = samples[key][index]
            predicted = max(scores, key=scores.get)
            total += 1
            if predicted == key:
                correct += 1
        if correct > best_correct:
            best_correct = correct
            best_crop = crop
    return best_crop


def write_learned_templates(reference: dict, sprites: dict[str, dict[str, Sprite]],
                            backgrounds: dict[int, np.ndarray], crop: float) -> list[dict]:
    theme = reference["theme"]
    style = reference["style"]
    theme_dir = os.path.join(TEMPLATES, theme)
    os.makedirs(theme_dir, exist_ok=True)
    rows = []
    for color in ("white", "black"):
        for piece_type in TYPES:
            sprite = sprites[color].get(piece_type)
            if sprite is None:
                continue
            filename = f"{color}_{piece_type}.png"
            image = normalize_mask(sprite.alpha)
            cv2.imwrite(os.path.join(theme_dir, filename), image)
            rows.append({
                "theme": theme,
                "type": TYPE_CODE[piece_type],
                "color": COLOR_CODE[color],
                "style": style,
                "crop": crop,
                "mean_rgb": sprite.mean_rgb,
                "resource": f"{theme}/{filename}",
            })
    return rows


def random_position(rng: random.Random, min_plies: int = 8, max_plies: int = 84) -> chess.Board:
    for _ in range(200):
        board = chess.Board()
        plies = rng.randint(min_plies, max_plies)
        for _ in range(plies):
            if board.is_game_over():
                break
            moves = list(board.legal_moves)
            board.push(rng.choice(moves))
        if not board.is_game_over() and board.king(chess.WHITE) and board.king(chess.BLACK):
            return board
    raise RuntimeError("could not create a random position")


def render_position(board: chess.Board, sprites: dict[str, dict[str, Sprite]],
                    backgrounds: dict[int, np.ndarray], rng: random.Random) -> np.ndarray:
    canvas = np.zeros((BOARD, BOARD, 3), np.uint8)
    for row in range(8):
        for col in range(8):
            canvas[row * CELL:(row + 1) * CELL, col * CELL:(col + 1) * CELL] = backgrounds[(row + col) & 1]

    for square in chess.SQUARES:
        piece = board.piece_at(square)
        if piece is None:
            continue
        row = 7 - chess.square_rank(square)
        col = chess.square_file(square)
        color = "white" if piece.color == chess.WHITE else "black"
        piece_type = TYPES["pnbrqk".index(piece.symbol().lower())]
        sprite = sprites[color][piece_type]
        y0 = row * CELL
        x0 = col * CELL
        alpha = sprite.alpha.astype(np.float32)[:, :, None] / 255.0
        target = canvas[y0:y0 + CELL, x0:x0 + CELL].astype(np.float32)
        canvas[y0:y0 + CELL, x0:x0 + CELL] = (
            sprite.rgb.astype(np.float32) * alpha + target * (1.0 - alpha)
        ).astype(np.uint8)

    brightness = rng.uniform(0.92, 1.08)
    contrast = rng.uniform(0.92, 1.08)
    canvas = np.clip(canvas.astype(np.float32) * brightness, 0, 255)
    canvas = np.clip((canvas - 127.5) * contrast + 127.5, 0, 255).astype(np.uint8)
    if rng.random() < 0.35:
        noise = rng.uniform(-7.0, 7.0)
        canvas = np.clip(canvas.astype(np.float32) + noise, 0, 255).astype(np.uint8)
    if rng.random() < 0.25:
        canvas = cv2.GaussianBlur(canvas, (3, 3), 0)
    return canvas


def paste_board(reference: dict, rendered: np.ndarray, rng: random.Random
                ) -> tuple[np.ndarray, np.ndarray]:
    image = cv2.imread(os.path.join(ROOT, reference["file"]))
    if image is None:
        raise FileNotFoundError(reference["file"])
    scale = 0.5
    image = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    quad = np.array(reference["quad"], dtype=np.float32) * scale
    jitter = np.array([[rng.uniform(-2.5, 2.5), rng.uniform(-2.5, 2.5)]
                       for _ in range(4)], dtype=np.float32)
    quad = quad + jitter
    height, width = image.shape[:2]
    source = np.float32([[0, 0], [BOARD, 0], [BOARD, BOARD], [0, BOARD]])
    homography = cv2.getPerspectiveTransform(source, quad)
    warped = cv2.warpPerspective(rendered, homography, (width, height))
    mask = cv2.warpPerspective(np.full((BOARD, BOARD), 255, np.uint8), homography, (width, height))
    image[mask > 0] = warped[mask > 0]
    return image, quad


def contact_sheet(paths: list[str], target: str, columns: int = 5) -> None:
    thumbs = []
    for path in paths:
        image = Image.open(path).convert("RGB")
        image.thumbnail((360, 170))
        canvas = Image.new("RGB", (360, 190), "white")
        canvas.paste(image, ((360 - image.width) // 2, 18))
        thumbs.append(canvas)
    rows = (len(thumbs) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * 360, rows * 190), (70, 70, 70))
    for index, thumb in enumerate(thumbs):
        sheet.paste(thumb, ((index % columns) * 360, (index // columns) * 190))
    sheet.save(target, quality=88)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed", type=int, default=20261030)
    parser.add_argument("--count", type=int, default=140)
    parser.add_argument("--clean", action="store_true", help="remove previously generated puzzles")
    parser.add_argument("--crop", type=float, default=None,
                        help="force one sprite crop fraction for every reference")
    args = parser.parse_args()

    if args.clean:
        for directory in (PUZZLES, PIECES, TEMPLATES):
            shutil.rmtree(directory, ignore_errors=True)
    os.makedirs(PUZZLES, exist_ok=True)
    os.makedirs(PIECES, exist_ok=True)
    os.makedirs(TEMPLATES, exist_ok=True)
    os.makedirs(WORK_OUTPUTS, exist_ok=True)

    manifest = json.load(open(os.path.join(DATASET, "reference_manifest.json"), encoding="utf-8"))
    rng = random.Random(args.seed)

    all_sprites: dict[str, dict[str, dict[str, Sprite]]] = {}
    all_backgrounds: dict[str, dict[int, np.ndarray]] = {}
    template_rows: list[dict] = []

    print(f"Extracting pieces from {len(manifest['references'])} reference screenshots")
    for reference in manifest["references"]:
        crop = args.crop if args.crop is not None else choose_crop(reference)
        reference["crop"] = crop
        sprites, backgrounds = build_reference(reference, crop)
        all_sprites[reference["theme"]] = sprites
        all_backgrounds[reference["theme"]] = backgrounds
        template_rows.extend(write_learned_templates(reference, sprites, backgrounds, crop))
        print(f"  {reference['theme']:<18} crop={crop:.2f} "
              f"{len(sprites['white'])} white / {len(sprites['black'])} black sprites")

    with open(os.path.join(DATASET, "reference_manifest.json"), "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2)
        handle.write("\n")

    manifest_path = os.path.join(TEMPLATES, "manifest.csv")
    with open(manifest_path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["theme", "type", "color", "style", "crop",
                         "mean_r", "mean_g", "mean_b", "resource"])
        for row in template_rows:
            r, g, b = row["mean_rgb"]
            writer.writerow([row["theme"], row["type"], row["color"], row["style"],
                             f"{row['crop']:.2f}", f"{r:.2f}", f"{g:.2f}", f"{b:.2f}",
                             row["resource"]])
    print(f"Wrote {len(template_rows)} learned templates to {manifest_path}")

    references = manifest["references"]
    themes = [reference["theme"] for reference in references]
    labels = []
    train_paths = []
    val_paths = []
    test_paths = []

    print(f"Rendering {args.count} labelled screenshot puzzles")
    for index in range(args.count):
        if index < 100:
            split = "train"
        elif index < 120:
            split = "validation"
        else:
            split = "test"
        reference = references[index % len(references)]
        theme = reference["theme"]
        board = random_position(rng)
        rendered = render_position(board, all_sprites[theme], all_backgrounds[theme], rng)
        screenshot, quad = paste_board(reference, rendered, rng)
        name = f"puzzle_{index + 1:04d}.jpg"
        path = os.path.join(PUZZLES, name)
        cv2.imwrite(path, screenshot, [int(cv2.IMWRITE_JPEG_QUALITY), 82])
        label = {
            "id": f"puzzle_{index + 1:04d}",
            "image": os.path.relpath(path, ROOT),
            "fen": board.fen(),
            "placement": board.board_fen(),
            "side": "w" if board.turn == chess.WHITE else "b",
            "theme": theme,
            "reference": reference["id"],
            "quad": [[float(x), float(y)] for x, y in quad],
            "split": split,
        }
        labels.append(label)
        if split == "train":
            train_paths.append(path)
        elif split == "validation":
            val_paths.append(path)
        else:
            test_paths.append(path)

    labels_csv = os.path.join(DATASET, "labels.csv")
    with open(labels_csv, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=["id", "image", "fen", "placement", "side",
                                                    "theme", "reference", "quad", "split"])
        writer.writeheader()
        writer.writerows(labels)
    with open(os.path.join(DATASET, "labels.json"), "w", encoding="utf-8") as handle:
        json.dump(labels, handle, indent=2)
        handle.write("\n")

    contact_sheet(train_paths[:20], os.path.join(WORK_OUTPUTS, "puzzles_train_preview.jpg"))
    contact_sheet(val_paths[:20], os.path.join(WORK_OUTPUTS, "puzzles_validation_preview.jpg"))
    contact_sheet(test_paths[:20], os.path.join(WORK_OUTPUTS, "puzzles_test_preview.jpg"))

    total_bytes = sum(os.path.getsize(os.path.join(PUZZLES, name)) for name in os.listdir(PUZZLES))
    print(f"Wrote {len(labels)} labels to {labels_csv}")
    print(f"Puzzle image size: {total_bytes / (1024 * 1024):.2f} MiB")
    print("Splits: "
          f"{sum(1 for label in labels if label['split'] == 'train')} train, "
          f"{sum(1 for label in labels if label['split'] == 'validation')} validation, "
          f"{sum(1 for label in labels if label['split'] == 'test')} test")


if __name__ == "__main__":
    main()
