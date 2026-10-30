# Chess AI

A dependency-light Java 17 project for reading chess game screenshots, choosing
moves with a built-in engine, replaying positions in a screenshot-style
simulator, and running engine-vs-engine self-play games.

## Features

- **Screenshot recognition** - finds the board with a Hough/grid search and
  classifies all 64 squares from bundled piece templates.
- **Chess engine** - legal move generation, alpha-beta search with iterative
  deepening, transposition table, quiescence search, null-move pruning, LMR,
  killer/history heuristics and tapered piece-square evaluation.
- **Simulator** - Swing board with click-to-move, engine replies, hints,
  auto-play and board flipping.
- **Self-play** - headless engine-vs-engine games with repetition, fifty-move
  and insufficient-material draws plus aggregate statistics.
- **Perft/regression tests** - JUnit tests cover standard perft positions,
  FEN round-trips, checkmate/stalemate and screenshot recognition.

## Requirements

- Java 17+
- Maven 3.8+
- Python 3.10+ only if you want to regenerate templates or build a vision
  dataset. Install the optional tooling with
  `pip install -r tools/requirements-vision.txt`.

## Build and test

```bash
mvn clean test
mvn package
```

The runnable jar is written to `target/chess-ai-1.0.0.jar`.

## Usage

```bash
# Analyze a screenshot, print the recognized FEN and best move, and write
# <name>_analysis.png with the detected grid and chosen move highlighted.
java -jar target/chess-ai-1.0.0.jar analyze screenshots/Screenshot_2026-10-29-20-02-09-026_com.llx.chess.jpg

# Optional analysis controls
java -jar target/chess-ai-1.0.0.jar analyze <image> --depth 8 --time 3000
java -jar target/chess-ai-1.0.0.jar analyze <image> --fen "<FEN>" --no-overlay

# Open the Swing simulator
java -jar target/chess-ai-1.0.0.jar gui

# Run 1000 self-play games (depth 3, 10 ms per move)
java -jar target/chess-ai-1.0.0.jar selfplay --games 1000 --depth 3 --time 10

# Engine sanity checks
java -jar target/chess-ai-1.0.0.jar perft 5
java -jar target/chess-ai-1.0.0.jar bench
```

`perft 5` from the start position should report `4865609` nodes.

## Android floating bot

The `android/` directory contains a complete Android app that reuses the same
engine, screenshot recognition and move planner. It targets Android 11+ and
does not need root or a separate server.

```bash
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To build and publish an APK from GitHub, run the **Build Android APK**
workflow manually (Actions -> Build Android APK -> Run workflow). It builds the
selected APK type, uploads it as a workflow artifact, and creates or updates a
GitHub release with the APK and its SHA-256 checksum using the `PAT_TOKEN`
secret. Leave the version input empty to generate a `v1.0.<run_number>` tag, or
provide an explicit tag such as `v1.0.0`.

1. Open **Chess AI Bot** and enable the accessibility service, then return to
   the app.
2. Set the target package (default `com.llx.chess`) and open that chess app.
3. Tap the floating pawn, choose whether the bot plays white or black and press
   **Start**.
4. The service reads the on-screen move indicator (for example
   `1.White's Move`) from the accessibility tree, with an ML Kit screenshot-OCR
   fallback. When it is the bot's turn it captures the display, recognises the
   board with the shared vision pipeline, searches for a move and taps the
   from/to squares with accessibility gestures.
5. It waits while the opponent moves, handles a board that the chess app has
   flipped for black, auto-promotes to a queen when the promotion dialog is
   exposed as an accessibility node, and stops on checkmate, stalemate, a draw
   or a win/loss message.

The floating ball and control panel use an accessibility overlay, so the app
stays out of the way while it is playing. The panel is hidden automatically
when a game starts so it does not cover the board in screenshots.

## Recognition notes

The classifier finds the board with a Hough/grid search and matches every
square against per-theme colour templates that encode both piece shape and
appearance. The primary 2D reference screenshot is recognized exactly as:

```text
rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1
```

The default template set is `color` (`src/main/resources/templates/color`).
Select a different classpath set with `-Dchessai.templates=<name>`, or point at
a template directory produced by the trainer with
`-Dchessai.templates.dir=<path> -Dchessai.templates=<name>`. For debugging a
single board theme, add `-Dchessai.forceTheme=<theme>`.

On the 157-case manual benchmark (140 generated puzzles plus the 17 reference
screenshots) the bundled `color` templates score **97.5% exact positions** and
**99.84% correct squares**. The remaining errors are isolated single-square
piece confusions; the benchmark supplies manual board corners, so it does not
measure automatic board detection.

The bundled `lichess-brown` theme was trained from 240 real labelled Lichess
screenshots. With automatic board detection it scores **319/320 exact
positions (99.7%)** and **100.00% correct squares** on the held-out external
set. The same run still scores 97.5% / 99.84% on the manual benchmark. The
single remaining miss is a black queen classified as a king.

## Vision dataset and training

The reproducible vision tooling lives in `tools/`:

