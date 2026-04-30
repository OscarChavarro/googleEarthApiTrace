package dumpanalyzer.model;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import dumpanalyzer.parser.CameraProcessor;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.common.RendererConfiguration;
import vsdk.toolkit.environment.Camera;
import vsdk.toolkit.gui.KeyEvent;
import vsdk.toolkit.gui.RendererConfigurationController;

public final class DumpAnalyzerModel {
    private final ConcurrentSkipListMap<Integer, Frame> framesById = new ConcurrentSkipListMap<>();
    private final ConcurrentHashMap<Integer, String> texturePathById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Boolean> textureIs256ById = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger selectedFrameIndex = new AtomicInteger(48);
    private final AtomicInteger selectedTileIndex = new AtomicInteger(1);
    private final RendererConfiguration rendererConfiguration = new RendererConfiguration();
    private final RendererConfigurationController rendererConfigurationController =
        new RendererConfigurationController(rendererConfiguration);
    private final Camera viewingCamera = new Camera();
    private final Camera googleCamera = new Camera();
    private volatile boolean useGoogleCameraAsView = true;
    private volatile boolean showGuiTextures = true;

    public DumpAnalyzerModel() {
        rendererConfiguration.setWires(false);
        rendererConfiguration.setBoundingVolume(true);
        rendererConfiguration.setTexture(true);
        viewingCamera.setName("ViewingCamera");
        googleCamera.setName("GoogleCamera");
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void addFrame(Frame frame) {
        framesById.put(frame.getId(), frame);
        clampSelection();
        updateGoogleCameraFromSelection();
        notifyListeners();
    }

    public void registerTexturePath(int textureId, String absolutePath) {
        texturePathById.putIfAbsent(textureId, absolutePath);
        textureIs256ById.remove(textureId);
    }

    public String getTexturePath(int textureId) {
        return texturePathById.get(textureId);
    }

    public List<Frame> snapshotFrames() {
        return new ArrayList<>(framesById.values());
    }

    public Camera getViewingCamera() {
        return viewingCamera;
    }

    public Camera getGoogleCamera() {
        return googleCamera;
    }

    public Camera getActiveCamera() {
        return useGoogleCameraAsView ? googleCamera : viewingCamera;
    }

    public boolean isUsingGoogleCameraAsView() {
        return useGoogleCameraAsView;
    }

    public boolean isShowGuiTextures() {
        return showGuiTextures;
    }

    public void toggleShowGuiTextures() {
        showGuiTextures = !showGuiTextures;
        notifyListeners();
    }

    public boolean isTexture256x256(int textureId) {
        Boolean cached = textureIs256ById.get(textureId);
        if (cached != null) {
            return cached;
        }
        boolean is256 = readTextureSize256x256(texturePathById.get(textureId));
        textureIs256ById.putIfAbsent(textureId, is256);
        return is256;
    }

    public void toggleActiveCamera() {
        useGoogleCameraAsView = !useGoogleCameraAsView;
        notifyListeners();
    }

    public HudState snapshotHudState() {
        List<Frame> frames = snapshotFrames();
        int processed = frames.size();

        if (processed == 0) {
            return new HudState(0, 0, -1, 0, 0);
        }

        int frameIdx = clamp(selectedFrameIndex.get(), 0, processed - 1);
        selectedFrameIndex.set(frameIdx);

        Frame selectedFrame = frames.get(frameIdx);
        int tileCount = selectedFrame.getTiles().size();
        int tileIdx = tileCount == 0 ? -1 : clamp(selectedTileIndex.get(), -1, tileCount - 1);
        selectedTileIndex.set(tileIdx);

        int selectedTextureId = 0;
        if (tileIdx >= 0 && tileIdx < tileCount) {
            selectedTextureId = selectedFrame.getTiles().get(tileIdx).getContentId();
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

    public void setSelectedFrameIndex(int frameIndex) {
        selectedFrameIndex.set(Math.max(0, frameIndex));
        clampSelection();
        updateGoogleCameraFromSelection();
        notifyListeners();
    }

    public void setSelectedFrameById(int frameId) {
        List<Integer> ids = new ArrayList<>(framesById.keySet());
        Collections.sort(ids);
        int idx = ids.indexOf(frameId);
        if (idx < 0) {
            return;
        }
        selectedFrameIndex.set(idx);
        clampSelection();
        updateGoogleCameraFromSelection();
        notifyListeners();
    }

    public void selectFirstFrameWithTiles() {
        List<Frame> frames = snapshotFrames();
        if (frames.isEmpty()) {
            return;
        }
        for (int i = 0; i < frames.size(); i++) {
            if (!frames.get(i).getTiles().isEmpty()) {
                selectedFrameIndex.set(i);
                selectedTileIndex.set(-1);
                clampSelection();
                updateGoogleCameraFromSelection();
                notifyListeners();
                return;
            }
        }
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
            selectedFrameIndex.set(0);
            selectedTileIndex.set(-1);
            return;
        }

        int frameIdx = clamp(selectedFrameIndex.get(), 0, processed - 1);
        List<Frame> frames = snapshotFrames();
        selectedFrameIndex.set(frameIdx);

        Frame selectedFrame = frames.get(frameIdx);
        int tiles = selectedFrame.getTiles().size();
        if (tiles <= 0) {
            selectedTileIndex.set(-1);
        }
        else {
            selectedTileIndex.set(clamp(selectedTileIndex.get(), -1, tiles - 1));
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

        int current = clamp(selectedFrameIndex.get(), 0, frames.size() - 1);
        int idx = current + direction;
        while (idx >= 0 && idx < frames.size()) {
            if (!frames.get(idx).getTiles().isEmpty()) {
                selectedFrameIndex.set(idx);
                clampSelection();
                updateGoogleCameraFromSelection();
                notifyListeners();
                return;
            }
            idx += direction;
        }
    }

    private void updateGoogleCameraFromSelection() {
        List<Frame> frames = snapshotFrames();
        if (frames.isEmpty()) {
            return;
        }
        int frameIdx = clamp(selectedFrameIndex.get(), 0, frames.size() - 1);
        Frame selected = frames.get(frameIdx);
        CameraProcessor.applyProjectionMatrixToCamera(googleCamera, selected.getProjectionMatrix());
        CameraProcessor.applyModelViewMatrixToCamera(googleCamera, selected.getModelViewMatrix());
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    private static boolean readTextureSize256x256(String texturePath) {
        if (texturePath == null || texturePath.isBlank()) {
            return false;
        }
        try {
            var image = ImagePersistence.importRGB(new File(texturePath));
            return image != null && image.getXSize() == 256 && image.getYSize() == 256;
        } catch (IOException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public record HudState(int selectedFrameIndex, int processedFrames, int selectedTileIndex, int tilesInSelectedFrame, int selectedTextureId) {}
}
