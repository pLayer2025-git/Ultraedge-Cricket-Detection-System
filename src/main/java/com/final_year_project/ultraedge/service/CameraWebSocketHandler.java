package com.final_year_project.ultraedge.service;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CameraWebSocketHandler extends TextWebSocketHandler {

    // Maps session ID to the camera role (e.g., "onField", "sideField")
    private static final Map<WebSocketSession, String> ACTIVE_CAMERAS = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String role = extractRole(session.getUri());
        ACTIVE_CAMERAS.put(session, role);

        System.out.println(">>> [WS CAMERA] Connected: " + session.getId()
                + " | Role: " + role
                + " | Remote IP: " + session.getRemoteAddress()
                + " | Active Cameras: " + ACTIVE_CAMERAS.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String role = ACTIVE_CAMERAS.remove(session);
        System.out.println(">>> [WS CAMERA] Disconnected: " + session.getId()
                + " | Role: " + role
                + " | Remaining Cameras: " + ACTIVE_CAMERAS.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        ACTIVE_CAMERAS.remove(session);
        System.err.println(">>> [WS CAMERA] Transport Error on " + session.getId() + ": " + exception.getMessage());
    }

    /**
     * Broadcasts START or STOP text payloads to all connected mobile cameras.
     */
    public static synchronized void broadcastCommand(String command) {
        int count = ACTIVE_CAMERAS.size();
        System.out.println(">>> [WS BROADCAST] Sending '" + command + "' to " + count + " device(s)...");

        for (WebSocketSession session : ACTIVE_CAMERAS.keySet()) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(command));
                } catch (IOException e) {
                    System.err.println(">>> [WS BROADCAST] Failed to send to " + session.getId() + ": " + e.getMessage());
                }
            }
        }
    }

    public static int getConnectedCameraCount() {
        ACTIVE_CAMERAS.keySet().removeIf(s -> !s.isOpen());
        return ACTIVE_CAMERAS.size();
    }

    public static boolean hasAnyConnectedCamera() {
        return getConnectedCameraCount() > 0;
    }

    private String extractRole(URI uri) {
        if (uri != null && uri.getQuery() != null) {
            for (String param : uri.getQuery().split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2 && "role".equalsIgnoreCase(pair[0])) {
                    return pair[1];
                }
            }
        }
        return "unknown";
    }
}