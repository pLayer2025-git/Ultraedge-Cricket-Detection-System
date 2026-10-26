package com.final_year_project.ultraedge.service;

import java.nio.file.Path;
import java.util.function.BiConsumer;

public class RecordingCoordinator {

    private static Path onFieldVideo = null;
    private static Path sideFieldVideo = null;
    private static BiConsumer<Path, Path> completionCallback = null;
    private static int expectedCameraCount = 2;

    public static synchronized void startAwaitingRecordings(int expectedCameras, BiConsumer<Path, Path> callback) {
        reset();
        expectedCameraCount = expectedCameras;
        completionCallback = callback;
    }

    public static synchronized void registerUpload(String role, Path filePath) {
        if (role.toLowerCase().contains("side")) {
            sideFieldVideo = filePath;
        } else {
            onFieldVideo = filePath;
        }

        checkAndNotify();
    }

    private static synchronized void checkAndNotify() {
        if (completionCallback == null) return;

        boolean isReady = false;

        if (expectedCameraCount <= 1) {
            // As long as at least ONE video arrived, notify
            if (onFieldVideo != null || sideFieldVideo != null) {
                isReady = true;
            }
        } else {
            // Both are required for dual-camera mode
            if (onFieldVideo != null && sideFieldVideo != null) {
                isReady = true;
            }
        }

        if (isReady) {
            BiConsumer<Path, Path> cb = completionCallback;
            Path onField = onFieldVideo;
            Path sideField = sideFieldVideo;
            reset(); // Clear state for the next ball
            cb.accept(onField, sideField);
        }
    }

    public static synchronized void reset() {
        onFieldVideo = null;
        sideFieldVideo = null;
        completionCallback = null;
    }
}