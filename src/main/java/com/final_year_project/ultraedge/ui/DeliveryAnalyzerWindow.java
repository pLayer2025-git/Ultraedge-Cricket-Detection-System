package com.final_year_project.ultraedge.ui;

import com.final_year_project.ultraedge.model.DeliveryBundle;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;

public class DeliveryAnalyzerWindow extends JFrame {

    private final DeliveryBundle bundle;

    public DeliveryAnalyzerWindow(DeliveryBundle bundle) {
        super("Hawk-Eye Innovations & DRS Decision Review Console");
        this.bundle = bundle;

        setSize(1080, 710);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        getContentPane().setBackground(new Color(11, 19, 30));
        setLayout(new BorderLayout(0, 10));

        // Detect Stream Health
        int sampleCount = (bundle != null && bundle.getAudioSamples() != null) ? bundle.getAudioSamples().size() : 0;
        boolean hasBat = (sampleCount > 0);
        boolean hasOnField = (bundle != null && bundle.getOnFieldVideo() != null && bundle.getOnFieldVideo().toFile().exists());
        boolean hasSideField = (bundle != null && bundle.getSideFieldVideo() != null && bundle.getSideFieldVideo().toFile().exists());

        int activeStreams = (hasBat ? 1 : 0) + (hasOnField ? 1 : 0) + (hasSideField ? 1 : 0);

        // 1. Header Bar
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(6, 11, 19));
        header.setBorder(new EmptyBorder(14, 24, 14, 24));

        JLabel title = new JLabel("DRS THIRD UMPIRE REVIEW SUITE");
        title.setFont(new Font("Segoe UI", Font.BOLD, 18));
        title.setForeground(Color.WHITE);

        JLabel status = new JLabel("● " + activeStreams + " / 3 STREAMS LOCKED (" + sampleCount + " samples)", JLabel.RIGHT);
        status.setFont(new Font("Segoe UI", Font.BOLD, 14));
        status.setForeground(activeStreams >= 3 ? new Color(34, 197, 94) : new Color(249, 115, 22));

