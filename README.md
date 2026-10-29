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
- Python 3.10+ with `numpy`, `opencv-python-headless` and `pillow` only if you
  want to regenerate the bundled piece templates.

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

## Recognition notes

The classifier works best on the flat/vector-style board themes included in
`screenshots/`. The primary 2D reference screenshot is recognized exactly as:

```text
rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1
```

Rendered/3D themes (perspective pieces with shadows) are located geometrically,
but the bundled classifier treats piece identification as best-effort. The
templates live in `src/main/resources/templates/` and can be extended or
regenerated with:

```bash
python3 tools/generate_templates.py
```

## Project layout

```text
src/main/java/com/chessai/
  Main.java                 CLI entry point
  engine/                   board, move generation, search, evaluation
  vision/                   board detection, homography, piece classifier
  sim/                      Swing simulator and self-play runner
src/main/resources/templates/  2D and 3D piece templates
src/test/java/                  JUnit 5 regression tests
tools/generate_templates.py     template regeneration helper
screenshots/                    reference screenshots
```
