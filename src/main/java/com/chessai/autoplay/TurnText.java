package com.chessai.autoplay;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the move indicator shown by chess apps, for example "1.White's Move"
 * or "12. Black to move", and recognises common game-over messages.
 */
public final class TurnText {
    public static final int NO_SIDE = -1;

    private static final Pattern NUMBERED = Pattern.compile(
            "(?i)(\\d+)\\s*[.)]?\\s*(white|black)\\s*['\u2019]?s?\\s*(?:move|turn)");
    private static final Pattern SIDE_MOVE = Pattern.compile(
            "(?i)(?<![a-z])(white|black)\\s*['\u2019]?s?\\s*(?:move|turn)");
    private static final Pattern TO_MOVE = Pattern.compile(
            "(?i)(?<![a-z])(white|black)\\s+to\\s+(?:move|play)");
    private static final Pattern WHITE_RESULT = Pattern.compile(
            "(?i)\\b(?:1-0|white\\s+wins?|white\\s+won|black\\s+(?:is\\s+)?checkmated)\\b");
    private static final Pattern BLACK_RESULT = Pattern.compile(
            "(?i)\\b(?:0-1|black\\s+wins?|black\\s+won|white\\s+(?:is\\s+)?checkmated)\\b");
    private static final Pattern DRAW_RESULT = Pattern.compile(
            "(?i)\\b(?:game\\s+drawn?|draw\\s+(?:agreed|by\\s+agreement)|stalemate"
                    + "|1/2\\s*[-\\u2013]\\s*1/2|\u00bd\\s*[-\\u2013]\\s*\u00bd"
                    + "|insufficient\\s+material)\\b");
    private static final Pattern CHECKMATE = Pattern.compile("(?i)\\bcheck\\s*mate\\b");
    private static final Pattern GENERIC_END = Pattern.compile(
            "(?i)\\b(?:time(?:d)?\\s*out|game\\s+over|abandon(?:ed)?)\\b");

    private TurnText() {
    }

    public static final class TurnInfo {
        public final int side;
        public final int fullmove;
        public final String result;
        public final String raw;

        public TurnInfo(int side, int fullmove, String result, String raw) {
            this.side = side;
            this.fullmove = Math.max(1, fullmove);
            this.result = result;
            this.raw = raw == null ? "" : raw;
        }

        public boolean isGameOver() {
            return result != null && !result.isEmpty();
        }
    }

    public static TurnInfo combine(TurnInfo accessible, TurnInfo screen) {
        if (accessible == null) {
            return screen;
        }
        if (screen == null) {
            return accessible;
        }
        if (accessible.isGameOver() && !screen.isGameOver()) {
            return accessible;
        }
        if (screen.isGameOver()) {
            return screen;
        }
        boolean accessibleValid = accessible.side != NO_SIDE;
        boolean screenValid = screen.side != NO_SIDE;
        if (!accessibleValid) {
            return screen;
        }
        if (!screenValid) {
            return accessible;
        }
        if (screen.fullmove != accessible.fullmove) {
            return screen.fullmove > accessible.fullmove ? screen : accessible;
        }
        return screen;
    }

    public static boolean looksLikeTurnText(String text) {
        TurnInfo info = parse(text);
        return info.side != NO_SIDE || info.isGameOver();
    }

    public static TurnInfo parse(String text) {
        if (text == null) {
            text = "";
        }
        String normalized = text.replace('\u2019', '\'').replace('\u2018', '\'');
        String result = findResult(normalized);
        if (result != null) {
            return new TurnInfo(NO_SIDE, 1, result, normalized.trim());
        }

        Candidate best = new Candidate();
        scan(NUMBERED, normalized, true, best);
        if (!best.found) {
            scan(SIDE_MOVE, normalized, false, best);
        }
        if (!best.found) {
            scan(TO_MOVE, normalized, false, best);
        }

        return new TurnInfo(best.found ? best.side : NO_SIDE, best.number,
                null, normalized.trim());
    }

    private static final class Candidate {
        boolean found;
        int side = NO_SIDE;
        int number = 1;
    }

    private static void scan(Pattern pattern, String text, boolean numbered, Candidate best) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int number = numbered ? parseInt(matcher.group(1)) : 1;
            if (best.found && number < best.number) {
                continue;
            }
            best.found = true;
            best.side = sideOf(matcher.group(numbered ? 2 : 1));
            best.number = number;
        }
    }

    private static String findResult(String text) {
        if (DRAW_RESULT.matcher(text).find()) {
            return "Draw";
        }
        if (WHITE_RESULT.matcher(text).find()) {
            return "White wins";
        }
        if (BLACK_RESULT.matcher(text).find()) {
            return "Black wins";
        }
        if (CHECKMATE.matcher(text).find()) {
            return "Checkmate";
        }
        if (GENERIC_END.matcher(text).find()) {
            return "Game over";
        }
        return null;
    }

    private static int sideOf(String value) {
        if (value == null) {
            return NO_SIDE;
        }
        if (value.equalsIgnoreCase("white")) {
            return com.chessai.engine.Piece.WHITE;
        }
        if (value.equalsIgnoreCase("black")) {
            return com.chessai.engine.Piece.BLACK;
        }
        return NO_SIDE;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 1;
        }
    }
}