        header.add(title, BorderLayout.WEST);
        header.add(status, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        // 2. Existing Cards Grid (Untouched functionality)
        JPanel cardsGrid = new JPanel(new GridLayout(1, 3, 16, 0));
        cardsGrid.setBackground(new Color(11, 19, 30));
        cardsGrid.setBorder(new EmptyBorder(16, 20, 10, 20));

        // Card 1: UltraEdge
        cardsGrid.add(createCard(
                "ULTRAEDGE / SNICKO",
                "Acoustic transient frequency response, FFT harmonics, thermal spectrogram, and bat resonance tracker.",
                hasBat ? "🔍 OPEN ULTRAEDGE SUITE" : "⚠️ NO SENSOR SAMPLES",
                hasBat,
                new Color(14, 116, 144),
                e -> new UltraEdgeAnalyzer(bundle != null ? bundle.getAudioSamples() : null).setVisible(true)
        ));

        // Card 2: On-Field
        cardsGrid.add(createCard(
                "ON-FIELD CAMERA",
                "Straight angle from bowler's end for trajectory projection, LBW impact line, and pitch path analysis.",
                hasOnField ? "📹 OPEN ON-FIELD FEED" : "⚠️ FEED OFFLINE",
                hasOnField,
                new Color(30, 64, 175),
                e -> new VideoPlayerViewWindow("On-Field Camera", bundle != null ? bundle.getOnFieldVideo() : null).setVisible(true)
        ));

        // Card 3: Side-Field
        cardsGrid.add(createCard(
                "SIDE-FIELD CAMERA",
                "Side elevation view for bowler popping crease no-ball check, edge alignment, and bat impact height.",
                hasSideField ? "📹 OPEN SIDE-FIELD FEED" : "⚠️ FEED OFFLINE",
                hasSideField,
                new Color(194, 65, 12),
                e -> new VideoPlayerViewWindow("Side-Field Camera", bundle != null ? bundle.getSideFieldVideo() : null).setVisible(true)
        ));

        add(cardsGrid, BorderLayout.CENTER);

        // 3. New Bottom Synchronize & Analyze Toolbar
        boolean canAnalyze = hasBat && (hasSideField || hasOnField);
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 14));
        bottomBar.setBackground(new Color(6, 11, 19));
        bottomBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(30, 48, 70)));

        JButton btnAnalyzeDelivery = new JButton("⚡ SYNCHRONIZE & COMBINE DELIVERY (ULTRAEDGE + VIDEO)") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(canAnalyze ? new Color(217, 119, 6) : new Color(30, 40, 55));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);

                g2.setColor(canAnalyze ? new Color(251, 191, 36) : new Color(45, 55, 70));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);

                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(canAnalyze ? Color.WHITE : new Color(100, 116, 139));
                int x = (getWidth() - fm.stringWidth(getText())) / 2;
                int y = (getHeight() + fm.getAscent()) / 2 - 2;
                g2.drawString(getText(), x, y);
                g2.dispose();
            }
        };

        btnAnalyzeDelivery.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btnAnalyzeDelivery.setPreferredSize(new Dimension(540, 44));
        btnAnalyzeDelivery.setFocusPainted(false);
        btnAnalyzeDelivery.setContentAreaFilled(false);
        btnAnalyzeDelivery.setBorderPainted(false);
        btnAnalyzeDelivery.setCursor(new Cursor(canAnalyze ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));

        if (canAnalyze) {
            btnAnalyzeDelivery.addActionListener(e -> executeDeliveryAnalysis(hasSideField, hasOnField));
        }

        bottomBar.add(btnAnalyzeDelivery);
        add(bottomBar, BorderLayout.SOUTH);
    }

    /**
     * Lets the umpire select which camera angle to sync (defaulting to Side-Field for edge detection),
     * runs the FFmpeg pipeline, and opens VideoPlayerViewWindow with the synchronized output.
     */
    private void executeDeliveryAnalysis(boolean hasSideField, boolean hasOnField) {
        Path targetVideoPath;
        String cameraLabel;

        if (hasSideField && hasOnField) {
            String[] options = {"Side-Field (Side-On Bat View - Recommended)", "On-Field (Straight Pitch View)"};
            int choice = JOptionPane.showOptionDialog(
                    this,
                    "Select which camera view to synchronize with the acoustic sensor:",
                    "Choose Camera Angle",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    options,
                    options[0]
            );

            if (choice == 0) {
                targetVideoPath = bundle.getSideFieldVideo();
                cameraLabel = "Side-Field Camera";
            } else if (choice == 1) {
                targetVideoPath = bundle.getOnFieldVideo();
                cameraLabel = "On-Field Camera";
            } else {
                return;
            }
        } else if (hasSideField) {
            targetVideoPath = bundle.getSideFieldVideo();
            cameraLabel = "Side-Field Camera";
        } else {
            targetVideoPath = bundle.getOnFieldVideo();
            cameraLabel = "On-Field Camera";
        }

        File inputFile = targetVideoPath.toFile();
        File outputFile = new File(inputFile.getParentFile(), "synced_ultraedge_" + inputFile.getName());

        JDialog progressDialog = new JDialog(this, "Rendering Combined Replay...", true);
        progressDialog.setLayout(new BorderLayout());
        progressDialog.getContentPane().setBackground(new Color(11, 19, 30));

        JLabel infoLbl = new JLabel("<html><center><font color='#38bdf8'><b>SYNCHRONIZING " + cameraLabel.toUpperCase() + "</b></font><br/><br/>"
                + "<font color='#94a3b8'>Running frame-by-frame spectrum synthesis via FFmpeg...<br/>Please wait.</font></center></html>", JLabel.CENTER);
        infoLbl.setBorder(new EmptyBorder(25, 25, 25, 25));

        JProgressBar pBar = new JProgressBar();
        pBar.setIndeterminate(true);
        pBar.setBackground(new Color(18, 30, 46));
        pBar.setForeground(new Color(217, 119, 6));

        progressDialog.add(infoLbl, BorderLayout.CENTER);
        progressDialog.add(pBar, BorderLayout.SOUTH);
        progressDialog.setSize(480, 160);
        progressDialog.setLocationRelativeTo(this);

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                UltraEdgeAnalyzer analyzer = new UltraEdgeAnalyzer(bundle.getAudioSamples());
                CliVideoSynchronizer.processAndSync(
                        inputFile.getAbsolutePath(),
                        outputFile.getAbsolutePath(),
                        analyzer
                );
                return null;
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                try {
                    get();
                    // Open the video player directly with the newly rendered video
                    SwingUtilities.invokeLater(() -> {
                        VideoPlayerViewWindow player = new VideoPlayerViewWindow(
                                cameraLabel + " (Synced UltraEdge)",
                                outputFile.toPath()
                        );
                        player.setVisible(true);
                    });
                } catch (Exception ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(
                            DeliveryAnalyzerWindow.this,
                            "Failed to generate combined replay:\n" + ex.getMessage(),
                            "Synchronization Error",
                            JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        };

        worker.execute();
        progressDialog.setVisible(true);
    }

    private JPanel createCard(String title, String desc, String btnText, boolean isAvailable, Color btnColor, java.awt.event.ActionListener onLaunch) {
        JPanel card = new JPanel(new BorderLayout(0, 10));
        card.setBackground(new Color(18, 30, 46));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(isAvailable ? new Color(40, 65, 95) : new Color(35, 45, 55), 1),
                new EmptyBorder(16, 16, 16, 16)
        ));

        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(new Font("Segoe UI", Font.BOLD, 15));
        titleLbl.setForeground(isAvailable ? new Color(56, 189, 248) : new Color(148, 163, 184));

        JTextArea descArea = new JTextArea(desc);
        descArea.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        descArea.setForeground(isAvailable ? new Color(203, 213, 225) : new Color(100, 116, 139));
        descArea.setWrapStyleWord(true);
        descArea.setLineWrap(true);
        descArea.setOpaque(false);
        descArea.setEditable(false);
        descArea.setFocusable(false);

        JButton launchBtn = new JButton(btnText) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isAvailable ? btnColor : new Color(24, 34, 48));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);

                g2.setColor(isAvailable ? btnColor.brighter() : new Color(40, 50, 65));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);

                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(isAvailable ? Color.WHITE : new Color(100, 116, 139));
                int x = (getWidth() - fm.stringWidth(getText())) / 2;
                int y = (getHeight() + fm.getAscent()) / 2 - 2;
                g2.drawString(getText(), x, y);
                g2.dispose();
            }
        };

        launchBtn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        launchBtn.setFocusPainted(false);
        launchBtn.setContentAreaFilled(false);
        launchBtn.setBorderPainted(false);
        launchBtn.setPreferredSize(new Dimension(0, 42));
        launchBtn.setCursor(new Cursor(isAvailable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));

        if (isAvailable) {
            launchBtn.addActionListener(onLaunch);
        }

        card.add(titleLbl, BorderLayout.NORTH);
        card.add(descArea, BorderLayout.CENTER);
        card.add(launchBtn, BorderLayout.SOUTH);
        return card;
    }
}