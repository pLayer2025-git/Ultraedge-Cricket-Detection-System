package com.final_year_project.ultraedge.ui;

import com.final_year_project.ultraedge.model.DeliveryBundle;
import com.final_year_project.ultraedge.service.BatCaptureService;
import com.final_year_project.ultraedge.service.CameraWebSocketHandler;
import com.final_year_project.ultraedge.service.RecordingCoordinator;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class CricketScorecardApp extends JFrame {

    // ==========================================
    // 1. MODELS & ENUMS
    // ==========================================
    public enum DeliveryState {
        IDLE,
        ARMING_HARDWARE,
        RECORDING,
        WAITING_INGESTION,
        DELIVERY_LOCKED,
        FAIR_SELECTED,
        WIDE_SELECTED
    }

    public static class Batsman implements Serializable {
        private static final long serialVersionUID = 1L;
        String name;
        int runs = 0, balls = 0, fours = 0, sixes = 0;
        boolean isOut = false;
        String dismissalText = "";

        public Batsman(String name) { this.name = name; }

        public double getStrikeRate() {
            return balls == 0 ? 0.0 : (runs * 100.0) / balls;
        }
    }

    public static class Bowler implements Serializable {
        private static final long serialVersionUID = 1L;
        String name;
        int ballsBowled = 0, runsConceded = 0, wicketsTaken = 0;

        public Bowler(String name) { this.name = name; }

        public String getOversFormatted() {
            return (ballsBowled / 6) + "." + (ballsBowled % 6);
        }

        public double getEconomy() {
            return ballsBowled == 0 ? 0.0 : (runsConceded * 6.0) / ballsBowled;
        }
    }

    public static class MatchState implements Serializable {
        private static final long serialVersionUID = 1L;
        String battingTeam, bowlingTeam;
        int totalOvers, currentInnings = 1;
        int totalRuns = 0, totalWickets = 0, legalBalls = 0, extrasConceded = 0, target = -1;

        List<Batsman> batsmen = new ArrayList<>();
        List<Bowler> bowlers = new ArrayList<>();

        int strikerIndex = 0;
        int nonStrikerIndex = 1;
        int nextBatsmanNumber = 3;
        int currentBowlerIndex = 0;
        int lastBowlerIndex = -1;
        boolean isMatchOver = false;
        String matchSummary = "Match in progress";

        public MatchState(String batTeam, String bowlTeam, int overs) {
            this.battingTeam = batTeam;
            this.bowlingTeam = bowlTeam;
            this.totalOvers = overs;
            for (int i = 1; i <= 11; i++) {
                batsmen.add(new Batsman("Player " + i));
                bowlers.add(new Bowler("Bowler " + i));
            }
        }

        public String getOversFormatted() {
            return (legalBalls / 6) + "." + (legalBalls % 6);
        }

        public double getRunRate() {
            return legalBalls == 0 ? 0.0 : (totalRuns * 6.0) / legalBalls;
        }
    }

    // ==========================================
    // 2. HARDWARE COORDINATOR
    // ==========================================
    public static class CaptureSessionCoordinator {
        private final ExecutorService workerPool = Executors.newFixedThreadPool(3);
        private Future<?> activeSessionFuture;

        public interface CaptureCallback {
            void onCountdownTick(int secondsRemaining);
            void onSuccess(List<Integer> batData, Path onField, Path sideField);
            void onFailure(String errorReason);
        }

        public synchronized void triggerCapture(int windowSeconds, int activeCameraCount, CaptureCallback callback) {
            if (activeSessionFuture != null && !activeSessionFuture.isDone()) {
                callback.onFailure("A capture session is currently running.");
                return;
            }

            activeSessionFuture = workerPool.submit(() -> {
                CountDownLatch captureLatch = new CountDownLatch(2);

                final List<Integer>[] batHolder = new List[1];
                final Path[] videoHolder = new Path[2];
                final String[] failureReason = new String[1];

                RecordingCoordinator.startAwaitingRecordings(activeCameraCount, (onField, sideField) -> {
                    if (onField != null) videoHolder[0] = onField;
                    if (sideField != null) videoHolder[1] = sideField;
                    captureLatch.countDown();
                });

                CountDownLatch batHandshakeLatch = new CountDownLatch(1);
                BatCaptureService.startCaptureSession(
                        batHandshakeLatch::countDown,
                        samples -> {
                            batHolder[0] = samples;
                            captureLatch.countDown();
                        },
                        err -> {
                            failureReason[0] = err;
                            captureLatch.countDown();
                        }
                );

                try {
                    boolean handshook = batHandshakeLatch.await(5, TimeUnit.SECONDS);
                    if (!handshook || failureReason[0] != null) {
                        RecordingCoordinator.reset();
                        callback.onFailure("Bat Handshake Failed: " + (failureReason[0] != null ? failureReason[0] : "Timeout on Port 5000"));
                        return;
                    }

                    CameraWebSocketHandler.broadcastCommand("START");

                    for (int s = windowSeconds; s > 0; s--) {
                        final int remaining = s;
                        SwingUtilities.invokeLater(() -> callback.onCountdownTick(remaining));
                        Thread.sleep(1000);
                    }

                    CameraWebSocketHandler.broadcastCommand("STOP");

                    captureLatch.await(10, TimeUnit.SECONDS);

                    boolean hasBat = (batHolder[0] != null);
                    boolean hasVideo = (videoHolder[0] != null || videoHolder[1] != null);

                    if (hasBat && hasVideo) {
                        SwingUtilities.invokeLater(() -> callback.onSuccess(batHolder[0], videoHolder[0], videoHolder[1]));
                    } else {
                        RecordingCoordinator.reset();
                        String detail = (!hasBat ? "Missing Bat Samples. " : "")
                                + (!hasVideo ? "No Camera Video Uploaded." : "");
                        SwingUtilities.invokeLater(() -> callback.onFailure("Ingestion Timeout: " + detail));
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    callback.onFailure("Hardware capture thread interrupted.");
                }
            });
        }
    }

    // ==========================================
    // 3. UI STATE & CUSTOM ENGINE
    // ==========================================
    private static final String SAVE_FILE = "match_session.ser";
    public MatchState match;
    private DeliveryState currentState = DeliveryState.IDLE;
    private final CaptureSessionCoordinator hardwareCoordinator = new CaptureSessionCoordinator();

    private List<Integer> latestBatSamples = null;
    private Path latestOnFieldVideo = null;
    private Path latestSideFieldVideo = null;

    private static final Color FIELD_BG = new Color(7, 18, 14);
    private static final Color PITCH_CARD = new Color(13, 26, 20);
    private static final Color MIDDLE_TILE_BG = new Color(18, 30, 44);
    private static final Color SCORECARD_GREEN = new Color(19, 78, 44);
    private static final Color DISABLED_BG = new Color(14, 22, 32);

    private static final Color NEON_GREEN = new Color(34, 197, 94);
    private static final Color NEON_GOLD = new Color(245, 158, 11);
    private static final Color NEON_ORANGE = new Color(249, 115, 22);
    private static final Color PULSE_ORANGE = new Color(234, 88, 12);

    private JLabel lblTeamScore, lblOvers, lblRunRate, lblTarget;
    private JLabel lblStrikerName, lblStrikerStats;
    private JLabel lblNonStrikerName, lblNonStrikerStats;
    private JLabel lblBowlerName, lblBowlerStats;
    private JLabel lblStatusNotice;
    private JLabel lblCameraStatus;

    private CustomStyledButton btnOpenScorecard, btnDrsReview, btnChangeBowler;
    private JPanel panelStageRunup, panelStageDecision, panelStageOutcome;
    private CustomStyledButton btnRunup, btnFairDelivery, btnCallWide;
    private final List<CustomStyledButton> fairActionButtons = new ArrayList<>();
    private final List<CustomStyledButton> wideActionButtons = new ArrayList<>();
    private final List<CustomStyledButton> editButtons = new ArrayList<>();

    private Timer blinkTimer;
    private boolean blinkState = false;

    public CricketScorecardApp() {
        super("Broadcaster Live Station - Stadium Console");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1366, 820);
        setMinimumSize(new Dimension(1100, 720));
        setLocationRelativeTo(null);

        initWorkflow();
        startBlinkEngine();
    }

    public static class CustomStyledButton extends JButton {
        private Color normalBg;
        private Color normalFg;
        private Color disabledBg;
        private Color disabledFg;
        private Color customBorder;

        public CustomStyledButton(String text, Color bg, Color fg, Color disBg, Color disFg) {
            super(text);
            this.normalBg = bg;
            this.normalFg = fg;
            this.disabledBg = disBg;
            this.disabledFg = disFg;
            this.customBorder = bg.brighter();
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR));
        }

        public void setCustomBackground(Color bg) { this.normalBg = bg; repaint(); }
        public void setCustomBorder(Color b) { this.customBorder = b; repaint(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            boolean active = isEnabled();
            Color fill = active ? normalBg : disabledBg;
            Color textCol = active ? normalFg : disabledFg;

            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);

            g2.setColor(active ? customBorder : new Color(30, 45, 60));
            g2.setStroke(new BasicStroke(active ? 1.5f : 1.0f));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);

            g2.setColor(textCol);
            FontMetrics fm = g2.getFontMetrics(getFont());
            g2.setFont(getFont());

            String t = getText();
            if (t.contains("<html>")) {
                super.paintComponent(g);
                g2.dispose();
                return;
            }

            int stringWidth = fm.stringWidth(t);
            int stringHeight = fm.getAscent();
            g2.drawString(t, (getWidth() - stringWidth) / 2, (getHeight() + stringHeight) / 2 - 2);

            g2.dispose();
        }
    }

    private void startBlinkEngine() {
        blinkTimer = new Timer(400, e -> {
            blinkState = !blinkState;

            // Camera Header
            int activeCams = CameraWebSocketHandler.getConnectedCameraCount();
            if (activeCams >= 2) {
                lblCameraStatus.setText("CAMERAS: 2 / 2 ACTIVE (DUAL)");
                lblCameraStatus.setForeground(NEON_GREEN);
            } else if (activeCams == 1) {
                lblCameraStatus.setText("CAMERAS: 1 / 2 ACTIVE (SINGLE)");
                lblCameraStatus.setForeground(NEON_ORANGE);
            } else {
                lblCameraStatus.setText("CAMERAS: 0 / 2 ACTIVE (OFFLINE)");
                lblCameraStatus.setForeground(new Color(239, 68, 68));
            }

            // Edit Buttons Shining Glow
            for (CustomStyledButton eb : editButtons) {
                eb.setCustomBorder(blinkState ? Color.WHITE : NEON_ORANGE);
            }

            // Stage 1 Orange Pulse
            if (currentState == DeliveryState.IDLE && btnRunup != null) {
                btnRunup.setCustomBackground(blinkState ? NEON_ORANGE : PULSE_ORANGE);
                btnRunup.setCustomBorder(blinkState ? Color.WHITE : NEON_ORANGE);
            } else if (btnRunup != null) {
                btnRunup.setCustomBackground(new Color(20, 80, 45));
                btnRunup.setCustomBorder(new Color(34, 197, 94));
            }

            // Stage 2 Delivery Locked Pulse
            if (currentState == DeliveryState.DELIVERY_LOCKED) {
                if (btnFairDelivery != null) {
                    btnFairDelivery.setCustomBackground(blinkState ? new Color(37, 99, 235) : new Color(29, 78, 216));
                    btnFairDelivery.setCustomBorder(blinkState ? Color.WHITE : new Color(59, 130, 246));
                }
                if (btnCallWide != null) {
                    btnCallWide.setCustomBackground(blinkState ? NEON_ORANGE : PULSE_ORANGE);
                    btnCallWide.setCustomBorder(blinkState ? Color.WHITE : NEON_ORANGE);
                }
                if (btnDrsReview != null) {
                    btnDrsReview.setCustomBackground(blinkState ? PULSE_ORANGE : MIDDLE_TILE_BG);
                    btnDrsReview.setCustomBorder(blinkState ? Color.WHITE : NEON_ORANGE);
                }
            } else {
                if (btnFairDelivery != null) {
                    btnFairDelivery.setCustomBackground(new Color(29, 78, 216));
                    btnFairDelivery.setCustomBorder(new Color(59, 130, 246));
                }
                if (btnCallWide != null) {
                    btnCallWide.setCustomBackground(new Color(180, 83, 9));
                    btnCallWide.setCustomBorder(new Color(245, 158, 11));
                }
                if (btnDrsReview != null) {
                    btnDrsReview.setCustomBackground(MIDDLE_TILE_BG);
                    btnDrsReview.setCustomBorder(new Color(40, 60, 85));
                }
            }

            // Stage 3 Selection Border
            if (currentState == DeliveryState.FAIR_SELECTED) {
                for (CustomStyledButton b : fairActionButtons) b.setCustomBorder(blinkState ? NEON_ORANGE : new Color(40, 60, 80));
            } else if (currentState == DeliveryState.WIDE_SELECTED) {
                for (CustomStyledButton b : wideActionButtons) b.setCustomBorder(blinkState ? NEON_ORANGE : new Color(180, 83, 9));
            }
        });
        blinkTimer.start();
    }

    private void initWorkflow() {
        File file = new File(SAVE_FILE);
        String[] options = file.exists() ? new String[]{"New Match", "Resume Old Match"} : new String[]{"New Match"};

        int choice = JOptionPane.showOptionDialog(
                this, "Select Match Session Mode:", "Cricket Console Setup",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]
        );

        if (choice == 1 && file.exists()) {
            loadMatchData();
            buildInterface();
        } else {
            showNewMatchDialog();
            buildInterface();
            promptInitialBowlerSelection();
            saveMatchData();
        }
    }

    private void showNewMatchDialog() {
        JTextField team1Field = new JTextField("ENGLAND");
        JTextField team2Field = new JTextField("INDIA");
        JTextField oversField = new JTextField("20");
        JComboBox<String> tossWinner = new JComboBox<>(new String[]{"Team 1", "Team 2"});
        JComboBox<String> decision = new JComboBox<>(new String[]{"Batting", "Bowling"});

        JPanel panel = new JPanel(new GridLayout(0, 1, 6, 6));
        panel.add(new JLabel("Team 1:")); panel.add(team1Field);
        panel.add(new JLabel("Team 2:")); panel.add(team2Field);
        panel.add(new JLabel("Overs:")); panel.add(oversField);
        panel.add(new JLabel("Toss Won By:")); panel.add(tossWinner);
        panel.add(new JLabel("Decision:")); panel.add(decision);

        int result = JOptionPane.showConfirmDialog(this, panel, "New Match Configuration", JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) System.exit(0);

        String t1 = team1Field.getText().trim().isEmpty() ? "ENGLAND" : team1Field.getText().trim().toUpperCase();
        String t2 = team2Field.getText().trim().isEmpty() ? "INDIA" : team2Field.getText().trim().toUpperCase();
        int overs = 20;
        try { overs = Integer.parseInt(oversField.getText().trim()); } catch (Exception ignored) {}

        boolean t1Toss = tossWinner.getSelectedIndex() == 0;
        boolean choosesBat = decision.getSelectedIndex() == 0;
        String batTeam = ((t1Toss && choosesBat) || (!t1Toss && !choosesBat)) ? t1 : t2;
        String bowlTeam = batTeam.equals(t1) ? t2 : t1;

        this.match = new MatchState(batTeam, bowlTeam, overs);
    }

    private void promptInitialBowlerSelection() {
        String[] bowlerNames = new String[match.bowlers.size()];
        for (int i = 0; i < match.bowlers.size(); i++) {
            bowlerNames[i] = (i + 1) + ". " + match.bowlers.get(i).name;
        }

        String choice = (String) JOptionPane.showInputDialog(
                this, "Choose opening bowler:", "Select Bowler",
                JOptionPane.QUESTION_MESSAGE, null, bowlerNames, bowlerNames[0]
        );

        if (choice != null) {
            match.currentBowlerIndex = Integer.parseInt(choice.split("\\.")[0]) - 1;
        }
    }

    private void buildInterface() {
        getContentPane().removeAll();
        setLayout(new BorderLayout(0, 6));
        getContentPane().setBackground(FIELD_BG);

        add(createBroadcastHeader(), BorderLayout.NORTH);
        add(createMiddleWorkstation(), BorderLayout.CENTER);
        add(createConsoleActionDock(), BorderLayout.SOUTH);

        refreshUI();
        updateProtocolLighting();
        revalidate();
        repaint();
    }

    private JPanel createBroadcastHeader() {
        JPanel header = new JPanel(new BorderLayout(14, 2));
        header.setBackground(FIELD_BG);
        header.setBorder(new EmptyBorder(12, 24, 6, 24));

        lblTeamScore = new JLabel("ENGLAND  0-0");
        lblTeamScore.setFont(new Font("Impact", Font.PLAIN, 56));
        lblTeamScore.setForeground(Color.WHITE);

        lblOvers = new JLabel("OVERS: 0.0 / 0", JLabel.RIGHT);
        lblOvers.setFont(new Font("Segoe UI", Font.BOLD, 30));
        lblOvers.setForeground(Color.WHITE);

        lblRunRate = new JLabel("CRR: 0.00");
        lblRunRate.setFont(new Font("Segoe UI", Font.BOLD, 18));
        lblRunRate.setForeground(NEON_GREEN);

        lblCameraStatus = new JLabel("CAMERAS: 0 / 2 ACTIVE", JLabel.CENTER);
        lblCameraStatus.setFont(new Font("Segoe UI", Font.BOLD, 14));
        lblCameraStatus.setForeground(new Color(239, 68, 68));

        lblTarget = new JLabel("", JLabel.RIGHT);
        lblTarget.setFont(new Font("Segoe UI", Font.BOLD, 18));
        lblTarget.setForeground(NEON_GOLD);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(lblTeamScore, BorderLayout.WEST);
        top.add(lblOvers, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(lblRunRate, BorderLayout.WEST);
        bottom.add(lblCameraStatus, BorderLayout.CENTER);
        bottom.add(lblTarget, BorderLayout.EAST);

        header.add(top, BorderLayout.NORTH);
        header.add(bottom, BorderLayout.SOUTH);
        return header;
    }

    private JPanel createMiddleWorkstation() {
        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setOpaque(false);
        center.setBorder(new EmptyBorder(4, 20, 4, 20));

        editButtons.clear();

        JPanel strip = new JPanel(new GridLayout(1, 3, 14, 0));
        strip.setOpaque(false);
        strip.setPreferredSize(new Dimension(0, 110));

        strip.add(createActorBox(true, false));
        strip.add(createActorBox(false, false));
        strip.add(createActorBox(false, true));

        center.add(strip, BorderLayout.NORTH);

        JPanel toolsGrid = new JPanel(new GridLayout(1, 3, 14, 0));
        toolsGrid.setOpaque(false);

        btnOpenScorecard = new CustomStyledButton(
                "<html><center><font size='+1'>📊</font><br/><b>OPEN FULL SCORECARD</b></center></html>",
                SCORECARD_GREEN, Color.WHITE, DISABLED_BG, new Color(148, 163, 184)
        );
        btnOpenScorecard.setFont(new Font("Segoe UI", Font.BOLD, 15));
        btnOpenScorecard.setCustomBorder(NEON_GREEN);
        btnOpenScorecard.addActionListener(e -> new BroadcastScorecardWindow(match, this::refreshUI).setVisible(true));

        btnDrsReview = new CustomStyledButton(
                "<html><center><font size='+1'>🎯</font><br/><b>DRS / THIRD UMPIRE REVIEW</b></center></html>",
                MIDDLE_TILE_BG, Color.WHITE, DISABLED_BG, new Color(148, 163, 184)
        );
        btnDrsReview.setFont(new Font("Segoe UI", Font.BOLD, 15));
        btnDrsReview.setCustomBorder(new Color(51, 65, 85));
        btnDrsReview.addActionListener(e -> {
            if (currentState == DeliveryState.DELIVERY_LOCKED) {
                DeliveryBundle bundle = new DeliveryBundle(latestBatSamples, latestOnFieldVideo, latestSideFieldVideo);
                new DeliveryAnalyzerWindow(bundle).setVisible(true);
            }
        });

        btnChangeBowler = new CustomStyledButton(
                "<html><center><font size='+1'>🔄</font><br/><b>CHANGE BOWLER</b></center></html>",
                MIDDLE_TILE_BG, Color.WHITE, DISABLED_BG, new Color(148, 163, 184)
        );
        btnChangeBowler.setFont(new Font("Segoe UI", Font.BOLD, 15));
        btnChangeBowler.setCustomBorder(new Color(51, 65, 85));
        btnChangeBowler.addActionListener(e -> promptBowlerSelectionManual());

        toolsGrid.add(btnOpenScorecard);
        toolsGrid.add(btnDrsReview);
        toolsGrid.add(btnChangeBowler);

        center.add(toolsGrid, BorderLayout.CENTER);
        return center;
    }

    private JPanel createActorBox(boolean isStriker, boolean isBowler) {
        JPanel card = new JPanel(new BorderLayout(6, 4));
        card.setBackground(PITCH_CARD);
        Color borderCol = isStriker ? NEON_GREEN : (isBowler ? NEON_GOLD : new Color(30, 65, 45));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderCol, isStriker || isBowler ? 2 : 1),
                new EmptyBorder(8, 12, 8, 12)
        ));

        String tagTitle = isBowler ? "ACTIVE BOWLER" : (isStriker ? "BATTER ON STRIKE (*)" : "NON-STRIKER");
        Color tagColor = isBowler ? NEON_GOLD : (isStriker ? NEON_GREEN : new Color(148, 163, 184));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);

        JLabel tag = new JLabel(tagTitle);
        tag.setFont(new Font("Segoe UI", Font.BOLD, 11));
        tag.setForeground(tagColor);
        top.add(tag, BorderLayout.WEST);

        // Inline Shining Orange Edit Button
        CustomStyledButton btnEdit = new CustomStyledButton("✏ Edit", new Color(180, 83, 9), Color.WHITE, DISABLED_BG, Color.GRAY);
        btnEdit.setFont(new Font("Segoe UI", Font.BOLD, 11));
        btnEdit.setPreferredSize(new Dimension(64, 22));
        btnEdit.setCustomBorder(NEON_ORANGE);
        btnEdit.addActionListener(e -> {
            if (isBowler) {
                promptRenamePlayer("Bowler", match.bowlers.get(match.currentBowlerIndex).name, newName -> {
                    match.bowlers.get(match.currentBowlerIndex).name = newName;
                });
            } else if (isStriker) {
                promptRenamePlayer("Striker", match.batsmen.get(match.strikerIndex).name, newName -> {
                    match.batsmen.get(match.strikerIndex).name = newName;
                });
            } else {
                promptRenamePlayer("Non-Striker", match.batsmen.get(match.nonStrikerIndex).name, newName -> {
                    match.batsmen.get(match.nonStrikerIndex).name = newName;
                });
            }
        });
        editButtons.add(btnEdit);
        top.add(btnEdit, BorderLayout.EAST);

        JLabel nameLbl = new JLabel("-");
        nameLbl.setFont(new Font("Segoe UI", Font.BOLD, 19));
        nameLbl.setForeground(Color.WHITE);

        JLabel statsLbl = new JLabel("-");
        statsLbl.setFont(new Font("Segoe UI", Font.BOLD, 13));
        statsLbl.setForeground(isStriker ? NEON_GREEN : (isBowler ? NEON_GOLD : new Color(148, 163, 184)));

        JPanel info = new JPanel(new GridLayout(2, 1, 2, 2));
        info.setOpaque(false);
        info.add(nameLbl);
        info.add(statsLbl);

        if (isBowler) {
            lblBowlerName = nameLbl;
            lblBowlerStats = statsLbl;
        } else if (isStriker) {
            lblStrikerName = nameLbl;
            lblStrikerStats = statsLbl;
        } else {
            lblNonStrikerName = nameLbl;
            lblNonStrikerStats = statsLbl;
        }

        card.add(top, BorderLayout.NORTH);
        card.add(info, BorderLayout.CENTER);
        return card;
    }

    private void promptRenamePlayer(String role, String currentName, java.util.function.Consumer<String> onApply) {
        String input = JOptionPane.showInputDialog(
                this,
                "Enter new name for " + role + ":",
                currentName
        );
        if (input != null && !input.trim().isEmpty()) {
            onApply.accept(input.trim().toUpperCase());
            saveMatchData();
            refreshUI();
        }
    }

    private JPanel createConsoleActionDock() {
        JPanel dock = new JPanel(new BorderLayout(0, 8));
        dock.setBackground(FIELD_BG);
        dock.setBorder(new EmptyBorder(4, 20, 14, 20));

        lblStatusNotice = new JLabel("STAGE 1: Awaiting Bowler. Click [⚡ BOWLER READY] to start.", JLabel.CENTER);
        lblStatusNotice.setFont(new Font("Segoe UI", Font.BOLD, 14));
        lblStatusNotice.setForeground(NEON_GREEN);
        dock.add(lblStatusNotice, BorderLayout.NORTH);

        JPanel stagesGrid = new JPanel(new GridLayout(1, 3, 14, 0));
        stagesGrid.setOpaque(false);
        stagesGrid.setPreferredSize(new Dimension(0, 110));

        // Stage 1
        panelStageRunup = createStagePanel("BOWLER RUN-UP");
        btnRunup = new CustomStyledButton("⚡ BOWLER READY", new Color(22, 101, 52), Color.WHITE, DISABLED_BG, new Color(148, 163, 184));
        btnRunup.setFont(new Font("Segoe UI", Font.BOLD, 15));
        btnRunup.addActionListener(e -> executeSynchronizedBallTrigger());
        panelStageRunup.add(btnRunup, BorderLayout.CENTER);

        // Stage 2
        panelStageDecision = createStagePanel("UMPIRE SIGNAL");
        JPanel decGrid = new JPanel(new GridLayout(2, 1, 4, 4));
        decGrid.setOpaque(false);

        btnFairDelivery = new CustomStyledButton("⚖ FAIR DELIVERY", new Color(29, 78, 216), Color.WHITE, DISABLED_BG, new Color(148, 163, 184));
        btnFairDelivery.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btnFairDelivery.addActionListener(e -> {
            currentState = DeliveryState.FAIR_SELECTED;
            updateProtocolLighting();
        });

        btnCallWide = new CustomStyledButton("⚖ CALL WIDE", new Color(180, 83, 9), Color.WHITE, DISABLED_BG, new Color(148, 163, 184));
        btnCallWide.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btnCallWide.addActionListener(e -> {
            currentState = DeliveryState.WIDE_SELECTED;
            updateProtocolLighting();
        });

        decGrid.add(btnFairDelivery);
        decGrid.add(btnCallWide);
        panelStageDecision.add(decGrid, BorderLayout.CENTER);

        // Stage 3
        panelStageOutcome = createStagePanel("DELIVERY OUTCOME");
        JPanel scoreGrid = new JPanel(new GridLayout(2, 6, 4, 4));
        scoreGrid.setOpaque(false);

        fairActionButtons.clear();
        wideActionButtons.clear();

        Color numBg = new Color(24, 38, 55);
        fairActionButtons.add(createActionButton("0", numBg, e -> executeDelivery(0, false, 0, false, "")));
        fairActionButtons.add(createActionButton("1", numBg, e -> executeDelivery(1, false, 0, false, "")));
        fairActionButtons.add(createActionButton("2", numBg, e -> executeDelivery(2, false, 0, false, "")));
        fairActionButtons.add(createActionButton("3", numBg, e -> executeDelivery(3, false, 0, false, "")));
        fairActionButtons.add(createActionButton("4 FOUR", new Color(5, 150, 105), e -> executeDelivery(4, false, 0, false, "")));
        fairActionButtons.add(createActionButton("6 SIX", new Color(37, 99, 235), e -> executeDelivery(6, false, 0, false, "")));
        fairActionButtons.add(createActionButton("WICKET", new Color(185, 28, 28), e -> promptWicketMethod()));

        Color wdBg = new Color(154, 52, 18);
        wideActionButtons.add(createActionButton("Wd (1r)", wdBg, e -> executeDelivery(0, true, 0, false, "")));
        wideActionButtons.add(createActionButton("Wd + 1", wdBg, e -> executeDelivery(0, true, 1, false, "")));
        wideActionButtons.add(createActionButton("Wd + 2", wdBg, e -> executeDelivery(0, true, 2, false, "")));
        wideActionButtons.add(createActionButton("Wd + 4", wdBg, e -> executeDelivery(0, true, 4, false, "")));

        for (CustomStyledButton b : fairActionButtons) scoreGrid.add(b);
        for (CustomStyledButton b : wideActionButtons) scoreGrid.add(b);

        panelStageOutcome.add(scoreGrid, BorderLayout.CENTER);

        stagesGrid.add(panelStageRunup);
        stagesGrid.add(panelStageDecision);
        stagesGrid.add(panelStageOutcome);

        dock.add(stagesGrid, BorderLayout.CENTER);
        return dock;
    }

    private CustomStyledButton createActionButton(String label, Color bg, java.awt.event.ActionListener al) {
        CustomStyledButton btn = new CustomStyledButton(label, bg, Color.WHITE, DISABLED_BG, new Color(148, 163, 184));
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.addActionListener(al);
        return btn;
    }

    private JPanel createStagePanel(String title) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(PITCH_CARD);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(30, 50, 40), 1),
                new EmptyBorder(6, 8, 6, 8)
        ));

        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
        titleLbl.setForeground(new Color(148, 163, 184));

        p.add(titleLbl, BorderLayout.NORTH);
        return p;
    }

    private void executeSynchronizedBallTrigger() {
        int activeCameras = CameraWebSocketHandler.getConnectedCameraCount();

        if (activeCameras == 0) {
            JOptionPane.showMessageDialog(this,
                    "No Camera Connected!\n\nConnect at least one mobile camera before starting.",
                    "No Cameras Available",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        currentState = DeliveryState.ARMING_HARDWARE;
        latestBatSamples = null;
        latestOnFieldVideo = null;
        latestSideFieldVideo = null;

        updateProtocolLighting();

        hardwareCoordinator.triggerCapture(10, activeCameras, new CaptureSessionCoordinator.CaptureCallback() {
            @Override
            public void onCountdownTick(int secondsRemaining) {
                currentState = DeliveryState.RECORDING;
                lblStatusNotice.setText("🔴 RECORDING & SAMPLING (" + secondsRemaining + "s remaining...)");
                lblStatusNotice.setForeground(new Color(239, 68, 68));
            }

            @Override
            public void onSuccess(List<Integer> batData, Path onField, Path sideField) {
                latestBatSamples = batData;
                latestOnFieldVideo = onField;
                latestSideFieldVideo = sideField;
                currentState = DeliveryState.DELIVERY_LOCKED;

                updateProtocolLighting();

                String feedsSummary = "";
                if (onField != null) feedsSummary += "• On-Field Video: " + onField.getFileName() + "\n";
                if (sideField != null) feedsSummary += "• Side-Field Video: " + sideField.getFileName() + "\n";

                JOptionPane.showMessageDialog(CricketScorecardApp.this,
                        "BALL DELIVERY CAPTURED & LOCKED!\n"
                                + "• Piezo Samples: " + batData.size() + "\n"
                                + feedsSummary + "\n"
                                + "DRS Review & Umpire Decision Unlocked.",
                        "Capture Synchronized", JOptionPane.INFORMATION_MESSAGE);
            }

            @Override
            public void onFailure(String errorReason) {
                currentState = DeliveryState.IDLE;
                updateProtocolLighting();
                JOptionPane.showMessageDialog(CricketScorecardApp.this,
                        "Hardware Synchronization Failed:\n" + errorReason,
                        "Hardware Timeout / Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void updateProtocolLighting() {
        switch (currentState) {
            case IDLE:
                lblStatusNotice.setText("STAGE 1: Awaiting Bowler. Click [⚡ BOWLER READY] to start.");
                lblStatusNotice.setForeground(NEON_GREEN);
                btnRunup.setEnabled(true);
                btnFairDelivery.setEnabled(false);
                btnCallWide.setEnabled(false);
                btnDrsReview.setEnabled(false);
                setButtonsActive(fairActionButtons, false);
                setButtonsActive(wideActionButtons, false);
                break;

            case ARMING_HARDWARE:
                lblStatusNotice.setText("TCP PORT 5000: Bat handshaking...");
                lblStatusNotice.setForeground(NEON_ORANGE);
                btnRunup.setEnabled(false);
                btnFairDelivery.setEnabled(false);
                btnCallWide.setEnabled(false);
                btnDrsReview.setEnabled(false);
                break;

            case RECORDING:
                btnRunup.setEnabled(false);
                btnFairDelivery.setEnabled(false);
                btnCallWide.setEnabled(false);
                btnDrsReview.setEnabled(false);
                break;

            case WAITING_INGESTION:
                lblStatusNotice.setText("⏳ STOP SENT: Ingesting video transfers...");
                lblStatusNotice.setForeground(NEON_GOLD);
                break;

            case DELIVERY_LOCKED:
                lblStatusNotice.setText("STAGE 2: Delivery Locked! Confirm Fair/Wide or request DRS Review.");
                lblStatusNotice.setForeground(NEON_ORANGE);
                btnRunup.setEnabled(false);
                btnFairDelivery.setEnabled(true);
                btnCallWide.setEnabled(true);
                btnDrsReview.setEnabled(true);
                setButtonsActive(fairActionButtons, false);
                setButtonsActive(wideActionButtons, false);
                break;

            case FAIR_SELECTED:
                lblStatusNotice.setText("STAGE 3: Fair Delivery Confirmed. Record Runs or Dismissal.");
                lblStatusNotice.setForeground(new Color(56, 189, 248));
                btnFairDelivery.setEnabled(false);
                btnCallWide.setEnabled(false);
                setButtonsActive(fairActionButtons, true);
                setButtonsActive(wideActionButtons, false);
                break;

            case WIDE_SELECTED:
                lblStatusNotice.setText("STAGE 3: Wide Conceded. Click extras scored.");
                lblStatusNotice.setForeground(NEON_ORANGE);
                btnFairDelivery.setEnabled(false);
                btnCallWide.setEnabled(false);
                setButtonsActive(fairActionButtons, false);
                setButtonsActive(wideActionButtons, true);
                break;
        }
        repaint();
    }

    private void setButtonsActive(List<CustomStyledButton> buttons, boolean enabled) {
        for (CustomStyledButton b : buttons) {
            b.setEnabled(enabled);
            b.repaint();
        }
    }

    private void promptWicketMethod() {
        String[] dismissals = {"bowled", "caught", "lbw", "run out", "stumped", "hit wicket"};
        String choice = (String) JOptionPane.showInputDialog(
                this, "Select Dismissal Method:", "Record Wicket",
                JOptionPane.QUESTION_MESSAGE, null, dismissals, dismissals[0]
        );
        if (choice != null) {
            executeDelivery(0, false, 0, true, choice);
        }
    }

    private void executeDelivery(int batterRuns, boolean isWide, int wideExtras, boolean isWicket, String dismissalType) {
        Batsman striker = match.batsmen.get(match.strikerIndex);
        Bowler currentBowler = match.bowlers.get(match.currentBowlerIndex);

        boolean overFinished = false;

        if (isWide) {
            int extraTotal = 1 + wideExtras;
            match.totalRuns += extraTotal;
            match.extrasConceded += extraTotal;
            currentBowler.runsConceded += extraTotal;
            if (wideExtras % 2 != 0) swapStrike();
        } else if (isWicket) {
            striker.balls++;
            striker.isOut = true;
            striker.dismissalText = dismissalType;

            match.totalWickets++;
            match.legalBalls++;
            currentBowler.ballsBowled++;

            if (!dismissalType.equalsIgnoreCase("run out")) {
                currentBowler.wicketsTaken++;
            }

            if (match.totalWickets < 10 && match.nextBatsmanNumber <= 11) {
                match.strikerIndex = match.nextBatsmanNumber - 1;
                match.nextBatsmanNumber++;
            }

            overFinished = (match.legalBalls % 6 == 0);
        } else {
            striker.balls++;
            striker.runs += batterRuns;
            if (batterRuns == 4) striker.fours++;
            if (batterRuns == 6) striker.sixes++;

            match.totalRuns += batterRuns;
            match.legalBalls++;

            currentBowler.ballsBowled++;
            currentBowler.runsConceded += batterRuns;

            boolean oddRuns = (batterRuns % 2 != 0);
            overFinished = (match.legalBalls % 6 == 0);

            if (oddRuns && !overFinished) {
                swapStrike();
            } else if (!oddRuns && overFinished) {
                swapStrike();
            }
        }

        currentState = DeliveryState.IDLE;
        latestBatSamples = null;
        latestOnFieldVideo = null;
        latestSideFieldVideo = null;

        saveMatchData();
        refreshUI();
        updateProtocolLighting();

        if (overFinished && match.totalWickets < 10 && match.legalBalls < match.totalOvers * 6) {
            promptBowlerSelectionMandatory();
        }

        checkMatchTransitions();
    }

    private void swapStrike() {
        int temp = match.strikerIndex;
        match.strikerIndex = match.nonStrikerIndex;
        match.nonStrikerIndex = temp;
    }

    private void promptBowlerSelectionMandatory() {
        match.lastBowlerIndex = match.currentBowlerIndex;

        List<String> available = new ArrayList<>();
        List<Integer> indexMap = new ArrayList<>();

        for (int i = 0; i < match.bowlers.size(); i++) {
            if (i != match.lastBowlerIndex) {
                Bowler b = match.bowlers.get(i);
                available.add((i + 1) + ". " + b.name + " (" + b.wicketsTaken + "/" + b.runsConceded + ")");
                indexMap.add(i);
            }
        }

        String[] options = available.toArray(new String[0]);
        String selected = (String) JOptionPane.showInputDialog(
                this,
                "OVER COMPLETED!\nSelect next bowler from opposite end:",
                "Mandatory Bowler Change",
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]
        );

        int chosenIdx = (selected != null) ? available.indexOf(selected) : 0;
        match.currentBowlerIndex = indexMap.get(chosenIdx);
        saveMatchData();
        refreshUI();
    }

    private void promptBowlerSelectionManual() {
        String[] options = new String[match.bowlers.size()];
        for (int i = 0; i < match.bowlers.size(); i++) {
            options[i] = (i + 1) + ". " + match.bowlers.get(i).name;
        }

        String sel = (String) JOptionPane.showInputDialog(
                this, "Choose bowler for this over:", "Manual Bowler Change",
                JOptionPane.QUESTION_MESSAGE, null, options, options[match.currentBowlerIndex]
        );

        if (sel != null) {
            int idx = Integer.parseInt(sel.split("\\.")[0]) - 1;
            if (idx == match.lastBowlerIndex && match.legalBalls % 6 == 0) {
                JOptionPane.showMessageDialog(this, "A bowler cannot bowl two consecutive overs.", "Rule Infraction", JOptionPane.ERROR_MESSAGE);
                return;
            }
            match.currentBowlerIndex = idx;
            saveMatchData();
            refreshUI();
        }
    }

    private void checkMatchTransitions() {
        int maxBalls = match.totalOvers * 6;
        boolean allOut = (match.totalWickets >= 10);
        boolean oversFinished = (match.legalBalls >= maxBalls);

        if (match.currentInnings == 1 && (allOut || oversFinished)) {
            match.target = match.totalRuns + 1;
            JOptionPane.showMessageDialog(
                    this, "End of 1st Innings!\n" + match.battingTeam + " posted " + match.totalRuns + "/" + match.totalWickets +
                            "\nTarget for " + match.bowlingTeam + ": " + match.target
            );

            String prevBat = match.battingTeam;
            match.battingTeam = match.bowlingTeam;
            match.bowlingTeam = prevBat;
            match.currentInnings = 2;

            match.totalRuns = 0;
            match.totalWickets = 0;
            match.legalBalls = 0;
            match.extrasConceded = 0;

            List<Batsman> newBatters = new ArrayList<>();
            for (Bowler b : match.bowlers) newBatters.add(new Batsman(b.name));

            List<Bowler> newBowlers = new ArrayList<>();
            for (Batsman bat : match.batsmen) newBowlers.add(new Bowler(bat.name));

            match.batsmen = newBatters;
            match.bowlers = newBowlers;

            match.strikerIndex = 0;
            match.nonStrikerIndex = 1;
            match.nextBatsmanNumber = 3;
            match.currentBowlerIndex = 0;
            match.lastBowlerIndex = -1;

            promptInitialBowlerSelection();
            saveMatchData();
            refreshUI();
        } else if (match.currentInnings == 2) {
            if (match.totalRuns >= match.target) {
                match.isMatchOver = true;
                match.matchSummary = match.battingTeam + " won by " + (10 - match.totalWickets) + " wickets!";
                JOptionPane.showMessageDialog(this, "MATCH CONCLUDED\n" + match.matchSummary);
            } else if (allOut || oversFinished) {
                match.isMatchOver = true;
                int diff = (match.target - 1) - match.totalRuns;
                match.matchSummary = diff == 0 ? "Match Tied!" : match.bowlingTeam + " won by " + diff + " runs!";
                JOptionPane.showMessageDialog(this, "MATCH CONCLUDED\n" + match.matchSummary);
            }
        }
    }

    private void refreshUI() {
        lblTeamScore.setText(match.battingTeam.toUpperCase() + "  " + match.totalRuns + "-" + match.totalWickets);
        lblOvers.setText("OVERS: " + match.getOversFormatted() + " / " + match.totalOvers);
        lblRunRate.setText(String.format("CRR: %.2f", match.getRunRate()));

        if (match.target != -1) {
            int remBalls = (match.totalOvers * 6) - match.legalBalls;
            lblTarget.setText("TARGET: " + match.target + "  (Need " + Math.max(0, match.target - match.totalRuns) + " off " + Math.max(0, remBalls) + "b)");
        } else {
            lblTarget.setText("");
        }

        if (match.strikerIndex < match.batsmen.size()) {
            Batsman s = match.batsmen.get(match.strikerIndex);
            lblStrikerName.setText(s.name.toUpperCase() + " *");
            lblStrikerStats.setText(s.runs + " (" + s.balls + "b) | 4s: " + s.fours + " 6s: " + s.sixes);
        }

        if (match.nonStrikerIndex < match.batsmen.size()) {
            Batsman ns = match.batsmen.get(match.nonStrikerIndex);
            lblNonStrikerName.setText(ns.name.toUpperCase());
            lblNonStrikerStats.setText(ns.runs + " (" + ns.balls + "b) | 4s: " + ns.fours + " 6s: " + ns.sixes);
        }

        if (match.currentBowlerIndex < match.bowlers.size()) {
            Bowler bw = match.bowlers.get(match.currentBowlerIndex);
            lblBowlerName.setText(bw.name.toUpperCase());
            lblBowlerStats.setText(bw.wicketsTaken + "-" + bw.runsConceded + "  (" + bw.getOversFormatted() + " ov) | Econ: " + String.format("%.2f", bw.getEconomy()));
        }
    }

    private void saveMatchData() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(SAVE_FILE))) {
            oos.writeObject(match);
        } catch (IOException ignored) {}
    }

    private void loadMatchData() {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(SAVE_FILE))) {
            this.match = (MatchState) ois.readObject();
        } catch (Exception e) {
            showNewMatchDialog();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new CricketScorecardApp().setVisible(true));
    }
}