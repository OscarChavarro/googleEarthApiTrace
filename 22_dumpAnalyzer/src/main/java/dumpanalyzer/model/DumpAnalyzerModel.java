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
import java.util.concurrent.atomic.AtomicLong;
import dumpanalyzer.io.parser.CameraProcessor;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.common.RendererConfiguration;
import vsdk.toolkit.environment.Camera;
import vsdk.toolkit.gui.KeyEvent;
import vsdk.toolkit.gui.RendererConfigurationController;

public final class DumpAnalyzerModel {
    public static final int SELECT_ALL_TILES = -1;

    private final ConcurrentSkipListMap<Integer, Frame> framesById = new ConcurrentSkipListMap<>();
    private final ConcurrentHashMap<Integer, ConcurrentSkipListMap<Integer, String>> texturePathByIdAndFrame = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> textureIs256ByPath = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger selectedFrameIndex = new AtomicInteger(48);
    private final AtomicInteger selectedTileIndex = new AtomicInteger(1);
    private final RendererConfiguration rendererConfiguration = new RendererConfiguration();
    private final RendererConfigurationController rendererConfigurationController =
        new RendererConfigurationController(rendererConfiguration);
    private final Camera viewingCamera = new Camera();
    private final Camera googleCamera = new Camera();
    private final AtomicLong gpuRamTextureBytesAssigned = new AtomicLong(0L);
    private volatile boolean useGoogleCameraAsView = true;
    private volatile boolean showGuiTextures = true;

    public DumpAnalyzerModel() {
        rendererConfiguration.setWires(false);
        rendererConfiguration.setBoundingVolume(false);
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

    public void registerTexturePath(int frameId, int textureId, String absolutePath) {
        texturePathByIdAndFrame
            .computeIfAbsent(textureId, ignored -> new ConcurrentSkipListMap<>())
            .put(frameId, absolutePath);
        textureIs256ByPath.remove(absolutePath);
    }

    public String getTexturePath(int frameId, int textureId) {
        ConcurrentSkipListMap<Integer, String> byFrame = texturePathByIdAndFrame.get(textureId);
        if (byFrame == null || byFrame.isEmpty()) {
            return null;
        }
        String exact = byFrame.get(frameId);
        if (exact != null) {
            return exact;
        }
        var floor = byFrame.floorEntry(frameId);
        if (floor != null) {
            return floor.getValue();
        }
        return byFrame.firstEntry().getValue();
    }

    public String getTexturePath(int frameId, String contentId) {
        Integer textureId = legacyTextureIdFromContent(contentId);
        if (textureId == null) {
            return null;
        }
        return getTexturePath(frameId, textureId);
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

    public boolean isTexture256x256(int frameId, int textureId) {
        String texturePath = getTexturePath(frameId, textureId);
        if (texturePath == null) {
            return false;
        }
        Boolean cached = textureIs256ByPath.get(texturePath);
        if (cached != null) {
            return cached;
        }
        boolean is256 = readTextureSize256x256(texturePath);
        textureIs256ByPath.putIfAbsent(texturePath, is256);
        return is256;
    }

    public boolean isTexture256x256(int frameId, String contentId) {
        String texturePath = getTexturePath(frameId, contentId);
        if (texturePath == null) {
            return false;
        }
        Boolean cached = textureIs256ByPath.get(texturePath);
        if (cached != null) {
            return cached;
        }
        boolean is256 = readTextureSize256x256(texturePath);
        textureIs256ByPath.putIfAbsent(texturePath, is256);
        return is256;
    }

    public long getGpuRamTextureBytesAssigned() {
        return gpuRamTextureBytesAssigned.get();
    }

    public void addGpuRamTextureBytesAssigned(long bytes) {
        if (bytes <= 0L) {
            return;
        }
        gpuRamTextureBytesAssigned.addAndGet(bytes);
    }

    public void subtractGpuRamTextureBytesAssigned(long bytes) {
        if (bytes <= 0L) {
            return;
        }
        gpuRamTextureBytesAssigned.updateAndGet(v -> Math.max(0L, v - bytes));
    }

    public void toggleActiveCamera() {
        useGoogleCameraAsView = !useGoogleCameraAsView;
        notifyListeners();
    }

    public HudState snapshotHudState() {
        List<Frame> frames = snapshotFrames();
        int processed = frames.size();

        if (processed == 0) {
            return new HudState(0, 0, SELECT_ALL_TILES, 0, null);
        }

        int frameIdx = clamp(selectedFrameIndex.get(), 0, processed - 1);
        selectedFrameIndex.set(frameIdx);

        Frame selectedFrame = frames.get(frameIdx);
        int tileCount = selectedFrame.getTiles().size();
        int tileIdx = tileCount == 0 ? SELECT_ALL_TILES : clamp(selectedTileIndex.get(), SELECT_ALL_TILES, tileCount - 1);
        selectedTileIndex.set(tileIdx);

        String selectedTextureId = null;
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
                selectedTileIndex.set(SELECT_ALL_TILES);
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
            selectedTileIndex.set(SELECT_ALL_TILES);
            return;
        }

        int frameIdx = clamp(selectedFrameIndex.get(), 0, processed - 1);
        List<Frame> frames = snapshotFrames();
        selectedFrameIndex.set(frameIdx);

        Frame selectedFrame = frames.get(frameIdx);
        int tiles = selectedFrame.getTiles().size();
        if (tiles <= 0) {
            selectedTileIndex.set(SELECT_ALL_TILES);
        }
        else {
            selectedTileIndex.set(clamp(selectedTileIndex.get(), SELECT_ALL_TILES, tiles - 1));
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

    public record HudState(int selectedFrameIndex, int processedFrames, int selectedTileIndex, int tilesInSelectedFrame, String selectedTextureId) {}

    private static Integer legacyTextureIdFromContent(String contentId) {
        if (contentId == null || contentId.isBlank()) {
            return null;
        }
        int sep = contentId.lastIndexOf('_');
        String suffix = sep >= 0 ? contentId.substring(sep + 1) : contentId;
        try {
            return Integer.parseInt(suffix);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }
}
