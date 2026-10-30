#!/usr/bin/env python3
"""Download chess datasets from Hugging Face for vision/engine training.

The original ``download_chess_images.py`` pulls unlabeled photos from Wikimedia
Commons.  That is useful for board-detection evaluation, but it cannot train a
piece classifier because there are no FENs.  This script instead streams small,
labelled slices of several public Hugging Face datasets through the datasets
server API, so a single shard (often hundreds of megabytes) is never downloaded
and the images that are kept have an explicit licence and a machine-readable
label.

Supported sources
-----------------
``lichess-boards``
    ``bingbangboom/chess-puzzles-images-mini`` (CC0-1.0).  Real Lichess puzzle
    screenshots with the board placement, side to move, castling and en-passant
    state.  These are used to train a new ``lichess-brown`` runtime theme.

``chess-games``
    ``angeluriot/chess_games`` (MIT).  Fourteen million high-level games.  The
    script samples positions from randomly chosen games and writes realistic
    FENs that ``build_vision_dataset.py`` can render with the bundled sprites.

``board-to-fen``
    ``paulprt/chess-board-to-fen`` (CC-BY-NC-4.0).  Optional labelled board
    images.  Only use this source when the non-commercial licence is acceptable.

Examples
--------
    python3 tools/download_hf_chess_datasets.py --source lichess-boards \
        --splits train,validation,test --limit 300,60,60 \
        --out dataset/external/lichess_boards

    python3 tools/download_hf_chess_datasets.py --source chess-games \
        --limit 300 --positions-per-game 3 \
        --out dataset/external/chess_games

Only Python's standard library is required.  ``python-chess`` is used when it is
available (it validates the FENs); otherwise the script falls back to a small
built-in FEN normaliser.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROWS_API = "https://datasets-server.huggingface.co/rows"
SPLITS_API = "https://datasets-server.huggingface.co/splits"
USER_AGENT = ("chess-ai-vision-dataset/2.0 "
              "(https://github.com/promikailamin/chess_ai; dataset builder)")

# Lichess renders a 512x512 board with coordinate labels around the playing
# area.  The grid spans x/y = 19..492 on every image in the mini split.
LICHESS_BOARD_INSET = 19.0 / 512.0

SOURCES = {
    "lichess-boards": {
        "dataset": "bingbangboom/chess-puzzles-images-mini",
        "config": "default",
        "kind": "board-images",
        "license": "CC0-1.0",
        "license_url": "https://huggingface.co/datasets/bingbangboom/chess-puzzles-images-mini",
        "theme": "lichess-brown",
        "quad_inset": LICHESS_BOARD_INSET,
    },
    "board-to-fen": {
        "dataset": "paulprt/chess-board-to-fen",
        "config": "default",
        "kind": "board-images",
        "license": "CC-BY-NC-4.0",
        "license_url": "https://huggingface.co/datasets/paulprt/chess-board-to-fen",
        "theme": "external-board",
        "quad_inset": 0.0,
    },
    "chess-games": {
        "dataset": "angeluriot/chess_games",
        "config": "default",
        "kind": "games",
        "license": "MIT",
        "license_url": "https://github.com/angeluriot/Chess_games",
        "theme": "chess-games",
        "quad_inset": 0.0,
    },
}

TAG_RE = re.compile(r"<[^>]+>")


def clean(value) -> str:
    if value is None:
        return ""
    return " ".join(TAG_RE.sub(" ", str(value)).split())


def api_json(url: str, retries: int = 5) -> dict:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    last = None
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                return json.loads(response.read().decode("utf-8"))
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as error:
            last = error
            if attempt >= retries:
                break
            delay = 4.0 * (2 ** attempt)
            retry_after = None
            if isinstance(error, urllib.error.HTTPError) and error.headers:
                retry_after = error.headers.get("Retry-After")
            if retry_after and retry_after.isdigit():
                delay = float(retry_after)
            print(f"  transient error ({error}); retrying in {delay:.0f}s")
            time.sleep(delay)
    raise RuntimeError(f"request failed: {url}: {last}")


def rows_url(source: dict, split: str, offset: int, length: int) -> str:
    query = urllib.parse.urlencode({
        "dataset": source["dataset"],
        "config": source["config"],
        "split": split,
        "offset": offset,
        "length": min(100, length),
    })
    return f"{ROWS_API}?{query}"


def available_splits(source: dict) -> list[str]:
    try:
        data = api_json(f"{SPLITS_API}?{urllib.parse.urlencode({'dataset': source['dataset']})}")
    except Exception:  # noqa: BLE001 - fall back to the common split names
        return ["train"]
    for entry in data.get("splits", []):
        if entry.get("config") == source["config"]:
            return [item["split"] for item in entry.get("splits", [])]
    return ["train"]


def download_image(url: str, path: str, retries: int = 5) -> int:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                data = response.read()
            os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
            temporary = path + ".tmp"
            with open(temporary, "wb") as handle:
                handle.write(data)
            os.replace(temporary, path)
            return len(data)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as error:
            if attempt >= retries:
                raise
            delay = 4.0 * (2 ** attempt)
            print(f"  image error ({error}); retrying in {delay:.0f}s")
            time.sleep(delay)
    raise RuntimeError("unreachable")


def orient_board_image(path: str, active: str, source_name: str) -> None:
    """Lichess screenshots are rendered from the side to move.

    The dataset FEN always uses White's point of view, so a board that belongs
    to a black-to-move puzzle is rotated by 180 degrees before it is stored.
    This keeps every training image in the same orientation as the runtime
    templates.  Sources without a known orientation are left untouched.
    """
    if source_name != "lichess-boards" or active != "b":
        return
    try:
        from PIL import Image  # type: ignore
    except ImportError:
        print("  pillow is not installed; leaving black-perspective image unrotated")
        return
    with Image.open(path) as image:
        image.rotate(180).save(path, quality=92)


def placement_to_fen(placement: str, active: str, castling: str, en_passant: str) -> str:
    placement = (placement or "8/8/8/8/8/8/8/8").strip()
    parts = placement.split()
    if len(parts) == 6:
        return placement
    active = (active or "w").strip()
    if active not in ("w", "b"):
        active = "w"
    castling = (castling or "-").strip() or "-"
    en_passant = (en_passant or "-").strip() or "-"
    return f"{placement} {active} {castling} {en_passant} 0 1"


def validate_fen(fen: str) -> str | None:
    try:
        import chess  # type: ignore
    except ImportError:
        return fen
    try:
        board = chess.Board(fen)
    except ValueError:
        return None
    return board.fen()


def relative(path: str) -> str:
    return os.path.relpath(path, ROOT)


def load_manifest(path: str, source_name: str, source: dict) -> dict:
    if os.path.isfile(path):
        try:
            with open(path, encoding="utf-8") as handle:
                data = json.load(handle)
            if isinstance(data, dict) and isinstance(data.get("images"), list):
                return data
        except (OSError, json.JSONDecodeError):
            pass
    return {
        "version": 2,
        "source": source_name,
        "dataset": source["dataset"],
        "license": source["license"],
        "license_url": source["license_url"],
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "images": [],
        "positions": [],
    }


def save_json(path: str, value) -> None:
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    temporary = path + ".tmp"
    with open(temporary, "w", encoding="utf-8") as handle:
        json.dump(value, handle, indent=2)
        handle.write("\n")
    os.replace(temporary, path)


def parse_limits(values, splits: list[str], default: int) -> dict[str, int]:
    limits = {}
    flat = []
    for value in values:
        for part in str(value).split(","):
            part = part.strip()
            if part:
                flat.append(part)
    if not flat:
        flat = [str(default)] * len(splits)
    if len(flat) == 1:
        flat = flat * len(splits)
    for split, value in zip(splits, flat):
        limits[split] = int(value)
    return limits


def fetch_board_images(source_name: str, source: dict, args: argparse.Namespace,
                       out_dir: str) -> None:
    splits = args.splits
    limits = parse_limits(args.limit, splits, 300)
    labels_path = os.path.join(out_dir, "labels.json")
    manifest_path = os.path.join(out_dir, "manifest.json")
    manifest = load_manifest(manifest_path, source_name, source)
    existing = {label["id"] for label in load_report(labels_path).get("labels", [])}
    labels = load_report(labels_path).get("labels", [])
    rng = random.Random(args.seed)
    image_dir = os.path.join(out_dir, "images")
    os.makedirs(image_dir, exist_ok=True)
    fetched = 0

    for split in splits:
        wanted = limits.get(split, 0)
        if wanted <= 0:
            continue
        rows = []
        offset = args.offset
        while len(rows) < wanted:
            chunk = api_json(rows_url(source, split, offset, wanted - len(rows)))
            page = chunk.get("rows", [])
            if not page:
                break
            rows.extend(page)
            offset += len(page)
            if len(page) < 100:
                break
            time.sleep(0.2)
        rng.shuffle(rows)
        taken = 0
        for entry in rows:
            if taken >= wanted:
                break
            row = entry.get("row", {})
            image_info = row.get("image") or {}
            image_url = image_info.get("src") or image_info.get("url")
            if not image_url:
                continue
            placement = row.get("board_state") or row.get("fen") or row.get("board_fen")
            if not placement:
                continue
            active = row.get("active_color", "w")
            castling = row.get("castling_rights", "-")
            en_passant = row.get("en_passant_target_square", "-")
            fen = placement_to_fen(placement, active, castling, en_passant)
            fen = validate_fen(fen)
            if not fen:
                continue
            identifier = f"{source_name}_{split}_{entry.get('row_idx', offset)}"
            if identifier in existing:
                taken += 1
                continue
            width = int(image_info.get("width") or 0)
            height = int(image_info.get("height") or 0)
            if width * height < 64 * 64:
                continue
            filename = f"{identifier}.jpg"
            path = os.path.join(image_dir, filename)
            try:
                size = download_image(image_url, path)
            except Exception as error:  # noqa: BLE001 - keep the run going
                print(f"  skip {identifier}: {error}")
                continue
            orient_board_image(path, active, source_name)
            inset = float(source.get("quad_inset", 0.0))
            x0 = inset * width
            y0 = inset * height
            x1 = (1.0 - inset) * width
            y1 = (1.0 - inset) * height
            label = {
                "id": identifier,
                "image": relative(path),
                "fen": fen,
                "placement": fen.split()[0],
                "side": fen.split()[1],
                "theme": source["theme"],
                "reference": source_name,
                "quad": [[x0, y0], [x1, y0], [x1, y1], [x0, y1]],
                "split": split,
                "source_dataset": source["dataset"],
                "license": source["license"],
            }
            labels.append(label)
            existing.add(identifier)
            manifest["images"].append({
                "id": identifier,
                "file": relative(path),
                "bytes": size,
                "row_idx": entry.get("row_idx"),
                "split": split,
                "image_url": image_url,
                "license": source["license"],
                "dataset": source["dataset"],
                "downloaded_at": datetime.now(timezone.utc).isoformat(),
            })
            taken += 1
            fetched += 1
            if fetched % 25 == 0:
                print(f"  {source_name}: {fetched} new images")
                save_json(manifest_path, manifest)
                save_json(labels_path, {"labels": labels})
        print(f"  {source_name}/{split}: kept {taken}/{wanted}")

    manifest["count"] = len(labels)
    manifest["generated_at"] = datetime.now(timezone.utc).isoformat()
    save_json(manifest_path, manifest)
    save_json(labels_path, {"labels": labels})
    print(f"Wrote {len(labels)} labels to {labels_path}")


def load_report(path: str) -> dict:
    if not os.path.isfile(path):
        return {}
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
        return data if isinstance(data, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def fetch_games(source_name: str, source: dict, args: argparse.Namespace,
                out_dir: str) -> None:
    split = args.splits[0] if args.splits else "train"
    manifest_path = os.path.join(out_dir, "manifest.json")
    positions_path = os.path.join(out_dir, "positions.json")
    manifest = load_manifest(manifest_path, source_name, source)
    report = load_report(positions_path)
    positions = report.get("positions", [])
    seen = {(position.get("source"), position.get("game_index"), position.get("ply"))
            for position in positions}
    rng = random.Random(args.seed)
    wanted_games = args.limit[0] if args.limit else 300
    fetched_games = 0
    offset = args.offset
    new_positions = 0

    while fetched_games < wanted_games:
        chunk = api_json(rows_url(source, split, offset, min(100, wanted_games - fetched_games)))
        rows = chunk.get("rows", [])
        if not rows:
            break
        for entry in rows:
            if fetched_games >= wanted_games:
                break
            row = entry.get("row", {})
            moves = row.get("moves_uci") or row.get("moves_san") or []
            if not moves:
                continue
            game_index = entry.get("row_idx", offset)
            samples = max(1, args.positions_per_game)
            plies = sorted({min(len(moves), max(1, rng.randint(4, len(moves))))
                            for _ in range(samples)})
            try:
                import chess  # type: ignore
                board = chess.Board()
                for ply, move_uci in enumerate(moves, start=1):
                    if ply > max(plies):
                        break
                    try:
                        board.push_uci(move_uci)
                    except ValueError:
                        board = None
                        break
                    if ply in plies and board is not None:
                        if board.is_game_over():
                            continue
                        key = (source_name, game_index, ply)
                        if key in seen:
                            continue
                        positions.append({
                            "id": f"{source_name}_{game_index}_{ply}",
                            "fen": board.fen(),
                            "source": source_name,
                            "game_index": game_index,
                            "ply": ply,
                            "white_elo": row.get("white_elo"),
                            "black_elo": row.get("black_elo"),
                            "end_type": row.get("end_type"),
                            "dataset": source["dataset"],
                            "license": source["license"],
                        })
                        seen.add(key)
                        new_positions += 1
            except ImportError:
                print("  python-chess is not installed; cannot replay games")
                return
            fetched_games += 1
            if fetched_games % 50 == 0:
                print(f"  {source_name}: {fetched_games} games, {new_positions} positions")
                save_json(positions_path, {"positions": positions})
        offset += len(rows)
        if len(rows) < 100:
            break
        time.sleep(0.2)

    rng.shuffle(positions)
    report = {
        "version": 1,
        "source": source_name,
        "dataset": source["dataset"],
        "license": source["license"],
        "license_url": source["license_url"],
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "games": fetched_games,
        "count": len(positions),
        "positions": positions,
    }
    save_json(positions_path, report)
    manifest["games"] = fetched_games
    manifest["count"] = len(positions)
    manifest["generated_at"] = datetime.now(timezone.utc).isoformat()
    save_json(manifest_path, manifest)
    print(f"Wrote {len(positions)} positions to {positions_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", action="append", required=True,
                        choices=sorted(SOURCES),
                        help="source to download; repeat for several sources")
    parser.add_argument("--splits", default="train",
                        help="comma-separated HF splits (default: train)")
    parser.add_argument("--limit", action="append", default=[],
                        help="number of images/games per split; repeat or comma-separate")
    parser.add_argument("--positions-per-game", type=int, default=2,
                        help="positions sampled from each game (chess-games)")
    parser.add_argument("--offset", type=int, default=0,
                        help="row offset into the HF split (useful for extra slices)")
    parser.add_argument("--seed", type=int, default=20261030)
    parser.add_argument("--out", default=os.path.join(ROOT, "dataset", "external"),
                        help="output directory")
    args = parser.parse_args()

    splits = [part.strip() for part in args.splits.split(",") if part.strip()]
    args.splits = splits or ["train"]
    if not args.limit:
        args.limit = [str(300)]
    # Normalise --limit into a flat list of integers.
    flat_limits = []
    for value in args.limit:
        flat_limits.extend(int(part) for part in value.split(",") if part.strip())
    args.limit = flat_limits

    for source_name in args.source:
        source = SOURCES[source_name]
        out_dir = os.path.join(args.out, source_name.replace("-", "_"))
        os.makedirs(out_dir, exist_ok=True)
        print(f"== {source_name} ({source['dataset']}, {source['license']}) -> {out_dir}")
        if source["kind"] == "board-images":
            fetch_board_images(source_name, source, args, out_dir)
        else:
            fetch_games(source_name, source, args, out_dir)


if __name__ == "__main__":
    main()
