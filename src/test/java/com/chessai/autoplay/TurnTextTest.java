package com.chessai.autoplay;

import com.chessai.engine.Piece;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnTextTest {
    @Test
    void parsesScreenshotStyleIndicator() {
        TurnText.TurnInfo info = TurnText.parse("1.White's Move");
        assertEquals(Piece.WHITE, info.side);
        assertEquals(1, info.fullmove);
        assertFalse(info.isGameOver());

        info = TurnText.parse("17. Black's Move");
        assertEquals(Piece.BLACK, info.side);
        assertEquals(17, info.fullmove);
    }

    @Test
    void parsesAlternativePhrases() {
        assertEquals(Piece.BLACK, TurnText.parse("Black to move").side);
        assertEquals(Piece.WHITE, TurnText.parse("White's turn").side);
    }

    @Test
    void ignoresOlderMoveHistory() {
        TurnText.TurnInfo info = TurnText.parse("1. White's Move 1. Black's Move 2. White's Move");
        assertEquals(Piece.WHITE, info.side);
        assertEquals(2, info.fullmove);
    }


    @Test
    void combinesAccessibilityAndScreenReadings() {
        TurnText.TurnInfo accessible = TurnText.parse("1.Black's Move");
        TurnText.TurnInfo screen = TurnText.parse("2.White's Move");
        TurnText.TurnInfo combined = TurnText.combine(accessible, screen);
        assertEquals(Piece.WHITE, combined.side);
        assertEquals(2, combined.fullmove);

        combined = TurnText.combine(accessible, TurnText.parse("no move text"));
        assertEquals(Piece.BLACK, combined.side);
        assertEquals(1, combined.fullmove);

        combined = TurnText.combine(TurnText.parse("White wins"), screen);
        assertTrue(combined.isGameOver());

        assertTrue(TurnText.looksLikeTurnText("2.White's Move"));
        assertFalse(TurnText.looksLikeTurnText("8 7 6 5 4 3 2 1"));
    }

    @Test
    void recognisesResults() {
        assertTrue(TurnText.parse("White wins").isGameOver());
        assertTrue(TurnText.parse("0-1").isGameOver());
        assertTrue(TurnText.parse("Stalemate").isGameOver());
        assertEquals("White wins", TurnText.parse("Black is checkmated").result);
    }

    @Test
    void menuButtonsDoNotEndTheGame() {
        assertFalse(TurnText.parse("Draw offer  Resign  Hint  Settings").isGameOver());
        assertFalse(TurnText.parse("Undo  Restart").isGameOver());
    }
}
