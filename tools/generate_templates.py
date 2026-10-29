"""Generate piece templates from the reference screenshots.

The runtime classifier is pure Java; this script only builds the template
assets shipped in src/main/resources/templates.  It is intentionally kept in
the repository so templates can be regenerated or extended for new themes.

Requires: numpy, opencv-python-headless, pillow.
"""
from __future__ import annotations

import os
import cv2
import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(ROOT, "screenshots")
OUT = os.path.join(ROOT, "src", "main", "resources", "templates")

# Board quads, clockwise from the top-left playing corner (a8).
FLAT_QUAD = np.float32([[751, 33], [1759, 33], [1759, 1044], [751, 1044]])
RENDERED_QUAD = np.float32([[740, 63], [1768, 60], [1926, 1037], [577, 1034]])

FLAT_REFERENCES = [
    "Screenshot_2026-10-29-20-02-09-026_com.llx.chess.jpg",
    "Screenshot_2026-10-29-20-02-12-688_com.llx.chess.jpg",
    "Screenshot_2026-10-29-20-02-19-616_com.llx.chess.jpg",
]
RENDERED_REFERENCE = "Screenshot_2026-10-29-19-54-31-538_com.llx.chess.jpg"

INITIAL = [
    list("rnbqkbnr"),
    list("pppppppp"),
    list("........"),
    list("........"),
    list("........"),
    list("........"),
    list("PPPPPPPP"),
    list("RNBQKBNR"),
]
NAMES = {"p": "pawn", "n": "knight", "b": "bishop",
         "r": "rook", "q": "queen", "k": "king"}


def warp(path: str, quad: np.ndarray, size: int = 768) -> np.ndarray:
    image = cv2.imread(os.path.join(SHOTS, path))
    if image is None:
        raise FileNotFoundError(path)
    dst = np.float32([[0, 0], [size, 0], [size, size], [0, size]])
    h = cv2.getPerspectiveTransform(quad, dst)
    return cv2.warpPerspective(image, h, (size, size))


def flat_mask(cell: np.ndarray) -> np.ndarray:
    """Foreground silhouette for the flat, vector-style piece themes."""
    h, w = cell.shape[:2]
    margin = 6
    interior = cell[margin:h - margin, margin:w - margin]
    bg = np.median(interior.reshape(-1, 3), axis=0)
    rgb = cell.astype(np.float32)
    distance = np.max(np.abs(rgb - bg), axis=2)
    lum_shift = np.abs(rgb.mean(axis=2) - bg.mean())
    mask = ((distance > 30) | (lum_shift > 34)).astype(np.uint8)
    mask[:4] = mask[-4:] = 0
    mask[:, :4] = mask[:, -4:] = 0
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask, 8)
    best, best_score = 0, -1.0
    for i in range(1, count):
        area = stats[i, cv2.CC_STAT_AREA]
        if area < 80:
            continue
        left = stats[i, cv2.CC_STAT_LEFT]
        top = stats[i, cv2.CC_STAT_TOP]
        width = stats[i, cv2.CC_STAT_WIDTH]
        height = stats[i, cv2.CC_STAT_HEIGHT]
        if left <= 2 or top <= 2 or left + width >= w - 3 or top + height >= h - 3:
            continue
        cx = left + width / 2
        cy = top + height / 2
        score = area / (1.0 + ((cx - w / 2) ** 2 + (cy - h / 2) ** 2) / 300.0)
        if score > best_score:
            best, best_score = i, score
    return ((labels == best) * 255).astype(np.uint8)


def normalize_mask(mask: np.ndarray, size: int = 48) -> np.ndarray:
    """Crop to the silhouette and scale it to fill a square."""
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


def edge_image(cell: np.ndarray) -> np.ndarray:
    """Gradient response used for the rendered/3-D piece themes."""
    gray = cv2.cvtColor(cell, cv2.COLOR_BGR2GRAY)
    gray = cv2.equalizeHist(gray)
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    edge = cv2.GaussianBlur(np.sqrt(gx * gx + gy * gy), (5, 5), 0)
    edge = edge[10:-10, 10:-10]
    edge -= edge.min()
    peak = edge.max() or 1.0
    return np.clip(edge / peak * 255.0, 0, 255).astype(np.uint8)


def build(kind: str, references: list[str], quad: np.ndarray) -> None:
    out_dir = os.path.join(OUT, kind)
    os.makedirs(out_dir, exist_ok=True)
    samples: dict[str, list[np.ndarray]] = {}
    for reference in references:
        board = warp(reference, quad)
        for row in range(8):
            for col in range(8):
                piece = INITIAL[row][col]
                if piece == ".":
                    continue
                cell = board[row * 96:(row + 1) * 96, col * 96:(col + 1) * 96]
                image = flat_mask(cell) if kind == "2d" else edge_image(cell)
                if kind == "2d":
                    image = normalize_mask(image, 48)
                    if piece.lower() == "n" and col >= 4:
                        image = np.fliplr(image)
                samples.setdefault(piece.lower(), []).append(image)
    for piece, images in samples.items():
        averaged = np.mean(images, axis=0)
        if kind == "2d":
            averaged = (averaged > 127).astype(np.uint8) * 255
        else:
            averaged = np.clip(averaged, 0, 255).astype(np.uint8)
        averaged = cv2.resize(averaged, (48, 48), interpolation=cv2.INTER_AREA)
        Image.fromarray(averaged).save(os.path.join(out_dir, NAMES[piece] + ".png"))
        print(kind, NAMES[piece], len(images))


if __name__ == "__main__":
    build("2d", FLAT_REFERENCES, FLAT_QUAD)
    build("3d", [RENDERED_REFERENCE], RENDERED_QUAD)
