package com.final_year_project.ultraedge.controller;

import com.final_year_project.ultraedge.service.RecordingCoordinator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;

@RestController
@RequestMapping("/api/video")
@CrossOrigin(origins = "*")
public class VideoUploadController {

    private static final Path ROOT_DIR = Paths.get("recordings").toAbsolutePath().normalize();

    @PostMapping("/upload")
    public ResponseEntity<String> uploadVideo(
            @RequestParam(value = "video", required = false) MultipartFile videoFile,
            @RequestParam(value = "file", required = false) MultipartFile fileFallback,
            @RequestParam("role") String role) {

        MultipartFile file = (videoFile != null) ? videoFile : fileFallback;

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("Uploaded file is missing or empty.");
        }

        // Determine destination folder: 'recordings/on_field' or 'recordings/side_field'
        String subFolder = role.toLowerCase().contains("side") ? "side_field" : "on_field";
        Path targetDir = ROOT_DIR.resolve(subFolder);

        try {
            // Ensure local directories exist
            Files.createDirectories(targetDir);

            // Determine file extension (.mp4 from Android CameraX or preserve original)
            String extension = ".mp4";
            String originalName = file.getOriginalFilename();
            if (originalName != null && originalName.lastIndexOf('.') != -1) {
                extension = originalName.substring(originalName.lastIndexOf('.'));
            }

            // Generate timestamped file name
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String fileName = subFolder + "_" + timestamp + extension;
            Path destinationPath = targetDir.resolve(fileName);

            // Stream and replace if existing
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, destinationPath, StandardCopyOption.REPLACE_EXISTING);
            }

            System.out.println(">>> [REST UPLOAD] Written to disk: " + destinationPath);

            // Inform the coordinator of this arrival
            RecordingCoordinator.registerUpload(subFolder, destinationPath);

            return ResponseEntity.ok("Saved: " + fileName);

        } catch (IOException e) {
            System.err.println(">>> [REST UPLOAD ERROR] Failed writing file: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error saving file: " + e.getMessage());
        }
    }
}