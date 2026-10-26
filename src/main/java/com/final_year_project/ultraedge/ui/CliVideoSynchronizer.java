package com.final_year_project.ultraedge.ui;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.*;

public class CliVideoSynchronizer {

    /**
     * Reads the video frame-by-frame via FFmpeg stdout, renders the corresponding
     * 20 kHz UltraEdge FFT window directly beneath each frame, and pipes the stacked
     * frames into an FFmpeg encoder with the original audio stream preserved.
     */
    public static void processAndSync(String inputVideoPath, String outputVideoPath, UltraEdgeAnalyzer analyzer) throws Exception {
        int[] rawSamples;
        synchronized (analyzer.samples) {
            rawSamples = analyzer.samples.stream().mapToInt(Integer::intValue).toArray();
        }

        if (rawSamples.length < UltraEdgeAnalyzer.FFT_SIZE) {
            throw new IllegalStateException("Insufficient acoustic samples available for analysis.");
        }

        // 1. Detect input dimensions and frame rate
        VideoMeta meta = probeVideo(inputVideoPath);
        int videoWidth = meta.width;
        int videoHeight = meta.height;
        double fps = meta.fps;

        int plotHeight = (int) (videoHeight * 0.30); // 30% height allocated for FFT
        int totalHeight = videoHeight + plotHeight;

        // 2. Decoder: FFmpeg decodes input video to raw BGR24 byte stream
        ProcessBuilder decodePb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputVideoPath,
                "-f", "rawvideo",
                "-pix_fmt", "bgr24",
                "-"
        );
        decodePb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process decodeProc = decodePb.start();
        InputStream videoIn = new BufferedInputStream(decodeProc.getInputStream(), 1024 * 1024);

