package com.final_year_project.ultraedge.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class UltraEdgeAnalyzer extends JFrame {

    // ============================================================
    // NETWORK & CONSTANTS
    // ============================================================

    static final int TCP_PORT = 5000;
    static final int UDP_PORT = 4210;

    static final int SAMPLE_RATE = 20_000;
    static final int DURATION_SECONDS = 10;
    static final int EXPECTED_SAMPLES = SAMPLE_RATE * DURATION_SECONDS;
    static final int SAMPLES_PER_PACKET = 500;
    static final int HEADER_SIZE = 12;
    static final int PACKET_SAMPLE_BYTES = SAMPLES_PER_PACKET * 2;

    static final int FFT_SIZE = 2048;
    static final int FFT_STEP = FFT_SIZE / 4; // 75% overlap for crisp time resolution
    static final double MAX_FREQUENCY = 5000.0;

    // UI Palette
    static final Color BG_DARK = new Color(18, 21, 26);
    static final Color BG_CARD = new Color(26, 31, 38);
    static final Color GRID_COLOR = new Color(42, 50, 62);
    static final Color TEXT_MUTED = new Color(140, 150, 165);
    static final Color TEXT_BRIGHT = new Color(235, 240, 245);
    static final Color ACCENT_CYAN = new Color(0, 229, 255);
    static final Color ACCENT_GREEN = new Color(0, 230, 118);
    static final Color ACCENT_AMBER = new Color(255, 171, 0);

    // ============================================================
    // DATA & PANELS
    // ============================================================

    final List<Integer> samples = new ArrayList<>(EXPECTED_SAMPLES);

    JLabel connectionLabel;
    JLabel sampleLabel;
    JLabel dominantLabel;
    JLabel statusLabel;

    WaveformPanel waveformPanel;
    SpectrumPanel spectrumPanel;
    SpectrogramPanel spectrogramPanel;
    FrequencyTimePanel frequencyTimePanel;

    // Precomputed turbo/inferno-like color map for fast spectrogram generation
    static final int[] COLOR_LUT = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            double norm = i / 255.0;
            int r = (int) (Math.min(1.0, Math.max(0.0, 1.5 * norm - 0.2)) * 255);
            int g = (int) (Math.min(1.0, Math.max(0.0, 2.0 * norm - 0.6)) * 255);
            int b = (int) (Math.min(1.0, Math.max(0.0, norm < 0.4 ? norm * 2.5 : 1.0 - (norm - 0.4) * 2.0)) * 255);
            COLOR_LUT[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            UltraEdgeAnalyzer app = new UltraEdgeAnalyzer();
            app.setVisible(true);
            app.startUDPDiscoveryServer();
            app.startTCPServer();
        });
    }

    /**
     * Default constructor for standalone use or initialization.
     */
    public UltraEdgeAnalyzer() {
        setTitle("UltraEdge Signal Suite | 10-Second High-Precision Edge Analyzer");
        setSize(1450, 1050);
        setMinimumSize(new Dimension(1000, 800));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        getContentPane().setBackground(BG_DARK);
        setLayout(new BorderLayout(0, 8));

        // Setup Header Telemetry
        JPanel topHeader = new JPanel(new GridLayout(1, 4, 10, 0));
        topHeader.setBackground(BG_DARK);
        topHeader.setBorder(new EmptyBorder(12, 16, 4, 16));

        connectionLabel = createCard("HARDWARE LINK", "BAT: DISCONNECTED", ACCENT_AMBER);
        sampleLabel = createCard("BUFFER FILL", "0 / " + EXPECTED_SAMPLES, TEXT_BRIGHT);
        dominantLabel = createCard("DOMINANT RESONANCE", "-- Hz", ACCENT_CYAN);
        statusLabel = createCard("SYSTEM STATE", "Initializing...", ACCENT_GREEN);

        topHeader.add(connectionLabel.getParent());
        topHeader.add(sampleLabel.getParent());
        topHeader.add(dominantLabel.getParent());
        topHeader.add(statusLabel.getParent());
        add(topHeader, BorderLayout.NORTH);

        // Graphs Container
        waveformPanel = new WaveformPanel();
        spectrumPanel = new SpectrumPanel();
        spectrogramPanel = new SpectrogramPanel();
        frequencyTimePanel = new FrequencyTimePanel();

        JPanel graphsGrid = new JPanel(new GridLayout(4, 1, 0, 6));
        graphsGrid.setBackground(BG_DARK);
        graphsGrid.setBorder(new EmptyBorder(0, 16, 8, 16));

        graphsGrid.add(waveformPanel);
        graphsGrid.add(spectrumPanel);
        graphsGrid.add(spectrogramPanel);
        graphsGrid.add(frequencyTimePanel);

        add(graphsGrid, BorderLayout.CENTER);

        // Add the Synchronization Toolbar at the bottom
        addBottomActionToolbar();
    }

    /**
     * Overloaded constructor used by the DRS Review system to inspect locked delivery data.
     */
    public UltraEdgeAnalyzer(List<Integer> preloadedSamples) {
        this();
        // Do not terminate the whole application when closing this analyzer from DRS
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        if (preloadedSamples != null && !preloadedSamples.isEmpty()) {
            synchronized (samples) {
                samples.clear();
                samples.addAll(preloadedSamples);
            }
            connectionLabel.setText("BAT: LOCKED (ARCHIVE)");
            connectionLabel.setForeground(ACCENT_CYAN);
            sampleLabel.setText(samples.size() + " / " + EXPECTED_SAMPLES);
            statusLabel.setText("Synthesizing acoustic spectral transforms...");
            analyze();
        } else {
            statusLabel.setText("No sample payload delivered.");
        }
    }

    private void addBottomActionToolbar() {
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 10));
        bottomBar.setBackground(new Color(12, 15, 19));
        bottomBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, GRID_COLOR));

        JButton btnSynchronize = new JButton("🔄 SYNCHRONIZE WITH VIDEO");
        btnSynchronize.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btnSynchronize.setBackground(ACCENT_AMBER);
        btnSynchronize.setForeground(Color.BLACK);
        btnSynchronize.setFocusPainted(false);
        btnSynchronize.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnSynchronize.addActionListener(e -> {
            JOptionPane.showMessageDialog(this,
                    "AUDIO-VIDEO SYNCHRONIZATION MODULE\n\n"
                            + "Active Acoustic Samples: " + samples.size() + "\n"
                            + "Sampling Rate: 20,000 Hz (Window: 10.00s)\n\n"
                            + "Synchronized temporal scrub bar and multi-view video alignment will execute here.",
                    "Synchronization (Pending Implementation)",
                    JOptionPane.INFORMATION_MESSAGE);
        });

        bottomBar.add(btnSynchronize);
        add(bottomBar, BorderLayout.SOUTH);
    }

    private JLabel createCard(String title, String initialVal, Color accent) {
        JPanel card = new JPanel(new BorderLayout(0, 4));
        card.setBackground(BG_CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(GRID_COLOR, 1),
                new EmptyBorder(8, 12, 8, 12)
        ));

        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 10));
        titleLbl.setForeground(TEXT_MUTED);

        JLabel valueLbl = new JLabel(initialVal);
        valueLbl.setFont(new Font("Monospaced", Font.BOLD, 15));
        valueLbl.setForeground(accent);

        card.add(titleLbl, BorderLayout.NORTH);
        card.add(valueLbl, BorderLayout.CENTER);
        return valueLbl;
    }

    // ============================================================
    // NETWORKING (FOR STANDALONE TESTING)
    // ============================================================

    void startUDPDiscoveryServer() {
        Thread thread = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(UDP_PORT)) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("Waiting for ESP32 beacon..."));
                byte[] buffer = new byte[256];

                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String message = new String(packet.getData(), packet.getOffset(), packet.getLength()).trim();

                    if ("ULTRAEDGE_DISCOVER".equals(message)) {
                        byte[] responseData = "ULTRAEDGE_SERVER:5000".getBytes();
                        DatagramPacket responsePacket = new DatagramPacket(
                                responseData, responseData.length, packet.getAddress(), packet.getPort()
                        );
                        socket.send(responsePacket);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    void startTCPServer() {
        Thread thread = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(TCP_PORT)) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("Ready - waiting for BAT"));
                while (true) {
                    Socket socket = serverSocket.accept();
                    handleClient(socket);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    void handleClient(Socket socket) {
        new Thread(() -> {
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(150);

                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();

                String handshake = readLineWithTimeout(input);
                if (!"BAT_CONNECTED".equals(handshake)) {
                    socket.close();
                    return;
                }

                SwingUtilities.invokeLater(() -> {
                    connectionLabel.setText("BAT: LINKED");
                    connectionLabel.setForeground(ACCENT_GREEN);
                    statusLabel.setText("Sampling active (START sent)");
                });

                synchronized (samples) {
                    samples.clear();
                }
                waveformPanel.setData(null);
                spectrumPanel.clear();
                spectrogramPanel.clear();
                frequencyTimePanel.clear();

                output.write("START\n".getBytes());
                output.flush();

                long startTime = System.nanoTime();
                receiveBinaryData(input, startTime);

                int finalCount;
                synchronized (samples) {
                    if (samples.size() > EXPECTED_SAMPLES) {
                        samples.subList(EXPECTED_SAMPLES, samples.size()).clear();
                    }
                    finalCount = samples.size();
                }

                try {
                    output.write("STOP\n".getBytes());
                    output.flush();
                } catch (Exception ignored) {}
                socket.close();

                SwingUtilities.invokeLater(() -> {
                    connectionLabel.setText("BAT: IDLE");
                    connectionLabel.setForeground(TEXT_MUTED);
                    sampleLabel.setText(finalCount + " / " + EXPECTED_SAMPLES);
                    statusLabel.setText("Transforming signals...");
                });

                analyze();

            } catch (Exception e) {
                e.printStackTrace();
                try { socket.close(); } catch (Exception ignored) {}
                SwingUtilities.invokeLater(() -> {
                    connectionLabel.setText("BAT: DROPPED");
                    connectionLabel.setForeground(Color.RED);
                    statusLabel.setText("Error: " + e.getMessage());
                });
            }
        }).start();
    }

    static String readLineWithTimeout(InputStream input) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            try {
                int c = input.read();
                if (c == -1) throw new EOFException("ESP closed connection");
                if (c == '\n') break;
                if (c != '\r') sb.append((char) c);
            } catch (SocketTimeoutException ignored) {}
        }
        return sb.toString();
    }

    void receiveBinaryData(InputStream input, long startTime) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        byte[] sampleBytes = new byte[PACKET_SAMPLE_BYTES];
        int expectedSequence = 0;

        while (true) {
            double elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0;
            if (elapsed >= DURATION_SECONDS) break;

            try {
                if (!readFullyWithTimeout(input, header, 0, HEADER_SIZE)) continue;
            } catch (SocketTimeoutException e) {
                continue;
            }

            ByteBuffer headerBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            long timestamp = headerBuf.getLong();
            int sequence = headerBuf.getInt();

            if (sequence != expectedSequence) {
                System.out.println("Frame drop! Expected: " + expectedSequence + " Got: " + sequence);
            }
            expectedSequence = sequence + 1;

            readFullyAllowTimeout(input, sampleBytes, 0, sampleBytes.length);
            ByteBuffer sampleBuf = ByteBuffer.wrap(sampleBytes).order(ByteOrder.LITTLE_ENDIAN);

            int currentTotal;
            synchronized (samples) {
                for (int i = 0; i < SAMPLES_PER_PACKET; i++) {
                    if (samples.size() >= EXPECTED_SAMPLES) break;
                    samples.add((int) sampleBuf.getShort());
                }
                currentTotal = samples.size();
            }

            double signalTime = currentTotal / (double) SAMPLE_RATE;
            SwingUtilities.invokeLater(() -> {
                sampleLabel.setText(currentTotal + " / " + EXPECTED_SAMPLES);
                statusLabel.setText(String.format("Streaming: %.2fs / 10.00s", Math.min(signalTime, 10.0)));
            });
        }
    }

    static boolean readFullyWithTimeout(InputStream input, byte[] buffer, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            try {
                int n = input.read(buffer, offset + total, length - total);
                if (n == -1) throw new EOFException("Disconnected");
                total += n;
            } catch (SocketTimeoutException e) {
                if (total == 0) return false;
            }
        }
        return true;
    }

    static void readFullyAllowTimeout(InputStream input, byte[] buffer, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            try {
                int n = input.read(buffer, offset + total, length - total);
                if (n == -1) throw new EOFException("Disconnected");
                total += n;
            } catch (SocketTimeoutException ignored) {}
        }
    }

    // ============================================================
    // DSP & ANALYSIS
    // ============================================================

    void analyze() {
        int[] data;
        synchronized (samples) {
            data = samples.stream().mapToInt(Integer::intValue).toArray();
        }

        if (data.length < FFT_SIZE) {
            SwingUtilities.invokeLater(() -> statusLabel.setText("Buffer underrun: not enough samples"));
            return;
        }

        waveformPanel.setData(data);

        // Full-frame spectral peak
        FFTResult spectrumResult = performFFTWindow(data, 0);
        spectrumPanel.setData(spectrumResult.frequencies, spectrumResult.magnitudes);

        // Spectrogram & Dominant Tracking
        SpectrogramResult spectrogram = performSpectrogram(data);
        spectrogramPanel.setData(spectrogram.times, spectrogram.frequencies, spectrogram.magnitudes);

        FrequencyTimeResult freqTime = performFrequencyTimeAnalysis(data);
        frequencyTimePanel.setData(freqTime.times, freqTime.dominantFrequencies);

        double dominant = findOverallDominantFrequency(freqTime);
        SwingUtilities.invokeLater(() -> {
            dominantLabel.setText(String.format("%.1f Hz", dominant));
            statusLabel.setText(String.format("Ready | %d Frames Processed", freqTime.times.length));
        });
    }

    static class FFTResult {
        double[] frequencies;
        double[] magnitudes;
        double dominantFrequency;
        double dominantMagnitude;

        FFTResult(double[] f, double[] m, double df, double dm) {
            this.frequencies = f;
            this.magnitudes = m;
            this.dominantFrequency = df;
            this.dominantMagnitude = dm;
        }
    }

    FFTResult performFFTWindow(int[] data, int start) {
        double[] real = new double[FFT_SIZE];
        double[] imag = new double[FFT_SIZE];

        double mean = 0;
        for (int i = 0; i < FFT_SIZE; i++) {
            mean += data[start + i];
        }
        mean /= FFT_SIZE;

        for (int i = 0; i < FFT_SIZE; i++) {
            double hann = 0.5 * (1.0 - Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
            real[i] = (data[start + i] - mean) * hann;
        }

        fft(real, imag);

        int bins = FFT_SIZE / 2;
        double[] frequencies = new double[bins];
        double[] magnitudes = new double[bins];
        double maxMag = 0;
        int maxIndex = 1;

        for (int i = 0; i < bins; i++) {
            frequencies[i] = i * SAMPLE_RATE / (double) FFT_SIZE;
            magnitudes[i] = Math.sqrt(real[i] * real[i] + imag[i] * imag[i]);

            if (frequencies[i] >= 20 && frequencies[i] <= MAX_FREQUENCY && magnitudes[i] > maxMag) {
                maxMag = magnitudes[i];
                maxIndex = i;
            }
        }
        return new FFTResult(frequencies, magnitudes, frequencies[maxIndex], maxMag);
    }

    static class FrequencyTimeResult {
        double[] times;
        double[] dominantFrequencies;
        double[] magnitudes;

        FrequencyTimeResult(double[] t, double[] df, double[] m) {
            this.times = t;
            this.dominantFrequencies = df;
            this.magnitudes = m;
        }
    }

    FrequencyTimeResult performFrequencyTimeAnalysis(int[] data) {
        int windows = 1 + (data.length - FFT_SIZE) / FFT_STEP;
        double[] times = new double[windows];
        double[] freqs = new double[windows];
        double[] mags = new double[windows];

        for (int w = 0; w < windows; w++) {
            int start = w * FFT_STEP;
            FFTResult res = performFFTWindow(data, start);
            times[w] = (start + FFT_SIZE / 2.0) / SAMPLE_RATE;
            freqs[w] = res.dominantFrequency;
            mags[w] = res.dominantMagnitude;
        }
        return new FrequencyTimeResult(times, freqs, mags);
    }

    static class SpectrogramResult {
        double[] times;
        double[] frequencies;
        double[][] magnitudes;

        SpectrogramResult(double[] t, double[] f, double[][] m) {
            this.times = t;
            this.frequencies = f;
            this.magnitudes = m;
        }
    }

    SpectrogramResult performSpectrogram(int[] data) {
        int windows = 1 + (data.length - FFT_SIZE) / FFT_STEP;
        int bins = FFT_SIZE / 2;

        double[] times = new double[windows];
        double[] frequencies = new double[bins];
        double[][] magnitudes = new double[windows][bins];

        for (int i = 0; i < bins; i++) {
            frequencies[i] = i * SAMPLE_RATE / (double) FFT_SIZE;
        }

        double globalMax = 1e-6;
        for (int w = 0; w < windows; w++) {
            int start = w * FFT_STEP;
            FFTResult res = performFFTWindow(data, start);
            times[w] = (start + FFT_SIZE / 2.0) / SAMPLE_RATE;

            for (int f = 0; f < bins; f++) {
                magnitudes[w][f] = res.magnitudes[f];
                if (frequencies[f] <= MAX_FREQUENCY && magnitudes[w][f] > globalMax) {
                    globalMax = magnitudes[w][f];
                }
            }
        }

        for (int w = 0; w < windows; w++) {
            for (int f = 0; f < bins; f++) {
                double val = magnitudes[w][f] / globalMax;
                double db = 20.0 * Math.log10(Math.max(val, 1e-4));
                magnitudes[w][f] = Math.max(0.0, Math.min(1.0, (db + 80.0) / 80.0));
            }
        }
        return new SpectrogramResult(times, frequencies, magnitudes);
    }

    double findOverallDominantFrequency(FrequencyTimeResult result) {
        double largest = -1;
        double freq = 0;
        for (int i = 0; i < result.magnitudes.length; i++) {
            if (result.magnitudes[i] > largest) {
                largest = result.magnitudes[i];
                freq = result.dominantFrequencies[i];
            }
        }
        return freq;
    }

    static void fft(double[] real, double[] imag) {
        int n = real.length;
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                double tr = real[i]; real[i] = real[j]; real[j] = tr;
                double ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wReal = Math.cos(angle);
            double wImag = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double curR = 1.0, curI = 0.0;
                for (int k = 0; k < len / 2; k++) {
                    int even = i + k;
                    int odd = i + k + len / 2;
                    double oddR = real[odd] * curR - imag[odd] * curI;
                    double oddI = real[odd] * curI + imag[odd] * curR;
                    real[odd] = real[even] - oddR;
                    imag[odd] = imag[even] - oddI;
                    real[even] += oddR;
                    imag[even] += oddI;
                    double nR = curR * wReal - curI * wImag;
                    curI = curR * wImag + curI * wReal;
                    curR = nR;
                }
            }
        }
    }

    // ============================================================
    // BASE GRAPH PANEL
    // ============================================================

    abstract class BaseInstrumentPanel extends JPanel {
        final int marginLeft = 70;
        final int marginRight = 25;
        final int marginTop = 28;
        final int marginBottom = 30;

        BaseInstrumentPanel() {
            setBackground(BG_CARD);
            setBorder(BorderFactory.createLineBorder(GRID_COLOR, 1));
        }

        void prepareGraphics(Graphics2D g2) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        }

        void drawHeader(Graphics2D g2, String title, String subtitle) {
            g2.setFont(new Font("SansSerif", Font.BOLD, 12));
            g2.setColor(TEXT_BRIGHT);
            g2.drawString(title, marginLeft, 18);

            if (subtitle != null) {
                g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
                g2.setColor(TEXT_MUTED);
                g2.drawString(subtitle, getWidth() - marginRight - g2.getFontMetrics().stringWidth(subtitle), 18);
            }
        }

        void drawEmptyState(Graphics2D g2, String msg) {
            g2.setFont(new Font("Monospaced", Font.PLAIN, 13));
            g2.setColor(TEXT_MUTED);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
        }

        void drawTimeAxis(Graphics2D g2, int left, int y0, int gw) {
            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            for (int sec = 0; sec <= 10; sec++) {
                int x = left + (int) ((sec / 10.0) * gw);
                g2.setColor(GRID_COLOR);
                g2.drawLine(x, marginTop, x, y0);

                g2.setColor(TEXT_MUTED);
                g2.drawLine(x, y0, x, y0 + 4);
                g2.drawString(sec + "s", x - 6, y0 + 16);
            }
        }

        void drawFrequencyGrid(Graphics2D g2, int left, int top, int gw, int gh) {
            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            for (int f = 0; f <= 5000; f += 1000) {
                int y = top + gh - (int) ((f / 5000.0) * gh);
                g2.setColor(GRID_COLOR);
                g2.drawLine(left, y, left + gw, y);

                g2.setColor(TEXT_MUTED);
                g2.drawLine(left - 4, y, left, y);
                String label = (f == 0) ? "0" : (f / 1000) + "k";
                g2.drawString(label, left - 24, y + 4);
            }
        }
    }

    // ============================================================
    // 1. AMPLITUDE WAVEFORM PANEL
    // ============================================================

    class WaveformPanel extends BaseInstrumentPanel {
        int[] data;

        void setData(int[] data) {
            this.data = data;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            prepareGraphics(g2);

            drawHeader(g2, "TRANSIENT WAVEFORM (RAW AMPLITUDE)", "Range: [-32768, +32767]");

            int left = marginLeft;
            int top = marginTop;
            int gw = getWidth() - left - marginRight;
            int gh = getHeight() - top - marginBottom;

            if (data == null || data.length == 0) {
                drawEmptyState(g2, "AWAITING SENSOR ACQUISITION...");
                return;
            }

            drawTimeAxis(g2, left, top + gh, gw);

            int midY = top + gh / 2;
            g2.setColor(new Color(60, 75, 95));
            g2.drawLine(left, midY, left + gw, midY);

            int minVal = Integer.MAX_VALUE;
            int maxVal = Integer.MIN_VALUE;
            for (int v : data) {
                if (v < minVal) minVal = v;
                if (v > maxVal) maxVal = v;
            }
            int peakSpan = Math.max(Math.abs(minVal), Math.abs(maxVal));
            if (peakSpan == 0) peakSpan = 1;

            g2.setColor(ACCENT_CYAN);

            for (int x = 0; x < gw; x++) {
                int startIdx = (int) ((long) x * data.length / gw);
                int endIdx = (int) ((long) (x + 1) * data.length / gw);
                endIdx = Math.min(endIdx, data.length);

                int bucketMin = Integer.MAX_VALUE;
                int bucketMax = Integer.MIN_VALUE;

                for (int i = startIdx; i < endIdx; i++) {
                    int val = data[i];
                    if (val < bucketMin) bucketMin = val;
                    if (val > bucketMax) bucketMax = val;
                }

                if (bucketMin == Integer.MAX_VALUE) {
                    bucketMin = bucketMax = data[Math.min(startIdx, data.length - 1)];
                }

                int y1 = midY - (int) ((bucketMax / (double) peakSpan) * (gh / 2.0));
                int y2 = midY - (int) ((bucketMin / (double) peakSpan) * (gh / 2.0));

                g2.drawLine(left + x, y1, left + x, Math.max(y1 + 1, y2));
            }
        }
    }

    // ============================================================
    // 2. FFT SPECTRUM PANEL
    // ============================================================

    class SpectrumPanel extends BaseInstrumentPanel {
        double[] freqs;
        double[] mags;

        void setData(double[] f, double[] m) {
            this.freqs = f;
            this.mags = m;
            repaint();
        }

        void clear() {
            this.freqs = null;
            this.mags = null;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            prepareGraphics(g2);

            drawHeader(g2, "PRIMARY FFT SPECTRUM DENSITY", "Resolution: " + String.format("%.2f Hz", SAMPLE_RATE / (double) FFT_SIZE));

            int left = marginLeft;
            int top = marginTop;
            int gw = getWidth() - left - marginRight;
            int gh = getHeight() - top - marginBottom;

            if (freqs == null || mags == null) {
                drawEmptyState(g2, "AWAITING SPECTRUM CALCULATION...");
                return;
            }

            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            for (int f = 0; f <= 5000; f += 1000) {
                int x = left + (int) ((f / 5000.0) * gw);
                g2.setColor(GRID_COLOR);
                g2.drawLine(x, top, x, top + gh);
                g2.setColor(TEXT_MUTED);
                g2.drawString(f + "Hz", x - 12, top + gh + 16);
            }

            double peak = 1e-3;
            for (int i = 0; i < freqs.length; i++) {
                if (freqs[i] <= MAX_FREQUENCY && mags[i] > peak) {
                    peak = mags[i];
                }
            }

            Path2D.Double path = new Path2D.Double();
            path.moveTo(left, top + gh);

            for (int i = 0; i < freqs.length; i++) {
                if (freqs[i] > MAX_FREQUENCY) break;
                double px = left + (freqs[i] / MAX_FREQUENCY) * gw;
                double py = top + gh - (mags[i] / peak) * (gh - 10);
                path.lineTo(px, py);
            }
            path.lineTo(left + gw, top + gh);
            path.closePath();

            g2.setPaint(new GradientPaint(0, top, new Color(0, 229, 255, 110), 0, top + gh, new Color(0, 229, 255, 10)));
            g2.fill(path);

            g2.setPaint(ACCENT_CYAN);
            g2.setStroke(new BasicStroke(1.5f));
            for (int i = 0; i < freqs.length - 1; i++) {
                if (freqs[i + 1] > MAX_FREQUENCY) break;
                int x1 = left + (int) ((freqs[i] / MAX_FREQUENCY) * gw);
                int y1 = top + gh - (int) ((mags[i] / peak) * (gh - 10));
                int x2 = left + (int) ((freqs[i + 1] / MAX_FREQUENCY) * gw);
                int y2 = top + gh - (int) ((mags[i + 1] / peak) * (gh - 10));
                g2.drawLine(x1, y1, x2, y2);
            }
        }
    }

    // ============================================================
    // 3. ENHANCED SPECTROGRAM PANEL
    // ============================================================

    class SpectrogramPanel extends BaseInstrumentPanel {
        BufferedImage buffer;

        void setData(double[] times, double[] freqs, double[][] magnitudes) {
            int w = times.length;
            int h = 250;

            buffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    double freq = MAX_FREQUENCY * (h - 1 - y) / (double) (h - 1);
                    int binIdx = (int) (freq / SAMPLE_RATE * FFT_SIZE);
                    binIdx = Math.max(0, Math.min(magnitudes[x].length - 1, binIdx));

                    double normalized = magnitudes[x][binIdx];
                    int lutIndex = (int) (normalized * 255.0);
                    buffer.setRGB(x, y, COLOR_LUT[Math.max(0, Math.min(255, lutIndex))]);
                }
            }
            repaint();
        }

        void clear() {
            buffer = null;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            prepareGraphics(g2);

            drawHeader(g2, "TIME-FREQUENCY SPECTROGRAM", "Intensity: [-80dB, 0dB] Normalized");

            int left = marginLeft;
            int top = marginTop;
            int gw = getWidth() - left - marginRight;
            int gh = getHeight() - top - marginBottom;

            if (buffer == null) {
                drawEmptyState(g2, "AWAITING SPECTROGRAM SYNTHESIS...");
                return;
            }

            g2.drawImage(buffer, left, top, gw, gh, null);
            drawFrequencyGrid(g2, left, top, gw, gh);
            drawTimeAxis(g2, left, top + gh, gw);
        }
    }

    // ============================================================
    // 4. DOMINANT RESONANCE TRACKER PANEL
    // ============================================================

    class FrequencyTimePanel extends BaseInstrumentPanel {
        double[] times;
        double[] freqs;

        void setData(double[] t, double[] f) {
            this.times = t;
            this.freqs = f;
            repaint();
        }

        void clear() {
            this.times = null;
            this.freqs = null;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            prepareGraphics(g2);

            drawHeader(g2, "PEAK RESONANCE FREQUENCY TRACKER", "Tracking window: 2048 (75% overlap)");

            int left = marginLeft;
            int top = marginTop;
            int gw = getWidth() - left - marginRight;
            int gh = getHeight() - top - marginBottom;

            if (times == null || freqs == null || times.length == 0) {
                drawEmptyState(g2, "AWAITING FREQUENCY ESTIMATION...");
                return;
            }

            drawFrequencyGrid(g2, left, top, gw, gh);
            drawTimeAxis(g2, left, top + gh, gw);

            g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(ACCENT_AMBER);

            int prevX = -1, prevY = -1;
            for (int i = 0; i < times.length; i++) {
                double t = times[i];
                double f = Math.max(0, Math.min(MAX_FREQUENCY, freqs[i]));

                int x = left + (int) ((t / 10.0) * gw);
                int y = top + gh - (int) ((f / MAX_FREQUENCY) * gh);

                if (prevX != -1) {
                    g2.drawLine(prevX, prevY, x, y);
                }
                g2.fillRect(x - 1, y - 1, 3, 3);
                prevX = x;
                prevY = y;
            }
        }
    }
}