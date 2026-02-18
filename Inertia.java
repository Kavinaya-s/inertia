import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

 class Inertia extends JFrame {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Inertia().setVisible(true));
    }

    // ===== Model =====
    enum Cell { EMPTY, WALL, MINE, GEM, STOP, BLOCK }
    enum Turn { HUMAN, WAITING, COMPUTER, SOLVING }
    enum Quadrant { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, ALL }

    static final class Vec {
        final int r, c;
        Vec(int r, int c) { this.r = r; this.c = c; }
        Vec add(Vec o) { return new Vec(r + o.r, c + o.c); }
        @Override public boolean equals(Object o) { return (o instanceof Vec) && ((Vec)o).r == r && ((Vec)o).c == c; }
        @Override public int hashCode() { return Objects.hash(r, c); }
        @Override public String toString() { return "(" + r + "," + c + ")"; }
    }

    // 8 directions
    static final Vec[] DIRS = {
        new Vec(-1, 0),  // N
        new Vec(1, 0),   // S
        new Vec(0, -1),  // W
        new Vec(0, 1),   // E
        new Vec(-1, -1), // NW
        new Vec(-1, 1),  // NE
        new Vec(1, -1),  // SW
        new Vec(1, 1)    // SE
    };

    static final class Grid {
        final int rows, cols;
        final Cell[][] cells;
        Grid(Cell[][] cells) {
            this.rows = cells.length;
            this.cols = cells[0].length;
            this.cells = cells;
        }
        boolean inBounds(Vec v) { return v.r >= 0 && v.r < rows && v.c >= 0 && v.c < cols; }
        Cell get(Vec v) { return cells[v.r][v.c]; }
        boolean isWall(Vec v) { Cell c = get(v); return c == Cell.WALL || c == Cell.BLOCK; }
    }

    static final class Snapshot {
        final Vec ball;
        final boolean[][] gemPresent;
        final int gemsCollected;
        final int deaths;
        final Quadrant currentQuadrant;
        Snapshot(Vec ball, boolean[][] gemPresent, int gemsCollected, int deaths, Quadrant currentQuadrant) {
            this.ball = new Vec(ball.r, ball.c);
            this.gemPresent = deepCopy(gemPresent);
            this.gemsCollected = gemsCollected;
            this.deaths = deaths;
            this.currentQuadrant = currentQuadrant;
        }
        static boolean[][] deepCopy(boolean[][] src) {
            boolean[][] out = new boolean[src.length][src[0].length];
            for (int i = 0; i < src.length; i++) System.arraycopy(src[i], 0, out[i], 0, src[i].length);
            return out;
        }
    }
    
    private void reviveGameIfNeeded() {
        if (gameOver) {
            gameOver = false;
            showExplosion = false;
            explosionCenter = null;
        }
        if (state.allGemsCollected()) {
            gameOver = true;
        }
    }

    static final class GameState {
        final Grid grid;
        Vec ball;                 // single shared pawn
        boolean[][] gemPresent;
        int totalGems;
        int gemsCollected;
        int deaths;
        Quadrant currentQuadrant = Quadrant.ALL;
        int[] quadrantGems = new int[4]; // gems per quadrant
        boolean[] quadrantCompleted = new boolean[4];

        final Deque<Snapshot> undo = new ArrayDeque<>();
        final Deque<Snapshot> redo = new ArrayDeque<>();

        GameState(Grid grid, Vec start, boolean[][] gemPresent, int totalGems) {
            this.grid = grid;
            this.ball = start;
            this.gemPresent = Snapshot.deepCopy(gemPresent);
            this.totalGems = totalGems;
            this.gemsCollected = 0;
            this.deaths = 0;
            calculateQuadrantGems();
        }

        void calculateQuadrantGems() {
            Arrays.fill(quadrantGems, 0);
            int midR = grid.rows / 2;
            int midC = grid.cols / 2;
            
            for (int r = 0; r < grid.rows; r++) {
                for (int c = 0; c < grid.cols; c++) {
                    if (gemPresent[r][c]) {
                        if (r < midR && c < midC) quadrantGems[0]++; // TOP_LEFT
                        else if (r < midR && c >= midC) quadrantGems[1]++; // TOP_RIGHT
                        else if (r >= midR && c < midC) quadrantGems[2]++; // BOTTOM_LEFT
                        else quadrantGems[3]++; // BOTTOM_RIGHT
                    }
                }
            }
        }

        Quadrant getQuadrant(Vec pos) {
            int midR = grid.rows / 2;
            int midC = grid.cols / 2;
            
            if (pos.r < midR && pos.c < midC) return Quadrant.TOP_LEFT;
            else if (pos.r < midR && pos.c >= midC) return Quadrant.TOP_RIGHT;
            else if (pos.r >= midR && pos.c < midC) return Quadrant.BOTTOM_LEFT;
            else return Quadrant.BOTTOM_RIGHT;
        }

        boolean isInCurrentQuadrant(Vec pos) {
            if (currentQuadrant == Quadrant.ALL) return true;
            return getQuadrant(pos) == currentQuadrant;
        }

        void pushUndo() {
            undo.push(new Snapshot(ball, gemPresent, gemsCollected, deaths, currentQuadrant));
            redo.clear();
        }

        boolean undo() {
            if (undo.isEmpty()) return false;
            redo.push(new Snapshot(ball, gemPresent, gemsCollected, deaths, currentQuadrant));
            Snapshot s = undo.pop();
            restore(s);
            return true;
        }

        boolean redo() {
            if (redo.isEmpty()) return false;
            undo.push(new Snapshot(ball, gemPresent, gemsCollected, deaths, currentQuadrant));
            Snapshot s = redo.pop();
            restore(s);
            return true;
        }

        void restore(Snapshot s) {
            this.ball = new Vec(s.ball.r, s.ball.c);
            this.gemPresent = Snapshot.deepCopy(s.gemPresent);
            this.gemsCollected = s.gemsCollected;
            this.deaths = s.deaths;
            this.currentQuadrant = s.currentQuadrant;
        }

        boolean allGemsCollected() { return gemsCollected >= totalGems; }
        int remainingGems() { return Math.max(0, totalGems - gemsCollected); }
        
        // Check if current quadrant is complete
        boolean isCurrentQuadrantComplete() {
            if (currentQuadrant == Quadrant.ALL) return false;
            int quadrantGemsRemaining = 0;
            int midR = grid.rows / 2;
            int midC = grid.cols / 2;
            
            for (int r = 0; r < grid.rows; r++) {
                for (int c = 0; c < grid.cols; c++) {
                    if (gemPresent[r][c]) {
                        Quadrant q = getQuadrant(new Vec(r, c));
                        if (q == currentQuadrant) quadrantGemsRemaining++;
                    }
                }
            }
            return quadrantGemsRemaining == 0;
        }
        
        // Move to next quadrant
        Quadrant getNextQuadrant() {
            if (currentQuadrant == Quadrant.ALL) return Quadrant.ALL;
            
            Quadrant[] order = {Quadrant.TOP_LEFT, Quadrant.TOP_RIGHT, 
                                Quadrant.BOTTOM_LEFT, Quadrant.BOTTOM_RIGHT};
            int currentIdx = -1;
            for (int i = 0; i < order.length; i++) {
                if (order[i] == currentQuadrant) {
                    currentIdx = i;
                    break;
                }
            }
            
            // Find next incomplete quadrant
            for (int i = 1; i <= 4; i++) {
                int nextIdx = (currentIdx + i) % 4;
                if (!isQuadrantComplete(order[nextIdx])) {
                    return order[nextIdx];
                }
            }
            return Quadrant.ALL;
        }
        
        boolean isQuadrantComplete(Quadrant q) {
            int midR = grid.rows / 2;
            int midC = grid.cols / 2;
            
            for (int r = 0; r < grid.rows; r++) {
                for (int c = 0; c < grid.cols; c++) {
                    if (gemPresent[r][c]) {
                        Quadrant gemQ = getQuadrant(new Vec(r, c));
                        if (gemQ == q) return false;
                    }
                }
            }
            return true;
        }
        
        // Get list of quadrants that still have gems
        List<Quadrant> getRemainingQuadrants() {
            List<Quadrant> remaining = new ArrayList<>();
            Quadrant[] all = {Quadrant.TOP_LEFT, Quadrant.TOP_RIGHT, 
                             Quadrant.BOTTOM_LEFT, Quadrant.BOTTOM_RIGHT};
            
            for (Quadrant q : all) {
                if (!isQuadrantComplete(q)) {
                    remaining.add(q);
                }
            }
            return remaining;
        }
    }

    // ===== UI & Controller =====
    final BoardPanel board;
    final JLabel status;
    final JComboBox<String> quadrantSelector;
    GameState state;
    Level currentLevel;

    volatile boolean showExplosion = false;
    Vec explosionCenter = null;
    javax.swing.Timer explosionTimer = null;
    javax.swing.Timer compMoveTimer = null;
    javax.swing.Timer solverTimer = null;
    boolean gameOver = false;
    private Turn turn = Turn.HUMAN;

    // AI memory to avoid oscillation patterns for shared ball
    private Vec lastEnd = null;
    private Vec prevEnd = null;
    private final Random rng = new Random();

    // For drawing: which outline to use on the single ball (last mover color)
    private Color lastMoverStroke = new Color(10, 120, 60);   
    private Color lastMoverFill = new Color(30, 180, 90);     

    // Solver state for divide & conquer
    private List<Quadrant> solverQuadrants = new ArrayList<>();
    private int currentSolverQuadrantIndex = 0;
    private List<Integer> currentQuadrantPlan = null;
    private Iterator<Integer> currentPlanIterator = null;

    public Inertia() {
        super("Inertia — one shared ball, 1s gap, greedy+sorting AI, BFS levels, solver, quadrants");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        this.currentLevel = Level.generateRandomLevel();
        this.state = currentLevel.toGameState();

        this.board = new BoardPanel(state);
        this.status = new JLabel();
        
        // Quadrant selector
        String[] quadrants = {"All Quadrants", "Top-Left", "Top-Right", "Bottom-Left", "Bottom-Right"};
        this.quadrantSelector = new JComboBox<>(quadrants);
        quadrantSelector.addActionListener(e -> {
            if (turn == Turn.HUMAN) {
                switch (quadrantSelector.getSelectedIndex()) {
                    case 0: state.currentQuadrant = Quadrant.ALL; break;
                    case 1: state.currentQuadrant = Quadrant.TOP_LEFT; break;
                    case 2: state.currentQuadrant = Quadrant.TOP_RIGHT; break;
                    case 3: state.currentQuadrant = Quadrant.BOTTOM_LEFT; break;
                    case 4: state.currentQuadrant = Quadrant.BOTTOM_RIGHT; break;
                }
                updateStatus();
                board.repaint();
            }
        });

        updateStatus();

        add(board, BorderLayout.CENTER);
        add(toolbar(), BorderLayout.NORTH);
        add(status, BorderLayout.SOUTH);

        setSize(720, 640);
        setLocationRelativeTo(null);
        setupKeyBindings();
    }

    private JToolBar toolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);

        JButton newGame = new JButton("New game");
        newGame.addActionListener(e -> {
            stopTimers();
            status.setText("Generating new game...");
            turn = Turn.WAITING;

            new SwingWorker<Level, Void>() {
                @Override
                protected Level doInBackground() {
                    return Level.generateRandomLevel();
                }

                @Override
                protected void done() {
                    try {
                        currentLevel = get();
                        state = currentLevel.toGameState();
                        gameOver = false;
                        showExplosion = false;
                        explosionCenter = null;
                        turn = Turn.HUMAN;
                        lastEnd = null;
                        prevEnd = null;
                        lastMoverFill = new Color(30, 180, 90);
                        lastMoverStroke = new Color(10, 120, 60);
                        quadrantSelector.setSelectedIndex(0);
                        board.setState(state);
                        updateStatus();
                        board.repaint();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        JOptionPane.showMessageDialog(Inertia.this,
                                "Failed to generate level", "Error",
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });

        JButton restart = new JButton("Restart");
        restart.addActionListener(e -> {
            if (this.currentLevel == null) return;
            stopTimers();
            state = this.currentLevel.toGameState();
            gameOver = false;
            showExplosion = false;
            explosionCenter = null;
            turn = Turn.HUMAN;
            lastEnd = null;
            prevEnd = null;
            lastMoverFill = new Color(30, 180, 90);
            lastMoverStroke = new Color(10, 120, 60);
            quadrantSelector.setSelectedIndex(0);
            board.setState(state);
            updateStatus();
            board.repaint();
        });

        JButton undo = new JButton("Undo");
        undo.addActionListener(e -> {
            if (turn == Turn.HUMAN && state.undo()) {
                reviveGameIfNeeded();
                updateStatus();
                board.repaint();
            }
        });

        JButton redo = new JButton("Redo");
        redo.addActionListener(e -> {
            if (turn == Turn.HUMAN && state.redo()) {
                reviveGameIfNeeded();
                updateStatus();
                board.repaint();
            }
        });

        JButton solve = new JButton("Solve game");
        solve.addActionListener(e -> {
            if (gameOver || turn == Turn.SOLVING) return;
            stopTimers();
            startDivideAndConquerSolver(); // Modified to use divide & conquer
        });

        JButton solveQuadrant = new JButton("Solve Quadrant");
        solveQuadrant.addActionListener(e -> {
            if (gameOver || turn == Turn.SOLVING || state.currentQuadrant == Quadrant.ALL) {
                JOptionPane.showMessageDialog(this, 
                    "Please select a specific quadrant to solve.", 
                    "Info", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            stopTimers();
            startQuadrantSolver();
        });

        tb.add(newGame);
        tb.add(restart);
        tb.add(undo);
        tb.add(redo);
        tb.add(quadrantSelector);
        tb.add(solveQuadrant);
        tb.add(solve);
        return tb;
    }

    private void stopTimers() {
        if (explosionTimer != null && explosionTimer.isRunning()) explosionTimer.stop();
        if (compMoveTimer != null && compMoveTimer.isRunning()) compMoveTimer.stop();
        if (solverTimer != null && solverTimer.isRunning()) solverTimer.stop();
        compMoveTimer = null;
        solverTimer = null;
    }

    private void setupKeyBindings() {
        InputMap im = board.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = board.getActionMap();

        bindDir(im, am, "UP", new Vec(-1, 0));
        bindDir(im, am, "DOWN", new Vec(1, 0));
        bindDir(im, am, "LEFT", new Vec(0, -1));
        bindDir(im, am, "RIGHT", new Vec(0, 1));
        bindDir(im, am, "NUMPAD8", new Vec(-1, 0));
        bindDir(im, am, "NUMPAD2", new Vec(1, 0));
        bindDir(im, am, "NUMPAD4", new Vec(0, -1));
        bindDir(im, am, "NUMPAD6", new Vec(0, 1));
        bindDir(im, am, "NUMPAD7", new Vec(-1, -1));
        bindDir(im, am, "NUMPAD9", new Vec(-1, 1));
        bindDir(im, am, "NUMPAD1", new Vec(1, -1));
        bindDir(im, am, "NUMPAD3", new Vec(1, 1));
    }

    private void bindDir(InputMap im, ActionMap am, String key, Vec dir) {
        im.put(KeyStroke.getKeyStroke(key), key);
        am.put(key, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (!gameOver && turn == Turn.HUMAN) handleHumanTurn(dir);
            }
        });
    }

    // ===== Alternation: human moves, then computer after 1s =====
    private void handleHumanTurn(Vec dir) {
        if (dir.r == 0 && dir.c == 0) return;
        if (turn != Turn.HUMAN || gameOver) return;

        // Check if move is valid in current quadrant
        Vec preview = previewSlide(state.ball, dir);
        if (!state.isInCurrentQuadrant(preview) && state.currentQuadrant != Quadrant.ALL) {
            JOptionPane.showMessageDialog(this, 
                "Move must stay within the current quadrant!", 
                "Invalid Move", JOptionPane.WARNING_MESSAGE);
            return;
        }

        turn = Turn.WAITING;
        state.pushUndo();

        boolean died = slideFrom(state.ball, dir, true, true);
        lastMoverFill = new Color(30, 180, 90);
        lastMoverStroke = new Color(10, 120, 60);

        if (died) {
            state.deaths++;
            explosionCenter = state.ball;
            triggerExplosionAnimation();
            if (state.deaths >= 3) {
                gameOver = true;
                updateStatus();
                board.repaint();
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, "Game over — 3 deaths reached. Click Restart or New game.", "Game Over", JOptionPane.INFORMATION_MESSAGE));
                turn = Turn.HUMAN;
                return;
            }
        }

        // Check if quadrant is complete
        if (state.currentQuadrant != Quadrant.ALL && state.isCurrentQuadrantComplete()) {
            Quadrant next = state.getNextQuadrant();
            if (next != state.currentQuadrant) {
                state.currentQuadrant = next;
                updateQuadrantSelector();
                JOptionPane.showMessageDialog(this, 
                    "Quadrant complete! Moving to " + next.toString().replace('_', ' ') + ".", 
                    "Quadrant Complete", JOptionPane.INFORMATION_MESSAGE);
            }
        }

        updateStatusWaiting();
        board.repaint();

        if (compMoveTimer != null && compMoveTimer.isRunning()) compMoveTimer.stop();
        compMoveTimer = new javax.swing.Timer(1000, ev -> {
            if (gameOver) { turn = Turn.HUMAN; return; }
            turn = Turn.COMPUTER;
            handleComputerMove();

            if (!gameOver && state.allGemsCollected()) {
                gameOver = true;
                updateStatus();
                board.repaint();
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, "Congratulations — together you collected all the gems!", "You win", JOptionPane.INFORMATION_MESSAGE));
                turn = Turn.HUMAN;
                return;
            }

            updateStatus();
            board.repaint();
            if (!gameOver) turn = Turn.HUMAN;
        });
        compMoveTimer.setRepeats(false);
        compMoveTimer.start();
    }

    private void updateQuadrantSelector() {
        switch (state.currentQuadrant) {
            case TOP_LEFT: quadrantSelector.setSelectedIndex(1); break;
            case TOP_RIGHT: quadrantSelector.setSelectedIndex(2); break;
            case BOTTOM_LEFT: quadrantSelector.setSelectedIndex(3); break;
            case BOTTOM_RIGHT: quadrantSelector.setSelectedIndex(4); break;
            default: quadrantSelector.setSelectedIndex(0); break;
        }
    }

    // ===== Computer AI (sorting + greedy) on the shared ball =====
    private void handleComputerMove() {
        if (gameOver) return;
        if (state.allGemsCollected()) return;

        Vec target = findNearestGem(state.ball);
        if (target == null) return;

        // Build candidate moves with simulation and scoring
        List<Choice> candidates = new ArrayList<>();
        for (Vec dir : DIRS) {
            SimResult sim = simulateSlide(state.ball, dir);
            if (!sim.moved) continue;
            
            // Check if move stays in current quadrant
            if (!state.isInCurrentQuadrant(sim.end) && state.currentQuadrant != Quadrant.ALL) continue;
            
            int score = 0;
            score += 5 * sim.gemsGained;
            score -= sim.hitMine ? 100 : 0;
            score -= manhattan(sim.end, target);
            if (lastEnd != null && sim.end.equals(lastEnd)) score -= 15;
            if (prevEnd != null && sim.end.equals(prevEnd)) score -= 10;
            candidates.add(new Choice(dir, sim.end, score, sim.hitMine, sim.gemsGained));
        }

        if (candidates.isEmpty()) return;

        Collections.shuffle(candidates, rng);
        mergeSortChoices(candidates);
        Choice best = candidates.get(candidates.size() - 1);

        boolean compDied = slideFrom(state.ball, best.dir, true, true);
        prevEnd = lastEnd;
        lastEnd = best.end;

        lastMoverFill = new Color(255, 150, 50);
        lastMoverStroke = new Color(200, 100, 30);

        // Check if quadrant is complete after computer move
        if (state.currentQuadrant != Quadrant.ALL && state.isCurrentQuadrantComplete()) {
            Quadrant next = state.getNextQuadrant();
            if (next != state.currentQuadrant) {
                state.currentQuadrant = next;
                updateQuadrantSelector();
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, 
                        "Quadrant complete! Moving to " + next.toString().replace('_', ' ') + ".", 
                        "Quadrant Complete", JOptionPane.INFORMATION_MESSAGE));
            }
        }

        if (compDied) {
            state.deaths++;
            explosionCenter = state.ball;
            triggerExplosionAnimation();
            if (state.deaths >= 3) {
                gameOver = true;
                updateStatus();
                board.repaint();
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, "Game over — 3 deaths reached. Click Restart or New game.", "Game Over", JOptionPane.INFORMATION_MESSAGE));
            }
        }
    }

    // ================= MERGE SORT (Explicit Sorting Algorithm) =================
    private static void mergeSortChoices(List<Choice> list) {
        if (list.size() <= 1) return;

        int mid = list.size() / 2;
        List<Choice> left = new ArrayList<>(list.subList(0, mid));
        List<Choice> right = new ArrayList<>(list.subList(mid, list.size()));

        mergeSortChoices(left);
        mergeSortChoices(right);

        merge(list, left, right);
    }

    private static void merge(List<Choice> result, List<Choice> left, List<Choice> right) {
        int i = 0, j = 0, k = 0;

        while (i < left.size() && j < right.size()) {
            if (left.get(i).score <= right.get(j).score) {
                result.set(k++, left.get(i++));
            } else {
                result.set(k++, right.get(j++));
            }
        }

        while (i < left.size()) result.set(k++, left.get(i++));
        while (j < right.size()) result.set(k++, right.get(j++));
    }

    static final class Choice {
        final Vec dir, end;
        final int score;
        final boolean hitsMine;
        final int gemsGained;
        Choice(Vec d, Vec e, int s, boolean hm, int gg) { dir = d; end = e; score = s; hitsMine = hm; gemsGained = gg; }
    }

    static final class SimResult {
        final Vec end;
        final boolean moved, hitMine;
        final int gemsGained;
        SimResult(Vec e, boolean m, boolean h, int g) { end = e; moved = m; hitMine = h; gemsGained = g; }
    }

    private SimResult simulateSlide(Vec startPos, Vec dir) {
        Vec cur = new Vec(startPos.r, startPos.c);
        int gems = 0;
        boolean moved = false;

        while (true) {
            Vec nxt = cur.add(dir);
            if (!state.grid.inBounds(nxt) || state.grid.isWall(nxt)) break;
            moved = true;
            cur = nxt;

            if (state.gemPresent[cur.r][cur.c]) gems++;

            Cell cell = state.grid.get(cur);
            if (cell == Cell.MINE) {
                return new SimResult(new Vec(cur.r, cur.c), true, true, gems);
            }
            if (cell == Cell.STOP) break;
        }
        return new SimResult(new Vec(cur.r, cur.c), moved, false, gems);
    }

    private void triggerExplosionAnimation() {
        showExplosion = true;
        if (explosionTimer != null && explosionTimer.isRunning()) explosionTimer.stop();
        explosionTimer = new javax.swing.Timer(600, ev -> {
            showExplosion = false;
            explosionCenter = null;
            explosionTimer.stop();
            board.repaint();
        });
        explosionTimer.setRepeats(false);
        explosionTimer.start();
    }

    private boolean slideFrom(Vec startPos, Vec dir, boolean collectGems, boolean allowDeath) {
        Vec cur = new Vec(startPos.r, startPos.c);
        boolean died = false;
        while (true) {
            Vec nxt = cur.add(dir);
            if (!state.grid.inBounds(nxt) || state.grid.isWall(nxt)) break;
            cur = nxt;

            if (collectGems && state.gemPresent[cur.r][cur.c]) {
                state.gemPresent[cur.r][cur.c] = false;
                state.gemsCollected++;
            }

            Cell cell = state.grid.get(cur);
            if (cell == Cell.MINE) {
                state.ball = new Vec(cur.r, cur.c);
                if (allowDeath) died = true;
                break;
            }
            if (cell == Cell.STOP) break;
        }
        state.ball = new Vec(cur.r, cur.c);
        return died;
    }

    private Vec previewSlide(Vec startPos, Vec dir) {
        Vec cur = new Vec(startPos.r, startPos.c);
        while (true) {
            Vec nxt = cur.add(dir);
            if (!state.grid.inBounds(nxt) || state.grid.isWall(nxt)) break;
            cur = nxt;
            Cell cell = state.grid.get(cur);
            if (cell == Cell.MINE || cell == Cell.STOP) break;
        }
        return cur;
    }

    private Vec findNearestGem(Vec from) {
        int bestDist = Integer.MAX_VALUE;
        Vec best = null;
        for (int r = 0; r < state.grid.rows; r++) {
            for (int c = 0; c < state.grid.cols; c++) {
                if (state.gemPresent[r][c] && state.isInCurrentQuadrant(new Vec(r, c))) {
                    Vec g = new Vec(r, c);
                    int d = manhattan(from, g);
                    if (d < bestDist) { bestDist = d; best = g; }
                }
            }
        }
        return best;
    }

    private int manhattan(Vec a, Vec b) { return Math.abs(a.r - b.r) + Math.abs(a.c - b.c); }

    private void updateStatusWaiting() {
        String quadrantInfo = state.currentQuadrant == Quadrant.ALL ? 
            "All" : state.currentQuadrant.toString().replace('_', ' ');
        status.setText(String.format("Gems: %d/%d     Deaths: %d     Quadrant: %s     Computer moves in 1s...", 
            state.gemsCollected, state.totalGems, state.deaths, quadrantInfo));
    }

    private void updateStatus() {
        String quadrantInfo = state.currentQuadrant == Quadrant.ALL ? 
            "All" : state.currentQuadrant.toString().replace('_', ' ');
        
        if (gameOver) {
            if (state.allGemsCollected()) {
                status.setText(String.format("Gems: %d/%d     Deaths: %d     Quadrant: %s     COMPLETED", 
                    state.gemsCollected, state.totalGems, state.deaths, quadrantInfo));
            } else {
                status.setText(String.format("Gems: %d/%d     Deaths: %d     Quadrant: %s     GAME OVER", 
                    state.gemsCollected, state.totalGems, state.deaths, quadrantInfo));
            }
        } else {
            String t;
            switch (turn) {
                case HUMAN: t = "Your turn"; break;
                case WAITING: t = "Waiting..."; break;
                case COMPUTER: t = "Computer's turn"; break;
                case SOLVING: t = "Solving..."; break;
                default: t = ""; break;
            }
            status.setText(String.format("Gems: %d/%d     Deaths: %d     Quadrant: %s     %s", 
                state.gemsCollected, state.totalGems, state.deaths, quadrantInfo, t));
        }
    }

    // ===== DIVIDE & CONQUER SOLVER (Solve game quadrant by quadrant) =====
    private void startDivideAndConquerSolver() {
        // Get all quadrants that still have gems
        solverQuadrants = state.getRemainingQuadrants();
        
        if (solverQuadrants.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No gems left to collect!", "Solver", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        // Start with the first quadrant
        currentSolverQuadrantIndex = 0;
        Quadrant firstQuadrant = solverQuadrants.get(0);
        
        // Temporarily set the current quadrant for solving
        state.currentQuadrant = firstQuadrant;
        updateQuadrantSelector();
        
        // Get plan for first quadrant
        currentQuadrantPlan = bfsSolveQuadrant(firstQuadrant);
        
        if (currentQuadrantPlan == null || currentQuadrantPlan.isEmpty()) {
            JOptionPane.showMessageDialog(this, 
                "Cannot solve first quadrant. Try a different approach.", 
                "Solver", JOptionPane.WARNING_MESSAGE);
            state.currentQuadrant = Quadrant.ALL;
            updateQuadrantSelector();
            return;
        }
        
        // Start the solving process
        turn = Turn.SOLVING;
        updateStatus();
        currentPlanIterator = currentQuadrantPlan.iterator();
        startNextSolverMove();
    }
    
    private void startNextSolverMove() {
        if (solverTimer != null && solverTimer.isRunning()) solverTimer.stop();
        
        solverTimer = new javax.swing.Timer(250, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent ev) {
                if (gameOver) {
                    solverTimer.stop();
                    turn = Turn.HUMAN;
                    updateStatus();
                    return;
                }
                
                // If current quadrant plan is done
                if (!currentPlanIterator.hasNext()) {
                    // Check if current quadrant is complete
                    if (state.isCurrentQuadrantComplete()) {
                        JOptionPane.showMessageDialog(Inertia.this, 
                            "Quadrant " + state.currentQuadrant.toString().replace('_', ' ') + " complete!", 
                            "Solver", JOptionPane.INFORMATION_MESSAGE);
                        
                        // Move to next quadrant
                        currentSolverQuadrantIndex++;
                        
                        if (currentSolverQuadrantIndex < solverQuadrants.size()) {
                            // Load next quadrant
                            Quadrant nextQuadrant = solverQuadrants.get(currentSolverQuadrantIndex);
                            state.currentQuadrant = nextQuadrant;
                            updateQuadrantSelector();
                            
                            // Get plan for next quadrant
                            currentQuadrantPlan = bfsSolveQuadrant(nextQuadrant);
                            
                            if (currentQuadrantPlan == null || currentQuadrantPlan.isEmpty()) {
                                solverTimer.stop();
                                JOptionPane.showMessageDialog(Inertia.this, 
                                    "Cannot solve next quadrant. Stopping solver.", 
                                    "Solver", JOptionPane.ERROR_MESSAGE);
                                turn = Turn.HUMAN;
                                return;
                            }
                            
                            currentPlanIterator = currentQuadrantPlan.iterator();
                            // Continue with next move - timer will automatically continue
                        } else {
                            // All quadrants done!
                            solverTimer.stop();
                            gameOver = true;
                            updateStatus();
                            board.repaint();
                            JOptionPane.showMessageDialog(Inertia.this, 
                                "Solved! All gems collected using Divide & Conquer!", 
                                "Solver", JOptionPane.INFORMATION_MESSAGE);
                            turn = Turn.HUMAN;
                            state.currentQuadrant = Quadrant.ALL;
                            updateQuadrantSelector();
                        }
                        return; // Important: exit to let timer continue with next cycle if needed
                    } else {
                        // Plan didn't complete the quadrant - try to replan
                        currentQuadrantPlan = bfsSolveQuadrant(state.currentQuadrant);
                        if (currentQuadrantPlan == null || currentQuadrantPlan.isEmpty()) {
                            solverTimer.stop();
                            JOptionPane.showMessageDialog(Inertia.this, 
                                "Solver stuck. Stopping.", 
                                "Solver", JOptionPane.ERROR_MESSAGE);
                            turn = Turn.HUMAN;
                            return;
                        }
                        currentPlanIterator = currentQuadrantPlan.iterator();
                    }
                }
                
                // Execute next move (only if we have a next move)
                if (currentPlanIterator.hasNext()) {
                    int dirIdx = currentPlanIterator.next();
                    Vec dir = DIRS[dirIdx];
                    
                    lastMoverFill = new Color(30, 180, 90);
                    lastMoverStroke = new Color(10, 120, 60);
                    
                    boolean died = slideFrom(state.ball, dir, true, false);
                    if (died) {
                        solverTimer.stop();
                        JOptionPane.showMessageDialog(Inertia.this, 
                            "Solver encountered a mine unexpectedly. Stopping.", 
                            "Solver", JOptionPane.ERROR_MESSAGE);
                        turn = Turn.HUMAN;
                    }
                    
                    board.repaint();
                }
            }
        });
        
        solverTimer.setRepeats(true);
        solverTimer.start();
    }

    // ===== Quadrant Solver (solves a specific quadrant) =====
    private void startQuadrantSolver() {
        if (state.currentQuadrant == Quadrant.ALL) return;
        
        List<Integer> plan = bfsSolveQuadrant(state.currentQuadrant);
        if (plan == null || plan.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No safe solution found for this quadrant.", "Solver", JOptionPane.WARNING_MESSAGE);
            return;
        }

        turn = Turn.SOLVING;
        updateStatus();

        final Iterator<Integer> it = plan.iterator();
        if (solverTimer != null && solverTimer.isRunning()) solverTimer.stop();
        solverTimer = new javax.swing.Timer(250, ev -> {
            if (gameOver) { solverTimer.stop(); turn = Turn.HUMAN; updateStatus(); return; }
            if (!it.hasNext()) {
                solverTimer.stop();
                
                if (state.isCurrentQuadrantComplete()) {
                    JOptionPane.showMessageDialog(this, 
                        "Quadrant solved! " + state.currentQuadrant.toString().replace('_', ' ') + " complete.", 
                        "Solver", JOptionPane.INFORMATION_MESSAGE);
                    
                    // Move to next quadrant if any
                    Quadrant next = state.getNextQuadrant();
                    if (next != state.currentQuadrant) {
                        state.currentQuadrant = next;
                        updateQuadrantSelector();
                    }
                }
                
                turn = Turn.HUMAN;
                updateStatus();
                return;
            }
            
            int dirIdx = it.next();
            Vec dir = DIRS[dirIdx];
            
            lastMoverFill = new Color(30, 180, 90);
            lastMoverStroke = new Color(10, 120, 60);

            boolean died = slideFrom(state.ball, dir, true, false);
            if (died) {
                solverTimer.stop();
                JOptionPane.showMessageDialog(this, "Solver encountered a mine unexpectedly. Stopping.", "Solver", JOptionPane.ERROR_MESSAGE);
                turn = Turn.HUMAN;
            }

            board.repaint();
        });
        solverTimer.setRepeats(true);
        solverTimer.start();
    }

    private List<Integer> bfsSolveQuadrant(Quadrant targetQuadrant) {
        int rows = state.grid.rows, cols = state.grid.cols;
        int midR = rows / 2;
        int midC = cols / 2;

        // Map gems in target quadrant only
        int[][] gemIndex = new int[rows][cols];
        for (int[] row : gemIndex) Arrays.fill(row, -1);

        int gemCount = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (state.gemPresent[r][c]) {
                    boolean inTarget = false;
                    if (targetQuadrant == Quadrant.TOP_LEFT && r < midR && c < midC) inTarget = true;
                    else if (targetQuadrant == Quadrant.TOP_RIGHT && r < midR && c >= midC) inTarget = true;
                    else if (targetQuadrant == Quadrant.BOTTOM_LEFT && r >= midR && c < midC) inTarget = true;
                    else if (targetQuadrant == Quadrant.BOTTOM_RIGHT && r >= midR && c >= midC) inTarget = true;
                    
                    if (inTarget) {
                        gemIndex[r][c] = gemCount++;
                    }
                }
            }
        }

        if (gemCount == 0) return Collections.emptyList();

        int fullMask = (1 << gemCount) - 1;

        class Node {
            final int r, c, mask, dirIdx;
            Node(int r, int c, int mask, int dirIdx) { this.r = r; this.c = c; this.mask = mask; this.dirIdx = dirIdx; }
        }

        boolean[][][] visited = new boolean[rows][cols][1 << gemCount];
        ArrayDeque<Node> q = new ArrayDeque<>();
        
        int startR = state.ball.r, startC = state.ball.c;
        int startMask = 0;
        if (gemIndex[startR][startC] != -1) startMask |= 1 << gemIndex[startR][startC];

        visited[startR][startC][startMask] = true;
        q.add(new Node(startR, startC, startMask, -1));

        int[][][] prevR = new int[rows][cols][1 << gemCount];
        int[][][] prevC = new int[rows][cols][1 << gemCount];
        int[][][] prevM = new int[rows][cols][1 << gemCount];
        int[][][] prevDir = new int[rows][cols][1 << gemCount];
        
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                Arrays.fill(prevDir[r][c], -2);

        prevDir[startR][startC][startMask] = -1;

        while (!q.isEmpty()) {
            Node cur = q.poll();
            if (cur.mask == fullMask) {
                return reconstructPath(prevR, prevC, prevM, prevDir, cur.r, cur.c, cur.mask);
            }

            for (int di = 0; di < DIRS.length; di++) {
                Vec dir = DIRS[di];
                SlideResult sr = slideForSolver(cur.r, cur.c, dir, state.grid, gemIndex, cur.mask);
                if (!sr.moved || sr.died) continue;
                
                // Ensure we stay in target quadrant
                if (!isInQuadrant(sr.r, sr.c, targetQuadrant, midR, midC)) continue;
                
                if (!visited[sr.r][sr.c][sr.mask]) {
                    visited[sr.r][sr.c][sr.mask] = true;
                    prevR[sr.r][sr.c][sr.mask] = cur.r;
                    prevC[sr.r][sr.c][sr.mask] = cur.c;
                    prevM[sr.r][sr.c][sr.mask] = cur.mask;
                    prevDir[sr.r][sr.c][sr.mask] = di;
                    q.add(new Node(sr.r, sr.c, sr.mask, di));
                }
            }
        }
        return null;
    }

    private boolean isInQuadrant(int r, int c, Quadrant q, int midR, int midC) {
        switch (q) {
            case TOP_LEFT: return r < midR && c < midC;
            case TOP_RIGHT: return r < midR && c >= midC;
            case BOTTOM_LEFT: return r >= midR && c < midC;
            case BOTTOM_RIGHT: return r >= midR && c >= midC;
            default: return true;
        }
    }

    // ===== Original Solver (kept for reference, but not used by Solve game button anymore) =====
    private List<Integer> bfsSolveCurrentState() {
        int rows = state.grid.rows, cols = state.grid.cols;

        int[][] gemIndex = new int[rows][cols];
        for (int[] row : gemIndex) Arrays.fill(row, -1);

        int gemCount = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (state.gemPresent[r][c]) {
                    gemIndex[r][c] = gemCount++;
                }
            }
        }

        if (gemCount == 0) return Collections.emptyList();
        if (gemCount > 20) gemCount = 20;

        int fullMask = (1 << gemCount) - 1;

        boolean[][][] visited;
        try {
            visited = new boolean[rows][cols][1 << gemCount];
        } catch (OutOfMemoryError oom) {
            return bfsSolveWithHashVisited(gemIndex, gemCount, fullMask);
        }

        class Node {
            final int r, c, mask, dirIdx;
            Node(int r, int c, int mask, int dirIdx) { this.r = r; this.c = c; this.mask = mask; this.dirIdx = dirIdx; }
        }

        ArrayDeque<Node> q = new ArrayDeque<>();
        int startR = state.ball.r, startC = state.ball.c;
        int startMask = 0;
        if (gemIndex[startR][startC] != -1) startMask |= 1 << gemIndex[startR][startC];

        visited[startR][startC][startMask] = true;
        q.add(new Node(startR, startC, startMask, -1));

        int[][][] prevR = new int[rows][cols][1 << gemCount];
        int[][][] prevC = new int[rows][cols][1 << gemCount];
        int[][][] prevM = new int[rows][cols][1 << gemCount];
        int[][][] prevDir = new int[rows][cols][1 << gemCount];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                Arrays.fill(prevDir[r][c], -2);

        prevDir[startR][startC][startMask] = -1;

        while (!q.isEmpty()) {
            Node cur = q.poll();
            if (cur.mask == fullMask) {
                return reconstructPath(prevR, prevC, prevM, prevDir, cur.r, cur.c, cur.mask);
            }

            for (int di = 0; di < DIRS.length; di++) {
                Vec dir = DIRS[di];
                SlideResult sr = slideForSolver(cur.r, cur.c, dir, state.grid, gemIndex, cur.mask);
                if (!sr.moved || sr.died) continue;
                if (!visited[sr.r][sr.c][sr.mask]) {
                    visited[sr.r][sr.c][sr.mask] = true;
                    prevR[sr.r][sr.c][sr.mask] = cur.r;
                    prevC[sr.r][sr.c][sr.mask] = cur.c;
                    prevM[sr.r][sr.c][sr.mask] = cur.mask;
                    prevDir[sr.r][sr.c][sr.mask] = di;
                    q.add(new Node(sr.r, sr.c, sr.mask, di));
                }
            }
        }
        return null;
    }

    private List<Integer> bfsSolveWithHashVisited(int[][] gemIndex, int gemCount, int fullMask) {
        int rows = state.grid.rows, cols = state.grid.cols;

        class Key {
            final int r, c, mask;
            Key(int r, int c, int mask) { this.r = r; this.c = c; this.mask = mask; }
            @Override public boolean equals(Object o) {
                if (!(o instanceof Key)) return false;
                Key k = (Key) o;
                return r == k.r && c == k.c && mask == k.mask;
            }
            @Override public int hashCode() { return Objects.hash(r, c, mask); }
        }
        class Node {
            final int r, c, mask;
            Node(int r, int c, int mask) { this.r = r; this.c = c; this.mask = mask; }
        }
        Map<Key, Key> prev = new HashMap<>();
        Map<Key, Integer> prevDir = new HashMap<>();

        ArrayDeque<Node> q = new ArrayDeque<>();
        int startR = state.ball.r, startC = state.ball.c;
        int startMask = 0;
        if (gemIndex[startR][startC] != -1) startMask |= 1 << gemIndex[startR][startC];
        Key startKey = new Key(startR, startC, startMask);

        Set<Key> visited = new HashSet<>();
        visited.add(startKey);
        q.add(new Node(startR, startC, startMask));
        prev.put(startKey, null);
        prevDir.put(startKey, -1);

        while (!q.isEmpty()) {
            Node cur = q.poll();
            Key curKey = new Key(cur.r, cur.c, cur.mask);
            if (cur.mask == fullMask) {
                return reconstructPath(prev, prevDir, curKey);
            }
            for (int di = 0; di < DIRS.length; di++) {
                Vec dir = DIRS[di];
                SlideResult sr = slideForSolver(cur.r, cur.c, dir, state.grid, gemIndex, cur.mask);
                if (!sr.moved || sr.died) continue;
                Key nextKey = new Key(sr.r, sr.c, sr.mask);
                if (!visited.contains(nextKey)) {
                    visited.add(nextKey);
                    prev.put(nextKey, curKey);
                    prevDir.put(nextKey, di);
                    q.add(new Node(sr.r, sr.c, sr.mask));
                }
            }
        }
        return null;
    }

    private List<Integer> reconstructPath(int[][][] prevR, int[][][] prevC, int[][][] prevM, int[][][] prevDir, int endR, int endC, int endM) {
        List<Integer> dirs = new ArrayList<>();
        int r = endR, c = endC, m = endM;
        while (true) {
            int d = prevDir[r][c][m];
            if (d == -1) break;
            dirs.add(d);
            int pr = prevR[r][c][m];
            int pc = prevC[r][c][m];
            int pm = prevM[r][c][m];
            r = pr; c = pc; m = pm;
        }
        Collections.reverse(dirs);
        return dirs;
    }

    private List<Integer> reconstructPath(Map<?, ?> prevGeneric, Map<?, ?> prevDirGeneric, Object endKey) {
        @SuppressWarnings("unchecked")
        Map<Object, Object> prev = (Map<Object, Object>) prevGeneric;
        @SuppressWarnings("unchecked")
        Map<Object, Integer> prevDir = (Map<Object, Integer>) prevDirGeneric;

        List<Integer> dirs = new ArrayList<>();
        Object cur = endKey;
        while (true) {
            Integer d = prevDir.get(cur);
            if (d == null || d == -1) break;
            dirs.add(d);
            cur = prev.get(cur);
        }
        Collections.reverse(dirs);
        return dirs;
    }

    private static class SlideResult {
        final int r, c, mask;
        final boolean died;
        final boolean moved;
        SlideResult(int r, int c, int mask, boolean died, boolean moved) {
            this.r = r; this.c = c; this.mask = mask; this.died = died; this.moved = moved;
        }
    }

    private static SlideResult slideForSolver(int r, int c, Vec dir, Grid grid, int[][] gemIndex, int mask) {
        int rows = grid.rows, cols = grid.cols;
        int cr = r, cc = c;
        int curMask = mask;
        boolean moved = false;

        while (true) {
            int nr = cr + dir.r;
            int nc = cc + dir.c;
            Vec nxt = new Vec(nr, nc);
            if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) break;
            if (grid.isWall(nxt)) break;
            moved = true;
            cr = nr;
            cc = nc;

            Cell cell = grid.get(nxt);
            if (cell == Cell.MINE) {
                return new SlideResult(cr, cc, curMask, true, true);
            }
            int gi = gemIndex[cr][cc];
            if (gi != -1) curMask |= (1 << gi);
            if (cell == Cell.STOP) break;
        }
        return new SlideResult(cr, cc, curMask, false, moved);
    }

    // ===== Board Rendering =====
    static final class BoardPanel extends JPanel {
        GameState state;
        int cellSize = 40;
        int pad = 16;
        int originX = 16;
        int originY = 16;

        BoardPanel(GameState s) {
            this.state = s;
            setBackground(Color.WHITE);
            addMouseListener(new MouseAdapter() { @Override public void mousePressed(MouseEvent e) { handleClick(e.getX(), e.getY()); }});
        }

        void setState(GameState s) { this.state = s; }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int rows = state.grid.rows, cols = state.grid.cols;

            int boardW = cols * cellSize;
            int boardH = rows * cellSize;
            originX = Math.max(pad, (getWidth()  - boardW) / 2);
            originY = Math.max(pad, (getHeight() - boardH) / 2);

            // Draw quadrant dividers
            int midR = rows / 2;
            int midC = cols / 2;
            g2.setStroke(new BasicStroke(3f));
            g2.setColor(new Color(100, 100, 200, 100));
            
            // Draw quadrant overlay if not ALL
            if (state.currentQuadrant != Quadrant.ALL) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
                g2.setColor(new Color(200, 200, 255));
                
                switch (state.currentQuadrant) {
                    case TOP_LEFT:
                        g2.fillRect(originX, originY, midC * cellSize, midR * cellSize);
                        break;
                    case TOP_RIGHT:
                        g2.fillRect(originX + midC * cellSize, originY, (cols - midC) * cellSize, midR * cellSize);
                        break;
                    case BOTTOM_LEFT:
                        g2.fillRect(originX, originY + midR * cellSize, midC * cellSize, (rows - midR) * cellSize);
                        break;
                    case BOTTOM_RIGHT:
                        g2.fillRect(originX + midC * cellSize, originY + midR * cellSize, 
                                   (cols - midC) * cellSize, (rows - midR) * cellSize);
                        break;
                }
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
            }
            
            // Draw quadrant lines
            g2.setColor(new Color(100, 100, 200));
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{5}, 0));
            g2.drawLine(originX + midC * cellSize, originY, originX + midC * cellSize, originY + rows * cellSize);
            g2.drawLine(originX, originY + midR * cellSize, originX + cols * cellSize, originY + midR * cellSize);

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int x = originX + c * cellSize;
                    int y = originY + r * cellSize;

                    g2.setColor(new Color(245, 245, 245));
                    g2.fillRect(x, y, cellSize, cellSize);
                    g2.setColor(new Color(220, 220, 220));
                    g2.drawRect(x, y, cellSize, cellSize);

                    Cell cell = state.grid.cells[r][c];
                    switch (cell) {
                        case WALL:
                            g2.setColor(new Color(80, 80, 80));
                            g2.fillRect(x, y, cellSize, cellSize);
                            break;
                        case BLOCK:
                            drawBlock(g2, x, y);
                            break;
                        case MINE:
                            drawBomb(g2, x, y);
                            break;
                        case STOP:
                            drawStop(g2, x, y);
                            break;
                        default:
                            break;
                    }
                    if (state.gemPresent[r][c]) drawGem(g2, x, y);
                }
            }

            Inertia outer = (Inertia) SwingUtilities.getWindowAncestor(this);
            if (outer != null && outer.showExplosion && outer.explosionCenter != null) {
                Vec e = outer.explosionCenter;
                int ex = originX + e.c * cellSize;
                int ey = originY + e.r * cellSize;
                drawExplosion(g2, ex, ey);
            }

            drawSharedBall(g2, state.ball, outer == null ? new Color(30, 180, 90) : outer.lastMoverFill,
                           outer == null ? new Color(10, 120, 60) : outer.lastMoverStroke);
        }

        private void drawSharedBall(Graphics2D g2, Vec pos, Color fill, Color stroke) {
            int px = originX + pos.c * cellSize + cellSize / 2;
            int py = originY + pos.r * cellSize + cellSize / 2;
            int rad = cellSize / 2 - 6;
            g2.setColor(new Color(240, 240, 240));
            g2.fillOval(px - rad - 4, py - rad - 4, (rad + 4) * 2, (rad + 4) * 2);

            g2.setColor(fill);
            g2.fillOval(px - rad, py - rad, rad * 2, rad * 2);
            g2.setColor(stroke);
            g2.setStroke(new BasicStroke(3f));
            g2.drawOval(px - rad, py - rad, rad * 2, rad * 2);
        }

        private void drawGem(Graphics2D g2, int x, int y) {
            int s = cellSize;
            Polygon p = new Polygon();
            p.addPoint(x + s/2, y + 6);
            p.addPoint(x + s - 6, y + s/2);
            p.addPoint(x + s/2, y + s - 6);
            p.addPoint(x + 6, y + s/2);
            g2.setColor(new Color(60, 140, 230));
            g2.fillPolygon(p);
            g2.setColor(new Color(25, 90, 170));
            g2.setStroke(new BasicStroke(2f));
            g2.drawPolygon(p);
        }

        private void drawBomb(Graphics2D g2, int x, int y) {
            int s = cellSize;
            int cx = x + s/2, cy = y + s/2;
            int r = s/2 - 8;
            g2.setColor(Color.BLACK);
            g2.fillOval(cx - r, cy - r, r*2, r*2);
            GradientPaint gp = new GradientPaint(cx - r, cy - r, new Color(90, 90, 90), cx + r, cy + r, new Color(20, 20, 20));
            Paint oldp = g2.getPaint();
            g2.setPaint(gp);
            g2.fillOval(cx - (r/2), cy - (r/2), r, r);
            g2.setPaint(oldp);
            g2.setColor(new Color(70, 70, 70));
            g2.setStroke(new BasicStroke(2f));
            g2.drawOval(cx - r, cy - r, r*2, r*2);
            int fx1 = cx + r - 2, fy1 = cy - r + 6;
            int fx2 = fx1 + 12, fy2 = fy1 - 10;
            g2.setStroke(new BasicStroke(3f));
            g2.setColor(new Color(120, 80, 30));
            g2.drawLine(fx1, fy1, fx2, fy2);
            Polygon flame = new Polygon();
            flame.addPoint(fx2 + 3, fy2);
            flame.addPoint(fx2 + 9, fy2 - 5);
            flame.addPoint(fx2 + 3, fy2 - 10);
            g2.setColor(new Color(255, 160, 20));
            g2.fillPolygon(flame);
            g2.setColor(new Color(200, 90, 10));
            g2.setStroke(new BasicStroke(1f));
            g2.drawPolygon(flame);
        }

        private void drawBlock(Graphics2D g2, int x, int y) {
            int s = cellSize;
            g2.setColor(new Color(200, 200, 200)); g2.fillRect(x, y, s, s);
            g2.setColor(new Color(220, 220, 220)); g2.fillRect(x+4, y+4, s-8, s-8);
            int b = Math.max(3, s/10);
            g2.setColor(Color.WHITE); g2.fillRect(x, y, s, b); g2.fillRect(x, y, b, s);
            g2.setColor(new Color(180, 180, 180)); g2.fillRect(x + s - b, y, b, s); g2.fillRect(x, y + s - b, s, b);
        }

        private void drawStop(Graphics2D g2, int x, int y) {
            int s = cellSize;
            int cx = x + s/2, cy = y + s/2;
            int r = s/2 - 6;
            g2.setColor(new Color(160, 160, 160));
            g2.setStroke(new BasicStroke(3f));
            g2.drawOval(cx - r, cy - r, r*2, r*2);
        }

        private void drawExplosion(Graphics2D g2, int cellX, int cellY) {
            int cx = cellX + cellSize/2, cy = cellY + cellSize/2;
            int maxR = cellSize;
            Composite old = g2.getComposite();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
            g2.setColor(new Color(220, 30, 30));
            g2.fillOval(cx - maxR/2, cy - maxR/2, maxR, maxR);
            g2.setStroke(new BasicStroke(3f));
            for (int a = 0; a < 360; a += 30) {
                double rad = Math.toRadians(a);
                int x2 = cx + (int) (Math.cos(rad) * (maxR));
                int y2 = cy + (int) (Math.sin(rad) * (maxR));
                g2.drawLine(cx, cy, x2, y2);
            }
            g2.setComposite(old);
        }

        private void handleClick(int mx, int my) {
            Container top = getTopLevelAncestor();
            if (!(top instanceof Inertia)) return;
            Inertia outer = (Inertia) top;
            if (outer.turn != Turn.HUMAN || outer.gameOver) return;

            int c = (mx - originX) / cellSize;
            int r = (my - originY) / cellSize;
            if (r < 0 || c < 0 || r >= state.grid.rows || c >= state.grid.cols) return;
            int dr = Integer.compare(r, state.ball.r);
            int dc = Integer.compare(c, state.ball.c);
            Vec dir = new Vec(dr, dc);
            if (dr == 0 && dc == 0) return;
            outer.handleHumanTurn(dir);
        }

        @Override public Dimension getPreferredSize() {
            int rows = state.grid.rows, cols = state.grid.cols;
            return new Dimension(cols * cellSize + pad * 2, rows * cellSize + pad * 2);
        }
    }

    // ===== Level handling and generator =====
    static final class Level {
        final String[] rows;
        Level(String[] rows) { this.rows = rows; }

        GameState toGameState() {
            int R = rows.length, C = rows[0].length();
            Cell[][] cells = new Cell[R][C];
            boolean[][] gem = new boolean[R][C];
            Vec start = null;
            int gems = 0;
            for (int r = 0; r < R; r++) {
                for (int c = 0; c < C; c++) {
                    char ch = rows[r].charAt(c);
                    Cell cell;
                    switch (ch) {
                        case '#': cell = Cell.WALL; break;
                        case '*': cell = Cell.MINE; break;
                        case 'O': cell = Cell.STOP; break;
                        case 'G': cell = Cell.EMPTY; gem[r][c] = true; gems++; break;
                        case 'S':
                            cell = Cell.STOP;
                            start = new Vec(r, c);
                            break;
                        case 'B': cell = Cell.BLOCK; break;
                        case ' ': default: cell = Cell.EMPTY; break;
                    }
                    cells[r][c] = cell;
                }
            }
            if (start == null) throw new IllegalStateException("Level missing start 'S'");
            return new GameState(new Grid(cells), start, gem, gems);
        }

        private static final int MIN_DIFFICULT_STEPS = 10;

        static Level generateRandomLevel() {
            final int rows = 10, cols = 12;
            final int GEMS = 16, STOPS = 17, MINES = 16, BLOCKS = 16;
            final Random rand = new Random();

            for (int attempt=0; attempt<10;attempt++) {
                List<Vec> stopsPath = new ArrayList<>();
                Vec cur = new Vec(rand.nextInt(rows), rand.nextInt(cols));
                stopsPath.add(cur);
                while (stopsPath.size() < STOPS) {
                    Vec dir = DIRS[rand.nextInt(DIRS.length)];
                    int steps = 1 + rand.nextInt(3);
                    Vec nxt = cur;
                    for (int i = 0; i < steps; i++) {
                        Vec candidate = nxt.add(dir);
                        if (candidate.r < 1 || candidate.r >= rows-1 || candidate.c < 1 || candidate.c >= cols-1) break;
                        nxt = candidate;
                    }
                    if (!stopsPath.contains(nxt)) {
                        stopsPath.add(nxt);
                        cur = nxt;
                    }
                }

                char[][] grid = new char[rows][cols];
                for (int r = 0; r < rows; r++) Arrays.fill(grid[r], ' ');

                for (Vec v : stopsPath) grid[v.r][v.c] = 'O';

                Vec start = stopsPath.get(rand.nextInt(stopsPath.size()));
                grid[start.r][start.c] = 'S';

                Set<Vec> used = new HashSet<>(stopsPath);
                used.add(start);
                int placedGems = 0;
                for (Vec v : stopsPath) {
                    if (placedGems >= GEMS) break;
                    List<Vec> candidates = new ArrayList<>();
                    for (Vec d : DIRS) {
                        Vec a = new Vec(v.r + d.r, v.c + d.c);
                        if (a.r >= 0 && a.r < rows && a.c >= 0 && a.c < cols && grid[a.r][a.c] == ' ' && !used.contains(a))
                            candidates.add(a);
                    }
                    if (!candidates.isEmpty()) {
                        Vec g = candidates.get(rand.nextInt(candidates.size()));
                        grid[g.r][g.c] = 'G';
                        used.add(g);
                        placedGems++;
                    }
                }
                while (placedGems < GEMS) {
                    int r = rand.nextInt(rows), c = rand.nextInt(cols);
                    if (grid[r][c] == ' ') { grid[r][c] = 'G'; placedGems++; }
                }

                int placedBlocks = 0;
                while (placedBlocks < BLOCKS) {
                    int r = rand.nextInt(rows), c = rand.nextInt(cols);
                    Vec v = new Vec(r, c);
                    if (grid[r][c] == ' ' && !stopsPath.contains(v)) {
                        grid[r][c] = 'B';
                        placedBlocks++;
                    }
                }

                int placedMines = 0;
                while (placedMines < MINES) {
                    int r = rand.nextInt(rows), c = rand.nextInt(cols);
                    Vec v = new Vec(r, c);
                    if (grid[r][c] == ' ' && !stopsPath.contains(v)) {
                        grid[r][c] = '*';
                        placedMines++;
                    }
                }

                if (!balancedDistribution(grid, 'G', GEMS)
                        || !balancedDistribution(grid, 'O', STOPS)
                        || !balancedDistribution(grid, '*', MINES)
                        || !balancedDistribution(grid, 'B', BLOCKS)) {
                    continue;
                }

                if (!isReachable(grid)) continue;

                String[] map = new String[rows];
                for (int r = 0; r < rows; r++) map[r] = new String(grid[r]);
                return new Level(map);
            }
            throw new RuntimeException("Failed to generate a solvable level after 10 attempts.");
        }
        
        static boolean isReachable(char[][] grid) {
            int R = grid.length, C = grid[0].length;
            boolean[][] vis = new boolean[R][C];
            ArrayDeque<Vec> q = new ArrayDeque<>();

            for (int r = 0; r < R; r++)
                for (int c = 0; c < C; c++)
                    if (grid[r][c] == 'S') {
                        q.add(new Vec(r,c));
                        vis[r][c] = true;
                    }

            while (!q.isEmpty()) {
                Vec v = q.poll();
                for (Vec d : DIRS) {
                    Vec cur = v;
                    while (true) {
                        Vec nxt = cur.add(d);
                        if (nxt.r<0||nxt.c<0||nxt.r>=R||nxt.c>=C) break;
                        if (grid[nxt.r][nxt.c]=='#' || grid[nxt.r][nxt.c]=='B') break;
                        cur = nxt;
                        if (!vis[cur.r][cur.c]) {
                            vis[cur.r][cur.c] = true;
                            q.add(cur);
                        }
                        if (grid[cur.r][cur.c]=='O' || grid[cur.r][cur.c]=='*') break;
                    }
                }
            }

            for (int r = 0; r < R; r++)
                for (int c = 0; c < C; c++)
                    if (grid[r][c]=='G' && !vis[r][c])
                        return false;

            return true;
        }

        private static boolean balancedDistribution(char[][] grid, char ch, int total) {
            int rows = grid.length, cols = grid[0].length;
            int left = 0, right = 0, top = 0, bottom = 0;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    char cell = grid[r][c];
                    boolean match = (cell == ch);
                    if (ch == 'O' && cell == 'S') match = true;
                    if (!match) continue;
                    if (c < cols/2) left++; else right++;
                    if (r < rows/2) top++; else bottom++;
                }
            }
            int minSide = Math.max(1, total / 4);
            return left >= minSide && right >= minSide && top >= minSide && bottom >= minSide;
        }

        private static int shortestSolutionMoves(char[][] grid) {
            int rows = grid.length, cols = grid[0].length;

            int[][] gemIndex = new int[rows][cols];
            for (int[] row : gemIndex) Arrays.fill(row, -1);

            int gemCount = 0;
            int startR = -1, startC = -1;

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    char ch = grid[r][c];
                    if (ch == 'G') {
                        gemIndex[r][c] = gemCount++;
                    } else if (ch == 'S') {
                        startR = r; startC = c;
                    }
                }
            }

            if (startR < 0 || gemCount == 0) return -1;
            if (gemCount > 14) gemCount = 14;

            int fullMask = (1 << gemCount) - 1;
            int maskSize = 1 << gemCount;

            boolean[][][] visited = new boolean[rows][cols][maskSize];

            class State {
                int r, c, mask, dist;
                State(int r, int c, int mask, int dist) { this.r = r; this.c = c; this.mask = mask; this.dist = dist; }
            }

            ArrayDeque<State> q = new ArrayDeque<>();
            int startMask = 0;
            if (gemIndex[startR][startC] != -1) {
                startMask |= 1 << gemIndex[startR][startC];
            }
            visited[startR][startC][startMask] = true;
            q.add(new State(startR, startC, startMask, 0));

            while (!q.isEmpty()) {
                State s = q.poll();
                if (s.mask == fullMask) return s.dist;

                for (Vec dir : DIRS) {
                    SlideResult sr = slideForSolver(s.r, s.c, dir, toGrid(grid), gemIndex, s.mask);
                    if (!sr.moved || sr.died) continue;
                    if (!visited[sr.r][sr.c][sr.mask]) {
                        visited[sr.r][sr.c][sr.mask] = true;
                        q.add(new State(sr.r, sr.c, sr.mask, s.dist + 1));
                    }
                }
            }
            return -1;
        }

        private static Grid toGrid(char[][] map) {
            int R = map.length, C = map[0].length;
            Cell[][] cells = new Cell[R][C];
            for (int r = 0; r < R; r++) {
                for (int c = 0; c < C; c++) {
                    switch (map[r][c]) {
                        case '#': cells[r][c] = Cell.WALL; break;
                        case '*': cells[r][c] = Cell.MINE; break;
                        case 'O': cells[r][c] = Cell.STOP; break;
                        case 'S': cells[r][c] = Cell.STOP; break;
                        case 'B': cells[r][c] = Cell.BLOCK; break;
                        case 'G': cells[r][c] = Cell.EMPTY; break;
                        default: cells[r][c] = Cell.EMPTY; break;
                    }
                }
            }
            return new Grid(cells);
        }

        static Level currentLevelSnapshot(GameState st) {
            int R = st.grid.rows, C = st.grid.cols;
            String[] out = new String[R];
            for (int r = 0; r < R; r++) {
                StringBuilder sb = new StringBuilder(C);
                for (int c = 0; c < C; c++) {
                    Vec v = new Vec(r, c);
                    if (st.ball.equals(v)) { sb.append('S'); continue; }
                    Cell cell = st.grid.cells[r][c];
                    switch (cell) {
                        case WALL: sb.append('#'); break;
                        case MINE: sb.append('*'); break;
                        case STOP: sb.append('O'); break;
                        case BLOCK: sb.append('B'); break;
                        default: sb.append(st.gemPresent[r][c] ? 'G' : ' '); break;
                    }
                }
                out[r] = sb.toString();
            }
            return new Level(out);
        }
    }
}