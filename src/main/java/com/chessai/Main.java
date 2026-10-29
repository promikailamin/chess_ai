package com.chessai;

import com.chessai.engine.Move;
import com.chessai.engine.Perft;
import com.chessai.engine.Position;
import com.chessai.engine.Search;
import com.chessai.engine.SearchResult;
import com.chessai.sim.SelfPlay;
import com.chessai.sim.SimulatorFrame;
import com.chessai.vision.Recognition;
import com.chessai.vision.ScreenshotAnalyzer;

import javax.swing.SwingUtilities;
import java.io.File;

/** Command-line entry point. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        String command = args[0];
        try {
            switch (command) {
                case "analyze" -> analyze(args);
                case "selfplay" -> selfplay(args);
                case "gui" -> gui(args);
                case "perft" -> perft(args);
                case "bench" -> bench();
                case "help", "--help", "-h" -> usage();
                default -> {
                    System.err.println("Unknown command: " + command);
                    usage();
                }
            }
        } catch (Exception ex) {
            System.err.println("Error: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("""
                Chess AI - screenshot recognition + Java chess engine

                Usage:
                  analyze <screenshot> [--depth N] [--time MS] [--fen FEN] [--no-overlay]
                  selfplay [--games N] [--depth N] [--time MS] [--max-plies N] [--random-openings] [--quiet]
                  gui [--fen FEN]
                  perft [depth] [FEN]
                  bench

                Examples:
                  analyze screenshots/Screenshot_2026-10-29-20-02-09-026_com.llx.chess.jpg
                  selfplay --games 1000 --depth 3 --time 10
                  gui
                """);
    }

    private static void analyze(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("analyze needs an image path");
            return;
        }
        File file = new File(args[1]);
        if (!file.isFile()) {
            System.err.println("No such image: " + file);
            return;
        }
        int depth = 64;
        long time = 2000;
        String fenOverride = null;
        boolean overlay = true;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--depth" -> depth = Integer.parseInt(args[++i]);
                case "--time" -> time = Long.parseLong(args[++i]);
                case "--fen" -> fenOverride = args[++i];
                case "--no-overlay" -> overlay = false;
                default -> {
                }
            }
        }

        System.out.println("Image       : " + file.getAbsolutePath());
        Recognition recognition = ScreenshotAnalyzer.analyze(file);
        System.out.println("Board       : " + recognition.quad.describe()
                + "  via " + recognition.method
                + String.format("  (checkerboard %.3f)", recognition.boardScore));
        System.out.printf("Confidence  : %.3f%n", recognition.confidence);
        System.out.println("Recognized board:");
        System.out.println(boardAscii(recognition.squares));

        Position position = fenOverride == null
                ? recognition.position
                : Position.fromFen(fenOverride);
        if (fenOverride != null) {
            System.out.println("Using FEN override.");
        }
        System.out.println("FEN         : " + position.toFen());

        Search search = new Search(64);
        SearchResult result = search.search(position, depth, time);
        if (result.move == Move.NONE) {
            System.out.println("Best move   : (none - position is already finished)");
        } else {
            System.out.println("Best move   : " + Move.toUci(result.move));
            System.out.printf("Search      : score %+d  depth %d  nodes %d  time %d ms%n",
                    result.score, result.depth, result.nodes, result.timeMillis);
            if (Math.abs(result.score) > Search.MATE_THRESHOLD) {
                System.out.println("              (forced mate detected)");
            }
        }

        if (overlay) {
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            File target = new File(file.getParentFile(), base + "_analysis.png");
            ScreenshotAnalyzer.saveOverlay(file, target, recognition, result.move);
            System.out.println("Overlay     : " + target.getAbsolutePath());
        }
    }

    private static String boardAscii(char[][] squares) {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < 8; row++) {
            sb.append(8 - row).append("  ");
            for (int col = 0; col < 8; col++) {
                sb.append(squares[row][col]).append(' ');
            }
            sb.append('\n');
        }
        sb.append("\n   a b c d e f g h\n");
        return sb.toString();
    }

    private static void selfplay(String[] args) {
        int games = 1000;
        int depth = 3;
        long time = 10;
        int maxPlies = 400;
        boolean random = true;
        boolean quiet = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--games" -> games = Integer.parseInt(args[++i]);
                case "--depth" -> depth = Integer.parseInt(args[++i]);
                case "--time" -> time = Long.parseLong(args[++i]);
                case "--max-plies" -> maxPlies = Integer.parseInt(args[++i]);
                case "--random-openings" -> random = true;
                case "--no-random-openings" -> random = false;
                case "--quiet" -> quiet = true;
                default -> {
                }
            }
        }
        System.out.printf("Self-play: %d games, depth %d, %d ms/move, max %d plies%n",
                games, depth, time, maxPlies);
        SelfPlay.Stats stats = SelfPlay.run(games, depth, time, maxPlies, random, !quiet);
        stats.print();
    }

    private static void gui(String[] args) {
        String fen = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--fen") && i + 1 < args.length) {
                fen = args[++i];
            }
        }
        String finalFen = fen;
        SwingUtilities.invokeLater(() -> {
            SimulatorFrame frame = new SimulatorFrame();
            if (finalFen != null) {
                frame.setPosition(finalFen);
            }
            frame.setVisible(true);
        });
    }

    private static void perft(String[] args) {
        int depth = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        Position position = args.length > 2
                ? Position.fromFen(String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)))
                : Position.startPosition();
        long start = System.currentTimeMillis();
        long nodes = Perft.count(position, depth);
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("perft(%d) = %d  %d ms  %d nps%n", depth, nodes, elapsed,
                elapsed == 0 ? nodes : nodes * 1000 / elapsed);
    }

    private static void bench() {
        Position position = Position.startPosition();
        long start = System.currentTimeMillis();
        long nodes = Perft.count(position, 4);
        long perftTime = System.currentTimeMillis() - start;
        System.out.printf("perft(4) = %d in %d ms%n", nodes, perftTime);
        if (nodes != 197281) {
            System.out.println("WARNING: perft(4) does not match 197281");
        }
        Search search = new Search(64);
        SearchResult result = search.search(position, 64, 3000);
        System.out.printf("search: %s score %+d depth %d nodes %d time %d ms%n",
                Move.toUci(result.move), result.score, result.depth,
                result.nodes, result.timeMillis);
    }
}
