package com.chessai.vision;

import com.chessai.engine.Position;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalVisionDatasetTest {
    private static String placement(Position position) {
        return position.toFen().split(" ")[0];
    }

    @Test
    void recognizesLichessBoardWithBlackToMove() throws Exception {
        File file = new File("dataset/external/lichess_boards/images/lichess-boards_test_7.jpg");
        assertTrue(file.isFile(), "external dataset image should be present");
        Recognition recognition = ScreenshotAnalyzer.analyze(file);
        Position expected = Position.fromFen(
                "8/p4pbk/bq4p1/1NN4p/PQ2P3/5P2/6PP/5K2 b - - 0 1");
        assertEquals(placement(expected), placement(recognition.position));
    }

    @Test
    void recognizesBusyLichessBoardUsingColourOutline() throws Exception {
        File file = new File("dataset/external/lichess_boards/images/lichess-boards_test_28.jpg");
        assertTrue(file.isFile(), "external dataset image should be present");
        Recognition recognition = ScreenshotAnalyzer.analyze(file);
        Position expected = Position.fromFen(
                "6k1/1n1q1pBp/p1Np2p1/1p1P3n/1Pr3P1/3Q3P/P3RPK1/R7 b - - 0 1");
        assertEquals(placement(expected), placement(recognition.position));
    }
}
