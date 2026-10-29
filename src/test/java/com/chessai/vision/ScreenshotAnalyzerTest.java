package com.chessai.vision;

import com.chessai.engine.Position;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenshotAnalyzerTest {
    @Test
    void recognizesInitialPositionFromFlatScreenshot() throws Exception {
        File file = new File("screenshots/Screenshot_2026-10-29-20-02-09-026_com.llx.chess.jpg");
        assertTrue(file.isFile(), "reference screenshot should be present");
        Recognition recognition = ScreenshotAnalyzer.analyze(file);
        assertEquals(Position.startPosition().toFen(), recognition.position.toFen());
        assertTrue(recognition.confidence > 0.7);
    }
}
