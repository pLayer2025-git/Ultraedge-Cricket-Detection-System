package com.final_year_project.ultraedge.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;

public class VideoPlayerViewWindow extends JFrame {

    // Configure your Python binary path:
    // Use "python" / "python3" if in system PATH, or specify direct venv path:
    // e.g. "C:\\your_project\\venv\\Scripts\\python.exe"
    private static final String PYTHON_EXECUTABLE = "python";
    private static final String TRACKING_SCRIPT_PATH = "track_ball.py";

    public VideoPlayerViewWindow(String cameraRole, Path videoPath) {
        super("Hawk-Eye DRS Review - " + cameraRole);
        setSize(920, 640);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        getContentPane().setBackground(new Color(11, 19, 30));
        setLayout(new BorderLayout(0, 10));

        boolean fileExists = (videoPath != null && videoPath.toFile().exists());

        // 1. Header Bar
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(new Color(6, 11, 19));
        topBar.setBorder(new EmptyBorder(16, 20, 16, 20));

        JLabel title = new JLabel(cameraRole.toUpperCase() + " FOOTAGE REVIEW");
        title.setFont(new Font("Segoe UI", Font.BOLD, 17));
        title.setForeground(Color.WHITE);

        String pathText = fileExists ? videoPath.getFileName().toString() : "No Recording Loaded";
        JLabel fileLabel = new JLabel("File: " + pathText, JLabel.RIGHT);
        fileLabel.setFont(new Font("Monospaced", Font.BOLD, 13));
        fileLabel.setForeground(fileExists ? new Color(56, 189, 248) : new Color(239, 68, 68));

        topBar.add(title, BorderLayout.WEST);
        topBar.add(fileLabel, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        // 2. Video Canvas
        JPanel videoCanvas = new JPanel(new BorderLayout());
        videoCanvas.setBackground(Color.BLACK);
        videoCanvas.setBorder(BorderFactory.createLineBorder(new Color(30, 48, 70), 2));

        String absPath = fileExists ? videoPath.toAbsolutePath().toString() : "Footage offline / not captured";
        String noticeHtml = fileExists ?
                "<html><center>"
                        + "<font color='#38bdf8' size='+1'><b>🎥 " + cameraRole.toUpperCase() + "</b></font><br/><br/>"
                        + "<font color='#94a3b8' size='-1'>Local Path: " + absPath + "</font><br/><br/>"
                        + "<font color='#e2e8f0'>Click <b>'Launch in Media Player'</b> to view instant replay,<br/>"
                        + "or click <b>'⚡ BALL TRACKING'</b> to execute trajectory AI projection.</font>"
                        + "</center></html>" :
                "<html><center>"
                        + "<font color='#ef4444' size='+1'><b>⚠️ FEED OFFLINE</b></font><br/><br/>"
                        + "<font color='#94a3b8'>No recording was received for " + cameraRole + " during this ball.</font>"
                        + "</center></html>";

        JLabel videoNotice = new JLabel(noticeHtml, JLabel.CENTER);
        videoNotice.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        videoCanvas.add(videoNotice, BorderLayout.CENTER);
        add(videoCanvas, BorderLayout.CENTER);

        // 3. Action Buttons Toolbar
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 12));
        bottomBar.setBackground(new Color(6, 11, 19));

        JButton btnPlayExt = createCustomActionButton(
                "▶ Launch in Media Player",
                fileExists ? new Color(30, 64, 175) : new Color(20, 30, 45),
                fileExists,
                e -> {
                    if (videoPath != null && videoPath.toFile().exists()) {
                        try {
                            Desktop.getDesktop().open(videoPath.toFile());
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(this, "Could not open media player:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }
        );

        // Ball Tracking: Passes the video path and camera role to Python
        JButton btnBallTracking = createCustomActionButton(
                "⚡ BALL TRACKING",
                fileExists ? new Color(234, 88, 12) : new Color(30, 45, 65),
                fileExists,
                e -> launchPythonBallTracking(cameraRole, videoPath.toFile().getAbsolutePath())
        );

        bottomBar.add(btnPlayExt);
        bottomBar.add(btnBallTracking);
        add(bottomBar, BorderLayout.SOUTH);
    }

    /**
     * Executes the Python script in a background worker thread.
     */
    private void launchPythonBallTracking(String cameraRole, String videoAbsolutePath) {
        JDialog progressDialog = new JDialog(this, "Hawk-Eye AI Trajectory Engine", true);
        progressDialog.setLayout(new BorderLayout());
        progressDialog.getContentPane().setBackground(new Color(11, 19, 30));

        JLabel statusLabel = new JLabel("<html><center><font color='#f97316'><b>DISPATCHING TO PYTHON AI MODEL</b></font><br/><br/>"
                + "<font color='#94a3b8'>Processing trajectory on: " + cameraRole + "<br/>Please wait...</font></center></html>", JLabel.CENTER);
        statusLabel.setBorder(new EmptyBorder(25, 25, 25, 25));

        JProgressBar pBar = new JProgressBar();
        pBar.setIndeterminate(true);
        pBar.setBackground(new Color(18, 30, 46));
        pBar.setForeground(new Color(234, 88, 12));

        progressDialog.add(statusLabel, BorderLayout.CENTER);
        progressDialog.add(pBar, BorderLayout.SOUTH);
        progressDialog.setSize(480, 150);
        progressDialog.setLocationRelativeTo(this);

        SwingWorker<Integer, Void> worker = new SwingWorker<>() {
            private String consoleOutput = "";

            @Override
            protected Integer doInBackground() throws Exception {
                ProcessBuilder pb = new ProcessBuilder(
                        PYTHON_EXECUTABLE,
                        TRACKING_SCRIPT_PATH,
                        cameraRole,
                        videoAbsolutePath
                );
                pb.redirectErrorStream(true);

                Process process = pb.start();
                StringBuilder sb = new StringBuilder();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                }

                consoleOutput = sb.toString();
                return process.waitFor();
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                try {
                    int exitCode = get();
                    if (exitCode == 0) {
                        JOptionPane.showMessageDialog(
                                VideoPlayerViewWindow.this,
                                "AI Processing Completed Successfully!\n\nPython Log:\n" + consoleOutput,
                                "Ball Tracking Success",
                                JOptionPane.INFORMATION_MESSAGE
                        );
                    } else {
                        JOptionPane.showMessageDialog(
                                VideoPlayerViewWindow.this,
                                "Python processing exited with code: " + exitCode + "\n\n" + consoleOutput,
                                "AI Model Error",
                                JOptionPane.ERROR_MESSAGE
                        );
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(
                            VideoPlayerViewWindow.this,
                            "Failed to invoke Python environment:\n" + ex.getMessage(),
                            "Execution Error",
                            JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        };

        worker.execute();
        progressDialog.setVisible(true);
    }

    private JButton createCustomActionButton(String text, Color bg, boolean isAvailable, java.awt.event.ActionListener al) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isAvailable ? bg : new Color(20, 30, 42));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);

                g2.setColor(isAvailable ? bg.brighter() : new Color(35, 48, 65));
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

        btn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btn.setPreferredSize(new Dimension(200, 38));
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setCursor(new Cursor(isAvailable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        if (isAvailable) {
            btn.addActionListener(al);
        }
        return btn;
    }
}