```bash
# 1. Download chess images and licence metadata from Wikimedia Commons.
#    The downloader is resumable and writes dataset/internet_manifest.json.
python3 tools/download_chess_images.py --target 1200 \
    --out .agent-work/tmp/internet_images \
    --manifest dataset/internet_manifest.json

# 2. Generate labelled screenshots from the 17 reference boards.
#    --augment strong adds scale/perspective/JPEG/blur/noise variation.
python3 tools/build_vision_dataset.py --count 1200 \
    --train-size 1000 --validation-size 100 \
    --dataset-dir .agent-work/tmp/vision_dataset \
    --templates-dir .agent-work/tmp/vision_dataset/learned \
    --augment strong --crop 1.0

# 3. Train per-theme templates (median sprite per piece).
python3 tools/train_vision_model.py \
    --labels .agent-work/tmp/vision_dataset/labels.json \
    --out .agent-work/tmp/vision_dataset/trained \
    --splits train --exemplars 1

# 4. Benchmark a template set on held-out puzzles.
javac -cp target/classes -d .agent-work/tmp \
    .agent-work/tests/VisionBenchmark.java
java -cp target/classes:.agent-work/tmp \
    -Dchessai.templates=color \
    -Dchessai.templates.dir=src/main/resources/templates/color \
    VisionBenchmark --manual .agent-work/tmp/vision_dataset/test_cases.tsv
```

### External labelled datasets

`tools/download_hf_chess_datasets.py` downloads and converts data from the
Hugging Face datasets server without extra dependencies:

```bash
# 320 real Lichess board images (240 train / 40 validation / 40 test).
python3 tools/download_hf_chess_datasets.py --source lichess-boards \
    --splits train --limit 240,40,40 --out dataset/external

# 799 real FEN positions sampled from angeluriot/chess_games (MIT).
python3 tools/download_hf_chess_datasets.py --source chess-games \
    --splits train --limit 400 --positions-per-game 2 \
    --out dataset/external

# Optional board-to-FEN dataset (CC-BY-NC-4.0, non-commercial use only).
python3 tools/download_hf_chess_datasets.py --source board-to-fen \
    --splits train --limit 100 --out dataset/external
```

The downloader writes `labels.json`/`positions.json` plus licence metadata.
`dataset/external/lichess_boards` is committed so the automatic-detection
regression tests and benchmarks are reproducible. The `chess-games` positions
can be fed to the screenshot generator:

```bash
python3 tools/build_vision_dataset.py --count 400 \
    --dataset-dir .agent-work/tmp/vision_positions \
    --templates-dir .agent-work/tmp/vision_positions/learned \
    --positions dataset/external/chess_games/positions.json
```

`tools/merge_template_sets.py` merges a trained theme directory into the
bundled `color` template set, including `manifest.csv`, `theme_stats.csv` and
the optional per-theme `theme_options.csv` shape-rerank threshold.

`--exemplars 1` selects the median sprite. Using several noisy exemplars per
piece makes the matcher worse (39% vs 99% exact on the strong-augmented test
split), so the trainer defaults to one median template per piece.

Training on the clean reference screenshots reproduces the bundled accuracy
(99.0% exact / 99.97% squares on the 1200-image test split). Training on 1000
strongly augmented screenshots reaches 54.0% exact / 97.53% squares; the blur
and JPEG noise survive the median and make the learned templates less sharp
than the clean `color` set. The `color` templates therefore remain the runtime
default, while the trainer is useful for adding new board themes from clean
reference screenshots.

`tools/export_color_templates.py` regenerates the bundled `color` set from
`dataset/pieces`, and `tools/generate_templates.py` regenerates the built-in
silhouette templates. `dataset/internet_manifest.json` records the downloaded
image URLs, licences and attribution; the image files themselves are kept out
of git.

The downloader collected **1208 images** from Wikimedia Commons (public domain
and CC/GPL licences). Running the automatic detector over all of them found a
board with score >= 0.5 in **56 images (4.6%)**; most of the rest are chess
photos, people, or diagrams whose board does not fill the frame. Automatic
board detection is tuned for app screenshots, so the internet manifest is
useful for detector evaluation and for selecting clean reference boards, not
as a direct source of piece-level labels. The detector summary and the top
overlays are written to `.agent-work/logs/internet-benchmark.log` and
`.agent-work/outputs/internet_overlays/` when
`.agent-work/tests/InternetBenchmark.java` is run.

## Project layout

```text
src/main/java/com/chessai/
  Main.java                 CLI entry point
  engine/                   board, move generation, search, evaluation
  vision/                   board detection, homography, piece classifier
  autoplay/                 turn-text parsing, orientation and move planning
  sim/                      Swing simulator and self-play runner
android/                        Android accessibility floating chess bot
src/main/resources/templates/  2D and 3D piece templates
src/test/java/                  JUnit 5 regression tests
tools/generate_templates.py     template regeneration helper
tools/download_chess_images.py  Wikimedia Commons image downloader
tools/download_hf_chess_datasets.py Hugging Face board/position downloader
tools/build_vision_dataset.py   labelled screenshot generator
tools/train_vision_model.py     per-theme template trainer
tools/merge_template_sets.py    merge trained themes into the runtime set
tools/export_color_templates.py export sprites as runtime colour templates
tools/requirements-vision.txt   optional Python tooling dependencies
dataset/                        reference manifests, labels and piece sprites
dataset/external/               labelled Lichess images and real FEN positions
screenshots/                    reference screenshots
```