        // 3. Encoder: Receives stacked raw frames from Java stdin and copies audio track
        ProcessBuilder encodePb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-f", "rawvideo",
                "-pix_fmt", "bgr24",
                "-s", videoWidth + "x" + totalHeight,
                "-r", String.format(java.util.Locale.US, "%.2f", fps),
                "-i", "-",                     // Piped video from stdin
                "-i", inputVideoPath,          // Source video for audio
                "-c:v", "libx264",
                "-pix_fmt", "yuv420p",
                "-c:a", "copy",
                "-map", "0:v:0",
                "-map", "1:a:0?",
                outputVideoPath
        );
        encodePb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process encodeProc = encodePb.start();
        OutputStream videoOut = new BufferedOutputStream(encodeProc.getOutputStream(), 1024 * 1024);

        // 4. Working buffers
        int frameSize = videoWidth * videoHeight * 3;
        byte[] frameBuffer = new byte[frameSize];

        BufferedImage originalFrame = new BufferedImage(videoWidth, videoHeight, BufferedImage.TYPE_3BYTE_BGR);
        byte[] originalPixels = ((DataBufferByte) originalFrame.getRaster().getDataBuffer()).getData();

        BufferedImage combinedFrame = new BufferedImage(videoWidth, totalHeight, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D gCombined = combinedFrame.createGraphics();
        gCombined.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        byte[] combinedPixels = ((DataBufferByte) combinedFrame.getRaster().getDataBuffer()).getData();

        int frameIndex = 0;
        while (readExact(videoIn, frameBuffer, frameSize)) {
            System.arraycopy(frameBuffer, 0, originalPixels, 0, frameSize);

            // Time alignment: map frame index to sample index in the 20 kHz buffer
            double timestampSec = frameIndex / fps;
            int startSample = (int) (timestampSec * UltraEdgeAnalyzer.SAMPLE_RATE);
            if (startSample + UltraEdgeAnalyzer.FFT_SIZE > rawSamples.length) {
                startSample = Math.max(0, rawSamples.length - UltraEdgeAnalyzer.FFT_SIZE);
            }

            // Compute FFT using your existing analyzer DSP
            UltraEdgeAnalyzer.FFTResult fftResult = analyzer.performFFTWindow(rawSamples, startSample);
            BufferedImage fftPlot = renderFFTFrame(videoWidth, plotHeight, fftResult);

            // Composite video frame on top, FFT plot below
            gCombined.drawImage(originalFrame, 0, 0, null);
            gCombined.drawImage(fftPlot, 0, videoHeight, null);

            // Pipe raw frame to encoder
            videoOut.write(combinedPixels);
            frameIndex++;
        }

        // 5. Clean teardown
        videoOut.flush();
        videoOut.close();
        videoIn.close();
        decodeProc.waitFor();
        encodeProc.waitFor();
        gCombined.dispose();
    }

    private static boolean readExact(InputStream in, byte[] buffer, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int count = in.read(buffer, read, len - read);
            if (count == -1) return false;
            read += count;
        }
        return true;
    }

    private static VideoMeta probeVideo(String videoPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe", "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height,r_frame_rate",
                    "-of", "csv=s=x:p=0",
                    videoPath
            );
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();

            if (line != null && !line.trim().isEmpty()) {
                String[] parts = line.trim().split("x");
                int width = Integer.parseInt(parts[0]);
                int height = Integer.parseInt(parts[1]);
                String[] fpsParts = parts[2].split("/");
                double fps = (fpsParts.length == 2)
                        ? Double.parseDouble(fpsParts[0]) / Double.parseDouble(fpsParts[1])
                        : Double.parseDouble(fpsParts[0]);
                return new VideoMeta(width, height, fps);
            }
        } catch (Exception ignored) {}
        return new VideoMeta(1280, 720, 30.0);
    }

    private static class VideoMeta {
        int width, height;
        double fps;
        VideoMeta(int w, int h, double f) { this.width = w; this.height = h; this.fps = f; }
    }

    private static BufferedImage renderFFTFrame(int width, int height, UltraEdgeAnalyzer.FFTResult fftResult) {
        BufferedImage panel = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g2 = panel.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int left = 60, right = 25, top = 24, bottom = 24;
        int gw = width - left - right;
        int gh = height - top - bottom;

        // Background
        g2.setColor(UltraEdgeAnalyzer.BG_CARD);
        g2.fillRect(0, 0, width, height);

        // Header Text
        g2.setFont(new Font("SansSerif", Font.BOLD, 11));
        g2.setColor(UltraEdgeAnalyzer.TEXT_BRIGHT);
        g2.drawString("PRIMARY FFT SPECTRUM DENSITY", left, 16);

        String subtitle = String.format("Peak: %.1f Hz | Res: %.2f Hz",
                fftResult.dominantFrequency,
                (double) UltraEdgeAnalyzer.SAMPLE_RATE / UltraEdgeAnalyzer.FFT_SIZE);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
        g2.setColor(UltraEdgeAnalyzer.TEXT_MUTED);
        g2.drawString(subtitle, width - right - g2.getFontMetrics().stringWidth(subtitle), 16);

        // Grid lines
        g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
        for (int f = 0; f <= (int) UltraEdgeAnalyzer.MAX_FREQUENCY; f += 1000) {
            int x = left + (int) ((f / UltraEdgeAnalyzer.MAX_FREQUENCY) * gw);
            g2.setColor(UltraEdgeAnalyzer.GRID_COLOR);
            g2.drawLine(x, top, x, top + gh);
            g2.setColor(UltraEdgeAnalyzer.TEXT_MUTED);
            g2.drawString(f + "Hz", x - 12, top + gh + 14);
        }

        double peak = 1e-3;
        for (int i = 0; i < fftResult.frequencies.length; i++) {
            if (fftResult.frequencies[i] <= UltraEdgeAnalyzer.MAX_FREQUENCY && fftResult.magnitudes[i] > peak) {
                peak = fftResult.magnitudes[i];
            }
        }

        // Spectrum Gradient
        Path2D.Double path = new Path2D.Double();
        path.moveTo(left, top + gh);
        for (int i = 0; i < fftResult.frequencies.length; i++) {
            if (fftResult.frequencies[i] > UltraEdgeAnalyzer.MAX_FREQUENCY) break;
            double px = left + (fftResult.frequencies[i] / UltraEdgeAnalyzer.MAX_FREQUENCY) * gw;
            double py = top + gh - (fftResult.magnitudes[i] / peak) * (gh - 8);
            path.lineTo(px, py);
        }
        path.lineTo(left + gw, top + gh);
        path.closePath();

        g2.setPaint(new GradientPaint(0, top, new Color(0, 229, 255, 95), 0, top + gh, new Color(0, 229, 255, 5)));
        g2.fill(path);

        // Cyan Trace
        g2.setColor(UltraEdgeAnalyzer.ACCENT_CYAN);
        g2.setStroke(new BasicStroke(1.5f));
        for (int i = 0; i < fftResult.frequencies.length - 1; i++) {
            if (fftResult.frequencies[i + 1] > UltraEdgeAnalyzer.MAX_FREQUENCY) break;
            int x1 = left + (int) ((fftResult.frequencies[i] / UltraEdgeAnalyzer.MAX_FREQUENCY) * gw);
            int y1 = top + gh - (int) ((fftResult.magnitudes[i] / peak) * (gh - 8));
            int x2 = left + (int) ((fftResult.frequencies[i + 1] / UltraEdgeAnalyzer.MAX_FREQUENCY) * gw);
            int y2 = top + gh - (int) ((fftResult.magnitudes[i + 1] / peak) * (gh - 8));
            g2.drawLine(x1, y1, x2, y2);
        }

        g2.dispose();
        return panel;
    }
}