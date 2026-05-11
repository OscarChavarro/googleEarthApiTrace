package frametexturenormalizer.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.util.ScopedTileIds;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.material.RendererConfiguration;

public final class FrameTextureNormalizerModel {
    public static final int SELECT_ALL_TILES = -1;

    private final Camera viewingCamera = new Camera();
    private final RendererConfiguration renderingConfiguration = new RendererConfiguration();
    private final List<FrameData> frames = new ArrayList<>();
    private final Set<String> residentTexturePaths = new HashSet<>();
    private final ArrayDeque<String> residentTexturesFifo = new ArrayDeque<>();
    private final Set<String> westCutterTileIds = new LinkedHashSet<>();
    private long gpuTextureBytesAssigned = 0L;
    private int selectedFrameIndex = 0;
    private int selectedTileIndex = SELECT_ALL_TILES;

    public FrameTextureNormalizerModel() {
        viewingCamera.setName("OrbiterCamera");
        renderingConfiguration.setWires(false);
    }

    public Camera getViewingCamera() {
        return viewingCamera;
    }

    public RendererConfiguration getRenderingConfiguration() {
        return renderingConfiguration;
    }

    public void setFrames(List<FrameData> loadedFrames) {
        frames.clear();
        if (loadedFrames != null) {
            frames.addAll(loadedFrames);
        }
        selectedFrameIndex = 0;
        selectedTileIndex = SELECT_ALL_TILES;
        applyCameraForSelectedFrame();
    }

    public List<FrameData> getFrames() {
        return Collections.unmodifiableList(frames);
    }

    public int getSelectedFrameIndex() {
        if (frames.isEmpty()) {
            return -1;
        }
        selectedFrameIndex = clamp(selectedFrameIndex, 0, frames.size() - 1);
        return selectedFrameIndex;
    }

    public int getSelectedTileIndex() {
        FrameData frame = getSelectedFrame();
        if (frame == null || frame.getTiles().isEmpty()) {
            selectedTileIndex = SELECT_ALL_TILES;
            return selectedTileIndex;
        }
        selectedTileIndex = clamp(selectedTileIndex, SELECT_ALL_TILES, frame.getTiles().size() - 1);
        return selectedTileIndex;
    }

    public FrameData getSelectedFrame() {
        int idx = getSelectedFrameIndex();
        if (idx < 0) {
            return null;
        }
        return frames.get(idx);
    }

    public boolean selectPreviousFrame() {
        if (frames.isEmpty() || selectedFrameIndex <= 0) {
            return false;
        }
        selectedFrameIndex--;
        selectedTileIndex = SELECT_ALL_TILES;
        applyCameraForSelectedFrame();
        return true;
    }

    public boolean selectNextFrame() {
        if (frames.isEmpty() || selectedFrameIndex >= frames.size() - 1) {
            return false;
        }
        selectedFrameIndex++;
        selectedTileIndex = SELECT_ALL_TILES;
        applyCameraForSelectedFrame();
        return true;
    }

    public boolean selectPreviousTile() {
        FrameData frame = getSelectedFrame();
        if (frame == null || frame.getTiles().isEmpty()) {
            return false;
        }
        if (selectedTileIndex <= 0) {
            if (selectedTileIndex < 0) {
                selectedTileIndex = 0;
                return true;
            }
            return false;
        }
        selectedTileIndex--;
        return true;
    }

    public boolean selectNextTile() {
        FrameData frame = getSelectedFrame();
        if (frame == null || frame.getTiles().isEmpty()) {
            return false;
        }
        int maxTileIndex = frame.getTiles().size() - 1;
        if (selectedTileIndex < 0) {
            selectedTileIndex = 0;
            return true;
        }
        if (selectedTileIndex >= maxTileIndex) {
            return false;
        }
        selectedTileIndex++;
        return true;
    }

    public boolean selectFrameById(int frameId) {
        if (frames.isEmpty() || frameId < 0) {
            return false;
        }
        for (int i = 0; i < frames.size(); i++) {
            FrameData frame = frames.get(i);
            if (frame != null && frame.getId() == frameId) {
                selectedFrameIndex = i;
                selectedTileIndex = SELECT_ALL_TILES;
                applyCameraForSelectedFrame();
                return true;
            }
        }
        return false;
    }

    private void applyCameraForSelectedFrame() {
        FrameData selected = getSelectedFrame();
        if (selected == null || selected.getCameraState() == null) {
            return;
        }
        selected.getCameraState().applyTo(viewingCamera);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public synchronized long getGpuTextureBytesAssigned() {
        return gpuTextureBytesAssigned;
    }

    public synchronized void markTextureResident(String texturePath, long bytes) {
        if (texturePath == null || texturePath.isBlank() || bytes <= 0L || residentTexturePaths.contains(texturePath)) {
            return;
        }
        residentTexturePaths.add(texturePath);
        residentTexturesFifo.addLast(texturePath);
        gpuTextureBytesAssigned += bytes;
    }

    public synchronized String popOldestResidentTexturePath() {
        while (!residentTexturesFifo.isEmpty()) {
            String texturePath = residentTexturesFifo.pollFirst();
            if (residentTexturePaths.contains(texturePath)) {
                return texturePath;
            }
        }
        return null;
    }

    public synchronized void unMarkTextureResident(String texturePath, long bytes) {
        if (texturePath == null || !residentTexturePaths.remove(texturePath)) {
            return;
        }
        residentTexturesFifo.removeFirstOccurrence(texturePath);
        gpuTextureBytesAssigned = Math.max(0L, gpuTextureBytesAssigned - Math.max(0L, bytes));
    }

    public synchronized void addWestCutterTileIds(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (String id : ids) {
            String normalized = ScopedTileIds.normalize(id);
            if (normalized != null && westCutterTileIds.add(normalized)) {
                changed = true;
            }
        }
        if (changed) {
            persistWestCuttersCache();
        }
    }

    private synchronized void persistWestCuttersCache() {
        Path outputPath = Path.of(Configuration.INPUT_PATH);
        Path cachePath = outputPath.resolve("westCutters.json");
        try {
            Files.createDirectories(outputPath);
            ObjectMapper mapper = new ObjectMapper();
            List<String> sortedIds = new ArrayList<>(westCutterTileIds);
            Collections.sort(sortedIds);
            mapper.writerWithDefaultPrettyPrinter().writeValue(cachePath.toFile(), sortedIds);
        }
        catch (IOException ignored) {
        }
    }
}
