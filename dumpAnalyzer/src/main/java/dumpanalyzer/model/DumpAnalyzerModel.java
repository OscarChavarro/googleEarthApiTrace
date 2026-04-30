package dumpanalyzer.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import vsdk.toolkit.common.RendererConfiguration;
import vsdk.toolkit.gui.KeyEvent;
import vsdk.toolkit.gui.RendererConfigurationController;

public final class DumpAnalyzerModel {
    private final ConcurrentSkipListMap<Integer, Frame> framesById = new ConcurrentSkipListMap<>();
    private final ConcurrentHashMap<Integer, String> texturePathById = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger selectedFrameIndex = new AtomicInteger(1);
    private final AtomicInteger selectedTileIndex = new AtomicInteger(0);
    private final RendererConfiguration rendererConfiguration = new RendererConfiguration();
    private final RendererConfigurationController rendererConfigurationController =
        new RendererConfigurationController(rendererConfiguration);

    public DumpAnalyzerModel() {
        rendererConfiguration.setWires(true);
        rendererConfiguration.setBoundingVolume(true);
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void addFrame(Frame frame) {
        framesById.put(frame.getId(), frame);
        clampSelection();
        notifyListeners();
    }

    public void registerTexturePath(int textureId, String absolutePath) {
        texturePathById.putIfAbsent(textureId, absolutePath);
    }

    public String getTexturePath(int textureId) {
        return texturePathById.get(textureId);
    }

    public List<Frame> snapshotFrames() {
        return new ArrayList<>(framesById.values());
    }

    public HudState snapshotHudState() {
        List<Frame> frames = snapshotFrames();
        int processed = frames.size();

        if (processed == 0) {
            return new HudState(0, 0, 0, 0, 0);
        }

        int frameIdx = clamp(selectedFrameIndex.get(), 1, processed);
        selectedFrameIndex.set(frameIdx);

        Frame selectedFrame = frames.get(frameIdx - 1);
        int tileCount = selectedFrame.getTiles().size();
        int tileIdx = tileCount == 0 ? 0 : clamp(selectedTileIndex.get(), 0, tileCount);
        selectedTileIndex.set(tileIdx);

        int selectedTextureId = 0;
        if (tileIdx > 0 && tileIdx <= tileCount) {
            selectedTextureId = selectedFrame.getTiles().get(tileIdx - 1).getContentId();
        }

        return new HudState(frameIdx, processed, tileIdx, tileCount, selectedTextureId);
    }

    public void selectPreviousFrame() {
        jumpToFrameWithTiles(-1);
    }

    public void selectNextFrame() {
        jumpToFrameWithTiles(+1);
    }

    public void selectPreviousTile() {
        selectedTileIndex.decrementAndGet();
        clampSelection();
        notifyListeners();
    }

    public void selectNextTile() {
        selectedTileIndex.incrementAndGet();
        clampSelection();
        notifyListeners();
    }

    public RendererConfiguration getRendererConfiguration() {
        return rendererConfiguration;
    }

    public boolean processRendererConfigurationKey(KeyEvent event) {
        boolean changed = rendererConfigurationController.processKeyPressedEvent(event);
        if (changed) {
            notifyListeners();
        }
        return changed;
    }

    private void clampSelection() {
        int processed = framesById.size();
        if (processed <= 0) {
            selectedFrameIndex.set(1);
            selectedTileIndex.set(0);
            return;
        }

        int frameIdx = clamp(selectedFrameIndex.get(), 1, processed);
        List<Frame> frames = snapshotFrames();
        selectedFrameIndex.set(frameIdx);

        Frame selectedFrame = frames.get(frameIdx - 1);
        int tiles = selectedFrame.getTiles().size();
        if (tiles <= 0) {
            selectedTileIndex.set(0);
        }
        else {
            selectedTileIndex.set(clamp(selectedTileIndex.get(), 0, tiles));
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void jumpToFrameWithTiles(int direction) {
        List<Frame> frames = snapshotFrames();
        if (frames.isEmpty()) {
            return;
        }

        int current = clamp(selectedFrameIndex.get(), 1, frames.size());
        int idx = current + direction;
        while (idx >= 1 && idx <= frames.size()) {
            if (!frames.get(idx - 1).getTiles().isEmpty()) {
                selectedFrameIndex.set(idx);
                clampSelection();
                notifyListeners();
                return;
            }
            idx += direction;
        }
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    public record HudState(int selectedFrameIndex, int processedFrames, int selectedTileIndex, int tilesInSelectedFrame, int selectedTextureId) {}
}
