package com.chessai.autoplay;

import com.chessai.engine.Piece;
import com.chessai.engine.Position;
import com.chessai.vision.Recognition;
import com.chessai.vision.ScreenshotAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenshotAutoplayTest {
    @Test
    void referenceScreenshotDrivesBotMove() throws Exception {
        File file = new File("screenshots/Screenshot_2026-10-29-20-02-09-026_com.llx.chess.jpg");
        Recognition recognition = ScreenshotAnalyzer.analyze(file);
        assertEquals(Position.startPosition().toFen(), recognition.position.toFen());
        assertFalse(OrientationDetector.isFlipped(recognition.position));

        TurnText.TurnInfo turn = TurnText.parse("1.White's Move");
        AutoPlaySession session = new AutoPlaySession(Piece.WHITE, 4, 3, 200);
        AutoPlaySession.Decision first = session.decide(turn, recognition.position, false);
        assertEquals(AutoPlaySession.Kind.WAIT, first.kind);
        AutoPlaySession.Decision second = session.decide(turn, recognition.position, false);
        assertEquals(AutoPlaySession.Kind.PLAY, second.kind);
        assertTrue(Position.startPosition().isLegal(second.move));
    }
}
