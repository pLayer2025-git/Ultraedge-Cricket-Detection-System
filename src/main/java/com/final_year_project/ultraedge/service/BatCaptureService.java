package com.final_year_project.ultraedge.service;

import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Service
public class BatCaptureService {

    public static final int TCP_PORT = 5000;
    public static final int UDP_DISCOVERY_PORT = 4210;

    public static final int SAMPLE_RATE = 20_000;
    public static final int DURATION_SECONDS = 10;
    public static final int EXPECTED_SAMPLES = SAMPLE_RATE * DURATION_SECONDS; // 200,000 samples
    public static final int SAMPLES_PER_PACKET = 500;
    public static final int HEADER_SIZE = 12;
    public static final int PACKET_SAMPLE_BYTES = SAMPLES_PER_PACKET * 2;

    private static volatile boolean isCapturing = false;
    private static ServerSocket serverSocketInstance = null;

    /**
     * Simultaneously sends the UDP discovery beacon to wake up the bat AND opens
     * TCP port 5000 to accept the incoming socket with zero delay.
     */
    public static synchronized void startCaptureSession(
            Runnable onHandshakeSuccess,
            Consumer<List<Integer>> onComplete,
            Consumer<String> onError) {

        if (isCapturing) {
            if (onError != null) onError.accept("Capture session is already active.");
            return;
        }

        isCapturing = true;

        // 1. Thread 1: Send UDP broadcast beacon to wake up the ESP32 instantly
        sendInstantUDPBroadcast();

        // 2. Thread 2: Start TCP Server to accept the incoming bat connection
        Thread tcpWorker = new Thread(() -> {
            Socket clientSocket = null;
            List<Integer> capturedSamples = new ArrayList<>(EXPECTED_SAMPLES);

            try {
                serverSocketInstance = new ServerSocket(TCP_PORT);
                serverSocketInstance.setSoTimeout(12000); // 12 seconds timeout
                System.out.println(">>> [BAT TCP] Port " + TCP_PORT + " armed & listening for BAT...");

                clientSocket = serverSocketInstance.accept();
                clientSocket.setTcpNoDelay(true);
                clientSocket.setSoTimeout(1000);

                InputStream input = clientSocket.getInputStream();
                OutputStream output = clientSocket.getOutputStream();

                // Receive and verify handshake from ESP32
                String handshake = readLineWithTimeout(input);
                if (!"BAT_CONNECTED".equals(handshake)) {
                    throw new IOException("Handshake failed. Received: " + handshake);
                }
                System.out.println(">>> [BAT TCP] Handshake verified: BAT_CONNECTED");

                // Trigger Cameras via WebSocket callback immediately
                if (onHandshakeSuccess != null) {
                    onHandshakeSuccess.run();
                }

                // Send START command to ESP32
                output.write("START\n".getBytes());
                output.flush();
                System.out.println(">>> [BAT TCP] START sent to bat. Ingesting 10s audio stream...");

                long startTime = System.nanoTime();
                receiveBinaryData(input, startTime, capturedSamples);

                // Send STOP command to ESP32
                try {
                    output.write("STOP\n".getBytes());
                    output.flush();
                } catch (Exception ignored) {}

                synchronized (capturedSamples) {
                    if (capturedSamples.size() > EXPECTED_SAMPLES) {
                        capturedSamples.subList(EXPECTED_SAMPLES, capturedSamples.size()).clear();
                    }
                }
                System.out.println(">>> [BAT TCP] 10s Window Finished. Samples: " + capturedSamples.size());

                if (onComplete != null) {
                    onComplete.accept(capturedSamples);
                }

            } catch (SocketTimeoutException ste) {
                System.err.println(">>> [BAT TCP TIMEOUT] Bat failed to connect within timeout.");
                if (onError != null) onError.accept("Bat connection timed out.");
            } catch (Exception ex) {
                System.err.println(">>> [BAT TCP ERROR] " + ex.getMessage());
                if (onError != null) onError.accept("Bat communication error: " + ex.getMessage());
            } finally {
                closeQuietly(clientSocket);
                closeServerSocket();
                isCapturing = false;
                System.out.println(">>> [BAT TCP] Port " + TCP_PORT + " closed.");
            }
        }, "Bat-TCP-Worker");

        tcpWorker.setDaemon(true);
        tcpWorker.start();
    }

    /**
     * Broadcasts 3 rapid UDP packets across the subnet so the ESP32 catches it immediately.
     */
    private static void sendInstantUDPBroadcast() {
        Thread udpThread = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                byte[] data = "ULTRAEDGE_SERVER:5000".getBytes();
                InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");

                DatagramPacket packet = new DatagramPacket(data, data.length, broadcastAddress, UDP_DISCOVERY_PORT);

                // Send 3 rapid bursts to ensure Wi-Fi delivery
                for (int i = 0; i < 3; i++) {
                    socket.send(packet);
                    Thread.sleep(30);
                }
                System.out.println(">>> [UDP BURST] Sent 'ULTRAEDGE_SERVER:5000' broadcast to wake up Bat.");
            } catch (Exception e) {
                System.err.println(">>> [UDP ERROR] Failed to broadcast beacon: " + e.getMessage());
            }
        }, "Bat-UDP-Broadcaster");

        udpThread.setDaemon(true);
        udpThread.start();
    }

    private static void receiveBinaryData(InputStream input, long startTime, List<Integer> sampleCollector) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        byte[] sampleBytes = new byte[PACKET_SAMPLE_BYTES];
        int expectedSequence = 0;

        while (true) {
            double elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0;
            if (elapsed >= DURATION_SECONDS) break;

            if (!readFullyWithTimeout(input, header, 0, HEADER_SIZE)) continue;

            ByteBuffer headerBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            long timestamp = headerBuf.getLong();
            int sequence = headerBuf.getInt();

            if (sequence != expectedSequence) {
                System.out.println(">>> [BAT TCP] Frame drop! Expected: " + expectedSequence + " Got: " + sequence);
            }
            expectedSequence = sequence + 1;

            readFullyAllowTimeout(input, sampleBytes, 0, sampleBytes.length);
            ByteBuffer sampleBuf = ByteBuffer.wrap(sampleBytes).order(ByteOrder.LITTLE_ENDIAN);

            for (int i = 0; i < SAMPLES_PER_PACKET; i++) {
                if (sampleCollector.size() >= EXPECTED_SAMPLES) break;
                sampleCollector.add((int) sampleBuf.getShort());
            }
        }
    }

    private static String readLineWithTimeout(InputStream input) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = input.read();
            if (c == -1) throw new EOFException("ESP closed connection prematurely.");
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return sb.toString();
    }

    private static boolean readFullyWithTimeout(InputStream input, byte[] buffer, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            try {
                int n = input.read(buffer, offset + total, length - total);
                if (n == -1) throw new EOFException("Bat disconnected.");
                total += n;
            } catch (SocketTimeoutException e) {
                if (total == 0) return false;
            }
        }
        return true;
    }

    private static void readFullyAllowTimeout(InputStream input, byte[] buffer, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            try {
                int n = input.read(buffer, offset + total, length - total);
                if (n == -1) throw new EOFException("Bat disconnected.");
                total += n;
            } catch (SocketTimeoutException ignored) {}
        }
    }

    private static void closeServerSocket() {
        if (serverSocketInstance != null && !serverSocketInstance.isClosed()) {
            try {
                serverSocketInstance.close();
            } catch (IOException ignored) {}
        }
        serverSocketInstance = null;
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {}
        }
    }

    public static boolean isCapturing() {
        return isCapturing;
    }
}