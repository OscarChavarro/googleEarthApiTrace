package frametexturenormalizer.model.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.io.FrameJsonWriter;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.model.contract.ScopedTileIds;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.material.RendererConfiguration;

public final class FrameTextureNormalizerState {
    public static final int SELECT_ALL_TILES = -1;

    private final Camera viewingCamera = new Camera();
    private final RendererConfiguration renderingConfiguration = new RendererConfiguration();
    private final List<FrameData> frames = new ArrayList<>();
    private final Set<String> residentTexturePaths = new HashSet<>();
    private final ArrayDeque<String> residentTexturesFifo = new ArrayDeque<>();
    private final Set<String> westCutterTileIds = new LinkedHashSet<>();
    private final Map<Integer, Set<Integer>> pendingDeletedTileIdsByFrame = new HashMap<>();
    private long gpuTextureBytesAssigned = 0L;
    private int selectedFrameIndex = 0;
    private int selectedTileIndex = SELECT_ALL_TILES;

    public FrameTextureNormalizerState() {
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
        pendingDeletedTileIdsByFrame.clear();
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

    public synchronized int deleteSelectedTiles() {
        if (frames.isEmpty()) {
            return 0;
        }

        int deletedCount = 0;
        boolean westCutterCacheChanged = false;
        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            FrameData frame = frames.get(frameIndex);
            if (frame == null || frame.getTiles() == null || frame.getTiles().isEmpty()) {
                continue;
            }

            Set<Integer> removedIds = new LinkedHashSet<>();
            Set<String> removedScopedIds = new LinkedHashSet<>();
            for (TileInstance tile : frame.getTiles()) {
                if (tile != null && tile.isSelected()) {
                    removedIds.add(tile.getTileId());
                    String scopedId = ScopedTileIds.normalize(tile.getScopedId());
                    if (scopedId != null) {
                        removedScopedIds.add(scopedId);
                    }
                }
            }
            if (removedIds.isEmpty()) {
                continue;
            }
            pendingDeletedTileIdsByFrame
                .computeIfAbsent(frame.getId(), ignored -> new LinkedHashSet<>())
                .addAll(removedIds);

            List<TileInstance> keptTiles = new ArrayList<>(Math.max(0, frame.getTiles().size() - removedIds.size()));
            for (TileInstance tile : frame.getTiles()) {
                if (tile == null || removedIds.contains(tile.getTileId())) {
                    continue;
                }
                clearRemovedNeighbors(tile, removedIds);
                keptTiles.add(tile);
            }
            frames.set(frameIndex, new FrameData(
                frame.getId(),
                keptTiles,
                frame.getLines(),
                frame.getCameraState(),
                frame.getProjectionMatrix(),
                frame.getModelViewMatrix(),
                frame.isWithMatrixErrors()
            ));
            deletedCount += removedIds.size();

            for (String scopedId : removedScopedIds) {
                westCutterCacheChanged |= westCutterTileIds.remove(scopedId);
            }
        }

        if (deletedCount > 0) {
            selectedFrameIndex = clamp(selectedFrameIndex, 0, frames.size() - 1);
            FrameData selectedFrame = getSelectedFrame();
            if (selectedFrame == null || selectedFrame.getTiles().isEmpty()) {
                selectedTileIndex = SELECT_ALL_TILES;
            }
            else {
                selectedTileIndex = clamp(selectedTileIndex, SELECT_ALL_TILES, selectedFrame.getTiles().size() - 1);
            }
            if (westCutterCacheChanged) {
                persistWestCuttersCache();
            }
        }
        return deletedCount;
    }

    public synchronized void flushPendingFrameJsonChanges() {
        if (pendingDeletedTileIdsByFrame.isEmpty()) {
            return;
        }
        Map<Integer, Set<Integer>> deletedByFrame = new HashMap<>();
        for (Map.Entry<Integer, Set<Integer>> entry : pendingDeletedTileIdsByFrame.entrySet()) {
            deletedByFrame.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
        Set<Integer> writtenFrameIds = FrameJsonWriter.writeDeletedTiles(deletedByFrame);
        for (Integer frameId : writtenFrameIds) {
            pendingDeletedTileIdsByFrame.remove(frameId);
        }
    }

    private static void clearRemovedNeighbors(TileInstance tile, Set<Integer> removedIds) {
        if (removedIds.contains(tile.getSouthNeighbor())) {
            tile.setSouthNeighbor(null);
        }
        if (removedIds.contains(tile.getNorthNeighbor())) {
            tile.setNorthNeighbor(null);
        }
        if (removedIds.contains(tile.getEastNeighbor())) {
            tile.setEastNeighbor(null);
        }
        if (removedIds.contains(tile.getWestNeighbor())) {
            tile.setWestNeighbor(null);
        }
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

    public synchronized void removeWestCutterTileId(String id) {
        String normalized = ScopedTileIds.normalize(id);
        if (normalized == null || !westCutterTileIds.remove(normalized)) {
            return;
        }
        persistWestCuttersCache();
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
