package com.chessai.sim;

import com.chessai.engine.Evaluation;
import com.chessai.engine.Move;
import com.chessai.engine.MoveList;
import com.chessai.engine.Piece;
import com.chessai.engine.Position;
import com.chessai.engine.Search;
import com.chessai.engine.SearchResult;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A Swing board that mirrors the look of the supplied screenshots and lets the
 * engine play itself or suggest/play a move.  The simulator shares the exact
 * rules and search used by the headless self-play runner.
 */
public final class SimulatorFrame extends JFrame {
    private final BoardPanel boardPanel = new BoardPanel();
    private final JLabel status = new JLabel("Ready");
    private final JTextField fenField = new JTextField(36);
    private final JTextArea log = new JTextArea(7, 36);
    private final Search search = new Search(32);
    private Position position = Position.startPosition();
    private int selectedSquare = -1;
    private int lastMove = Move.NONE;
    private int bestMove = Move.NONE;
    private boolean thinking;
    private boolean autoPlay;
    private Timer autoTimer;

    public SimulatorFrame() {
        super("Chess AI Simulator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        side.setBackground(new Color(32, 36, 44));
        side.setPreferredSize(new Dimension(330, 800));

        JLabel title = new JLabel("CHESS AI");
        title.setForeground(new Color(255, 205, 80));
        title.setFont(new Font("SansSerif", Font.BOLD, 26));
        side.add(title);
        side.add(Box.createVerticalStrut(12));

        side.add(button("New Game", this::newGame));
        side.add(Box.createVerticalStrut(6));
        side.add(button("Engine Move", this::engineMove));
        side.add(Box.createVerticalStrut(6));
        side.add(button("Hint", this::showHint));
        side.add(Box.createVerticalStrut(6));
        side.add(button("Auto Play", this::toggleAuto));
        side.add(Box.createVerticalStrut(6));
        side.add(button("Flip Board", this::flip));
        side.add(Box.createVerticalStrut(14));

        JLabel fenLabel = new JLabel("FEN");
        fenLabel.setForeground(Color.LIGHT_GRAY);
        side.add(fenLabel);
        fenField.setText(position.toFen());
        side.add(fenField);
        side.add(Box.createVerticalStrut(6));
        side.add(button("Load FEN", this::loadFen));
        side.add(Box.createVerticalStrut(14));

        status.setForeground(Color.WHITE);
        status.setFont(new Font("Monospaced", Font.PLAIN, 12));
        side.add(status);
        side.add(Box.createVerticalStrut(10));

        log.setEditable(false);
        log.setBackground(new Color(20, 23, 29));
        log.setForeground(new Color(180, 220, 180));
        log.setFont(new Font("Monospaced", Font.PLAIN, 12));
        side.add(new JScrollPane(log));
        side.add(Box.createVerticalGlue());

        boardPanel.setPreferredSize(new Dimension(760, 760));
        boardPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                boardClick(event.getX(), event.getY());
            }
        });

        add(boardPanel, BorderLayout.CENTER);
        add(side, BorderLayout.EAST);
        pack();
        setLocationRelativeTo(null);
        updateStatus();
    }

    private JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setAlignmentX(LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        button.setFocusPainted(false);
        button.addActionListener(event -> action.run());
        return button;
    }

    private void newGame() {
        stopAuto();
        position = Position.startPosition();
        selectedSquare = -1;
        lastMove = Move.NONE;
        bestMove = Move.NONE;
        search.clear();
        fenField.setText(position.toFen());
        log.setText("");
        boardPanel.repaint();
        updateStatus();
    }

    public void setPosition(String fen) {
        position = Position.fromFen(fen);
        selectedSquare = -1;
        lastMove = Move.NONE;
        bestMove = Move.NONE;
        search.clear();
        fenField.setText(position.toFen());
        boardPanel.repaint();
        updateStatus();
    }

    private void loadFen() {
        try {
            position = Position.fromFen(fenField.getText().trim());
            selectedSquare = -1;
            lastMove = Move.NONE;
            bestMove = Move.NONE;
            search.clear();
            boardPanel.repaint();
            updateStatus();
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Invalid FEN: " + ex.getMessage());
        }
    }

    private void flip() {
        boardPanel.flipped = !boardPanel.flipped;
        boardPanel.repaint();
    }

    private void toggleAuto() {
        if (autoPlay) {
            stopAuto();
        } else {
            autoPlay = true;
            autoTimer = new Timer(350, event -> {
                if (!thinking) {
                    engineMove();
                }
            });
            autoTimer.start();
        }
    }

    private void stopAuto() {
        autoPlay = false;
        if (autoTimer != null) {
            autoTimer.stop();
            autoTimer = null;
        }
    }

    private void showHint() {
        startSearch(false);
    }

    private void engineMove() {
        startSearch(true);
    }

    private void startSearch(boolean apply) {
        if (thinking || gameFinished()) {
            return;
        }
        thinking = true;
        status.setText("Thinking...");
        Position snapshot = new Position(position);
        SwingWorker<SearchResult, Void> worker = new SwingWorker<>() {
            @Override
            protected SearchResult doInBackground() {
                return search.search(snapshot, 64, 600, false);
            }

            @Override
            protected void done() {
                thinking = false;
                try {
                    SearchResult result = get();
                    bestMove = result.move;
                    if (bestMove != Move.NONE) {
                        log.append(String.format("best %s  eval %+d  depth %d  nodes %d  %d ms%n",
                                Move.toUci(bestMove), result.score, result.depth,
                                result.nodes, result.timeMillis));
                    }
                    if (apply && bestMove != Move.NONE) {
                        play(bestMove);
                        if (autoPlay && !gameFinished()) {
                            // The timer schedules the next move.
                        }
                    }
                    boardPanel.repaint();
                    updateStatus();
                } catch (Exception ex) {
                    status.setText("Search failed: " + ex.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void boardClick(int x, int y) {
        if (thinking) {
            return;
        }
        int size = Math.min(boardPanel.getWidth(), boardPanel.getHeight());
        int originX = (boardPanel.getWidth() - size) / 2 + BoardPanel.MARGIN;
        int originY = (boardPanel.getHeight() - size) / 2 + BoardPanel.MARGIN;
        int squareSize = (size - 2 * BoardPanel.MARGIN) / 8;
        int col = (x - originX) / squareSize;
        int row = (y - originY) / squareSize;
        if (col < 0 || col > 7 || row < 0 || row > 7 || x < originX || y < originY) {
            return;
        }
        int file = boardPanel.flipped ? 7 - col : col;
        int rank = boardPanel.flipped ? row : 7 - row;
        int square = rank * 8 + file;
        if (selectedSquare < 0) {
            int piece = position.pieceAt(square);
            if (piece != Piece.EMPTY && Piece.color(piece) == position.getSide()) {
                selectedSquare = square;
            }
        } else {
            int move = position.findLegalMove(selectedSquare, square, Piece.QUEEN);
            if (move == Move.NONE) {
                move = position.findLegalMove(selectedSquare, square, 0);
            }
            if (move != Move.NONE) {
                play(move);
                selectedSquare = -1;
            } else {
                int piece = position.pieceAt(square);
                selectedSquare = piece != Piece.EMPTY
                        && Piece.color(piece) == position.getSide() ? square : -1;
            }
        }
        boardPanel.repaint();
    }

    private void play(int move) {
        position.makeMove(move);
        lastMove = move;
        bestMove = Move.NONE;
        fenField.setText(position.toFen());
        boardPanel.repaint();
        updateStatus();
    }

    private boolean gameFinished() {
        MoveList legal = new MoveList(256);
        position.generateLegalMoves(legal);
        return legal.size() == 0 || position.isFiftyMoveDraw() || position.isInsufficientMaterial();
    }

    private void updateStatus() {
        MoveList legal = new MoveList(256);
        position.generateLegalMoves(legal);
        String state;
        if (legal.size() == 0) {
            state = position.inCheck(position.getSide()) ? "Checkmate" : "Stalemate";
        } else if (position.isFiftyMoveDraw()) {
            state = "Draw (50-move rule)";
        } else if (position.isInsufficientMaterial()) {
            state = "Draw (insufficient material)";
        } else {
            state = position.getSide() == Piece.WHITE ? "White to move" : "Black to move";
        }
        int eval = Evaluation.evaluate(position);
        status.setText(String.format("<html>%s<br>eval %+d cp<br>%s</html>",
                state, eval, position.toFen()));
        fenField.setText(position.toFen());
        boardPanel.repaint();
    }

    private final class BoardPanel extends JPanel {
        static final int MARGIN = 42;
        boolean flipped;

        BoardPanel() {
            setBackground(new Color(46, 38, 30));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int size = Math.min(getWidth(), getHeight());
            int originX = (getWidth() - size) / 2 + MARGIN;
            int originY = (getHeight() - size) / 2 + MARGIN;
            int squareSize = (size - 2 * MARGIN) / 8;
            int boardPixels = squareSize * 8;

            g.setColor(new Color(120, 82, 50));
            g.fillRoundRect(originX - 18, originY - 18, boardPixels + 36, boardPixels + 36, 12, 12);
            g.setColor(new Color(240, 222, 188));
            g.fillRoundRect(originX - 6, originY - 6, boardPixels + 12, boardPixels + 12, 6, 6);

            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 8; col++) {
                    int file = flipped ? 7 - col : col;
                    int rank = flipped ? row : 7 - row;
                    int square = rank * 8 + file;
                    boolean light = ((file + rank) & 1) == 1;
                    g.setColor(light ? new Color(238, 221, 183) : new Color(122, 82, 50));
                    g.fillRect(originX + col * squareSize, originY + row * squareSize,
                            squareSize, squareSize);

                    if (lastMove != Move.NONE
                            && (Move.from(lastMove) == square || Move.to(lastMove) == square)) {
                        g.setColor(new Color(255, 220, 70, 120));
                        g.fillRect(originX + col * squareSize, originY + row * squareSize,
                                squareSize, squareSize);
                    }
                    if (selectedSquare == square) {
                        g.setColor(new Color(70, 180, 255, 150));
                        g.fillRect(originX + col * squareSize, originY + row * squareSize,
                                squareSize, squareSize);
                    }
                }
            }

            if (bestMove != Move.NONE) {
                drawMove(BoardPanel.this, g, originX, originY, squareSize, bestMove, false);
            }
            if (lastMove != Move.NONE) {
                drawMove(BoardPanel.this, g, originX, originY, squareSize, lastMove, true);
            }

            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 8; col++) {
                    int file = flipped ? 7 - col : col;
                    int rank = flipped ? row : 7 - row;
                    int square = rank * 8 + file;
                    int piece = position.pieceAt(square);
                    if (piece == Piece.EMPTY) {
                        continue;
                    }
                    String glyph = glyph(piece);
                    int fontSize = (int) (squareSize * 0.78);
                    g.setFont(new Font("SansSerif", Font.PLAIN, fontSize));
                    int textX = originX + col * squareSize + squareSize / 2;
                    int textY = originY + row * squareSize + (int) (squareSize * 0.78);
                    int metricsWidth = g.getFontMetrics().stringWidth(glyph);
                    textX -= metricsWidth / 2;
                    g.setColor(new Color(0, 0, 0, 90));
                    g.drawString(glyph, textX + 2, textY + 2);
                    g.setColor(Piece.isWhite(piece) ? Color.WHITE : Color.BLACK);
                    g.drawString(glyph, textX, textY);
                }
            }

            g.setFont(new Font("SansSerif", Font.BOLD, Math.max(11, squareSize / 5)));
            g.setColor(new Color(235, 225, 205));
            for (int i = 0; i < 8; i++) {
                int file = flipped ? 7 - i : i;
                String fileLabel = String.valueOf((char) ('A' + file));
                int x = originX + i * squareSize + squareSize / 2
                        - g.getFontMetrics().stringWidth(fileLabel) / 2;
                g.drawString(fileLabel, x, originY + boardPixels + 28);
                int rank = flipped ? i : 7 - i;
                String rankLabel = String.valueOf(rank + 1);
                g.drawString(rankLabel, originX - 30, originY + i * squareSize + squareSize / 2 + 5);
            }
        }

        private void drawMove(java.awt.Component component, Graphics2D g, int originX, int originY,
                              int squareSize, int move, boolean last) {
            int from = Move.from(move);
            int to = Move.to(move);
            int fromFile = from & 7;
            int fromRank = from >>> 3;
            int toFile = to & 7;
            int toRank = to >>> 3;
            int fromCol = flipped ? 7 - fromFile : fromFile;
            int toCol = flipped ? 7 - toFile : toFile;
            int fromRow = flipped ? fromRank : 7 - fromRank;
            int toRow = flipped ? toRank : 7 - toRank;
            int x1 = originX + fromCol * squareSize + squareSize / 2;
            int y1 = originY + fromRow * squareSize + squareSize / 2;
            int x2 = originX + toCol * squareSize + squareSize / 2;
            int y2 = originY + toRow * squareSize + squareSize / 2;
            g.setColor(last ? new Color(40, 180, 90, 180) : new Color(40, 120, 255, 220));
            g.setStroke(new java.awt.BasicStroke(Math.max(4f, squareSize / 12f),
                    java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            g.drawLine(x1, y1, x2, y2);
            int radius = Math.max(5, squareSize / 9);
            g.fillOval(x2 - radius, y2 - radius, radius * 2, radius * 2);
        }

        private String glyph(int piece) {
            int type = Piece.type(piece);
            boolean white = Piece.isWhite(piece);
            return switch (type) {
                case Piece.KING -> white ? "\u2654" : "\u265A";
                case Piece.QUEEN -> white ? "\u2655" : "\u265B";
                case Piece.ROOK -> white ? "\u2656" : "\u265C";
                case Piece.BISHOP -> white ? "\u2657" : "\u265D";
                case Piece.KNIGHT -> white ? "\u2658" : "\u265E";
                case Piece.PAWN -> white ? "\u2659" : "\u265F";
                default -> "?";
            };
        }
    }
}
