package com.final_year_project.ultraedge.model;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DeliveryBundle implements Serializable {
    private static final long serialVersionUID = 1L;

    private final List<Integer> audioSamples;
    private final Path onFieldVideo;
    private final Path sideFieldVideo;

    public DeliveryBundle(List<Integer> audioSamples, Path onFieldVideo, Path sideFieldVideo) {
        this.audioSamples = (audioSamples != null)
                ? Collections.unmodifiableList(new ArrayList<>(audioSamples))
                : Collections.emptyList();
        this.onFieldVideo = onFieldVideo;
        this.sideFieldVideo = sideFieldVideo;
    }

    public List<Integer> getAudioSamples() {
        return audioSamples;
    }

    public Path getOnFieldVideo() {
        return onFieldVideo;
    }

    public Path getSideFieldVideo() {
        return sideFieldVideo;
    }
}