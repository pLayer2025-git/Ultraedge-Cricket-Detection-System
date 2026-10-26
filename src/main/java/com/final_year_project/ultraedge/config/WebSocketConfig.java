package com.final_year_project.ultraedge.config;

import com.final_year_project.ultraedge.service.CameraWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final CameraWebSocketHandler cameraWebSocketHandler;

    public WebSocketConfig(CameraWebSocketHandler cameraWebSocketHandler) {
        this.cameraWebSocketHandler = cameraWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Exposes ws://<IP>:8080/cameras
        registry.addHandler(cameraWebSocketHandler, "/cameras")
                .setAllowedOrigins("*");
    }
}