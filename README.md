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

The classifier works best on the flat/vector-style board themes included in
`screenshots/`. The primary 2D reference screenshot is recognized exactly as:

```text
rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1
```

Rendered/3D themes (perspective pieces with shadows) are located geometrically,
but the bundled classifier treats piece identification as best-effort. The
built-in 2D/3D templates live in `src/main/resources/templates/`. Optional
learned per-theme templates are loaded when
`-Dchessai.classifier.learned=true` is set (they are off by default because
some generated theme sets are noisier than the built-in silhouettes). Templates
can be regenerated with:

```bash
python3 tools/generate_templates.py
```

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
screenshots/                    reference screenshots
```
