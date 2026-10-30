#!/usr/bin/env python3
"""Download freely licensed chess images from Wikimedia Commons.

The issue that motivated this tool asked for 1000+ chess images from the
internet.  Random web screenshots are usually copyrighted and cannot be
committed to the repository, so this downloader uses Wikimedia Commons, keeps
the license/attribution metadata for every file, and stores the images outside
the tracked source tree by default.

The download is resumable and deterministic enough for repeated runs: files are
deduplicated by SHA-256, the manifest is written after every image, and already
recorded titles are skipped.

Example::

    python3 tools/download_chess_images.py --target 1200 \
        --out .agent-work/tmp/internet_images \
        --manifest dataset/internet_manifest.json

Only Python's standard library is required for downloading.  ``pillow`` is
needed for the optional contact sheet.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
API = "https://commons.wikimedia.org/w/api.php"
USER_AGENT = ("chess-ai-vision-dataset/1.0 "
              "(https://github.com/promikailamin/chess_ai; dataset builder)")

DEFAULT_QUERIES = [
    'incategory:"Chess software screenshots"',
    'incategory:"Chess diagrams"',
    'incategory:"Chess game positions"',
    'incategory:"Chess openings"',
    'incategory:"Chess tactics"',
    'incategory:"Chess pieces"',
    'incategory:"Computer chess"',
    'incategory:"Chess variants"',
    'incategory:"Chess websites"',
    'incategory:"Chess equipment"',
    'incategory:"Chess games"',
    "chess screenshot",
    "chess board screenshot",
    "chess video game",
    "chess computer game",
    "chess app",
    "chess online game",
    "chessboard screenshot",
    "chess position",
    "chess diagram",
    "chess game analysis",
    "chess puzzle board",
    "chess opening board",
]

ALLOWED_MIME = {
    "image/jpeg": ".jpg",
    "image/png": ".png",
}
TAG_RE = re.compile(r"<[^>]+>")


def clean(value) -> str:
    if not value:
        return ""
    text = html.unescape(TAG_RE.sub(" ", str(value)))
    return " ".join(text.split())


def retry_wait(error: Exception, attempt: int, retries: int) -> None:
    if attempt >= retries:
        raise error
    retry_after = None
    if isinstance(error, urllib.error.HTTPError):
        retry_after = error.headers.get("Retry-After") if error.headers else None
    if retry_after and retry_after.isdigit():
        delay = float(retry_after)
    else:
        delay = min(60.0, 4.0 * (2 ** attempt))
    print(f"  rate limited / transient error; retrying in {delay:.0f}s")
    time.sleep(delay)


def api_get(params: dict, retries: int = 5) -> dict:
    query = urllib.parse.urlencode({**params, "format": "json", "formatversion": "2",
                                    "maxlag": "5"})
    request = urllib.request.Request(f"{API}?{query}", headers={"User-Agent": USER_AGENT})
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.loads(response.read().decode("utf-8"))
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as error:
            retry_wait(error, attempt, retries)
    raise RuntimeError("unreachable")


def search_files(query: str, limit: int, page_size: int = 50,
                 retries: int = 5) -> list[dict]:
    results = []
    offset = 0
    while len(results) < limit:
        params = {
            "action": "query",
            "generator": "search",
            "gsrsearch": query,
            "gsrnamespace": "6",
            "gsrlimit": str(min(page_size, limit - len(results))),
            "gsroffset": str(offset),
            "prop": "imageinfo",
            "iiprop": "url|extmetadata|size|mime",
            "iiurlwidth": "1600",
        }
        try:
            payload = api_get(params, retries)
        except Exception as error:  # noqa: BLE001 - network errors should not abort the run
            print(f"  query failed ({query}): {error}")
            break
        pages = payload.get("query", {}).get("pages", [])
        if not pages:
            break
        results.extend(pages)
        offset += len(pages)
        if "continue" not in payload:
            break
        time.sleep(0.15)
    return results[:limit]


def image_record(page: dict, query: str) -> dict | None:
    info = (page.get("imageinfo") or [{}])[0]
    mime = info.get("mime", "")
    if mime not in ALLOWED_MIME:
        return None
    width = int(info.get("width") or 0)
    height = int(info.get("height") or 0)
    if width < 320 or height < 320:
        return None
    if width * height > 40_000_000:
        return None
    metadata = info.get("extmetadata") or {}
    license_name = clean((metadata.get("LicenseShortName") or {}).get("value"))
    license_url = clean((metadata.get("LicenseUrl") or {}).get("value"))
    if not license_name and not license_url:
        # Keep only files with an explicit reusable license.
        return None
    url = info.get("thumburl") or info.get("url")
    if not url:
        return None
    return {
        "pageid": page.get("pageid"),
        "title": page.get("title", ""),
        "query": query,
        "mime": mime,
        "width": width,
        "height": height,
        "download_url": url,
        "description_url": info.get("descriptionurl", ""),
        "license": license_name,
        "license_url": license_url,
        "artist": clean((metadata.get("Artist") or {}).get("value")),
        "credit": clean((metadata.get("Credit") or {}).get("value")),
        "description": clean((metadata.get("ImageDescription") or {}).get("value"))[:500],
        "date": clean((metadata.get("DateTimeOriginal") or {}).get("value")),
    }


def load_manifest(path: str) -> dict:
    if os.path.isfile(path):
        try:
            with open(path, encoding="utf-8") as handle:
                data = json.load(handle)
            if isinstance(data, dict) and isinstance(data.get("images"), list):
                return data
        except (OSError, json.JSONDecodeError):
            pass
    return {
        "version": 1,
        "source": "Wikimedia Commons",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "images": [],
    }


def save_manifest(path: str, manifest: dict) -> None:
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    temporary = path + ".tmp"
    with open(temporary, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2)
        handle.write("\n")
    os.replace(temporary, path)


def download(url: str, max_bytes: int, retries: int = 5) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                data = response.read(max_bytes + 1)
            if len(data) > max_bytes:
                raise ValueError(f"file larger than {max_bytes} bytes")
            return data
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as error:
            retry_wait(error, attempt, retries)
    raise RuntimeError("unreachable")


def contact_sheet(manifest: dict, out_dir: str, target: str, columns: int = 6,
                  limit: int = 48) -> None:
    try:
        from PIL import Image
    except ImportError:
        print("  pillow not installed; skipping contact sheet")
        return
    thumbnails = []
    for record in manifest["images"][:limit]:
        path = os.path.join(ROOT, record["local_path"])
        if not os.path.isfile(path):
            continue
        try:
            image = Image.open(path).convert("RGB")
        except Exception:  # noqa: BLE001 - skip unreadable downloads
            continue
        image.thumbnail((260, 180))
        canvas = Image.new("RGB", (260, 205), "white")
        canvas.paste(image, ((260 - image.width) // 2, 10))
        thumbnails.append(canvas)
    if not thumbnails:
        return
    rows = (len(thumbnails) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * 260, rows * 205), (60, 60, 60))
    for index, thumbnail in enumerate(thumbnails):
        sheet.paste(thumbnail, ((index % columns) * 260, (index // columns) * 205))
    os.makedirs(os.path.dirname(os.path.abspath(target)), exist_ok=True)
    sheet.save(target, quality=86)
    print(f"  contact sheet: {target} ({len(thumbnails)} images)")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", type=int, default=1200,
                        help="number of unique images to download")
    parser.add_argument("--out", default=os.path.join(ROOT, ".agent-work", "tmp", "internet_images"),
                        help="directory for downloaded images")
    parser.add_argument("--manifest", default=os.path.join(ROOT, "dataset", "internet_manifest.json"),
                        help="JSON manifest path")
    parser.add_argument("--queries", default="",
                        help="comma-separated queries; defaults to a chess-focused list")
    parser.add_argument("--max-per-query", type=int, default=300,
                        help="maximum search results inspected per query")
    parser.add_argument("--max-bytes", type=int, default=12 * 1024 * 1024,
                        help="maximum size of a single downloaded image")
    parser.add_argument("--contact-sheet", default=os.path.join(
        ROOT, ".agent-work", "outputs", "internet_contact.jpg"))
    parser.add_argument("--sleep", type=float, default=0.75,
                        help="delay between downloads in seconds")
    parser.add_argument("--retries", type=int, default=5,
                        help="retries for transient HTTP errors / rate limits")
    args = parser.parse_args()

    queries = ([part.strip() for part in args.queries.split(",") if part.strip()]
               or DEFAULT_QUERIES)
    os.makedirs(args.out, exist_ok=True)
    manifest = load_manifest(args.manifest)
    images = manifest["images"]
    known_titles = {record["title"] for record in images}
    known_hashes = {record["sha256"] for record in images if record.get("sha256")}
    start_count = len(images)
    print(f"Resuming with {start_count} images; target {args.target}")

    for query_index, query in enumerate(queries, start=1):
        if len(images) >= args.target:
            break
        print(f"[{query_index}/{len(queries)}] searching: {query}")
        pages = search_files(query, args.max_per_query, retries=args.retries)
        print(f"  {len(pages)} candidates")
        for page in pages:
            if len(images) >= args.target:
                break
            record = image_record(page, query)
            if record is None or record["title"] in known_titles:
                continue
            try:
                data = download(record["download_url"], args.max_bytes, args.retries)
            except Exception as error:  # noqa: BLE001 - keep going on individual failures
                print(f"  skip {record['title']}: {error}")
                continue
            digest = hashlib.sha256(data).hexdigest()
            if digest in known_hashes:
                continue
            extension = ALLOWED_MIME[record["mime"]]
            filename = f"{digest[:16]}{extension}"
            local_path = os.path.join(args.out, filename)
            with open(local_path, "wb") as handle:
                handle.write(data)
            record.update({
                "sha256": digest,
                "bytes": len(data),
                "local_path": os.path.relpath(local_path, ROOT),
                "downloaded_at": datetime.now(timezone.utc).isoformat(),
            })
            images.append(record)
            known_titles.add(record["title"])
            known_hashes.add(digest)
            save_manifest(args.manifest, manifest)
            if len(images) % 25 == 0:
                print(f"  downloaded {len(images)}/{args.target}")
            time.sleep(max(0.0, args.sleep))

    manifest["target"] = args.target
    manifest["generated_at"] = datetime.now(timezone.utc).isoformat()
    manifest["count"] = len(images)
    save_manifest(args.manifest, manifest)
    print(f"Downloaded {len(images) - start_count} new images "
          f"({len(images)} total) to {args.out}")
    print(f"Manifest: {args.manifest}")
    contact_sheet(manifest, args.out, args.contact_sheet)


if __name__ == "__main__":
    main()
