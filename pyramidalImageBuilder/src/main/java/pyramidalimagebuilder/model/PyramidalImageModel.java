package pyramidalimagebuilder.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import vsdk.toolkit.environment.Camera;

public final class PyramidalImageModel {
    private final Camera viewingCamera = new Camera();
    private final RenderingConfiguration renderingConfiguration = new RenderingConfiguration();
    private final List<TileInstance> tileInstances = new ArrayList<>();
    private final List<TileInstance> mergedTileInstances = new ArrayList<>();
    private Graph<TileInstance, DefaultEdge> mergedTileGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
    private final List<TileMatrix> tileMatrices = new ArrayList<>();
    private final Map<Integer, String> texturePathById = new HashMap<>();
    private final Map<Integer, Integer> textureFirstFrameById = new HashMap<>();
    private final Set<Integer> residentTextureIds = new HashSet<>();
    private final ArrayDeque<Integer> residentTexturesFifo = new ArrayDeque<>();
    private long gpuTextureBytesAssigned = 0L;
    private int selectedTileMatrixIndex = 0;

    public PyramidalImageModel() {
        viewingCamera.setName("OrbiterCamera");
    }

    public Camera getViewingCamera() {
        return viewingCamera;
    }

    public RenderingConfiguration getRenderingConfiguration() {
        return renderingConfiguration;
    }

    public void setTileInstances(List<TileInstance> tiles) {
        tileInstances.clear();
        if (tiles != null) {
            tileInstances.addAll(tiles);
        }
    }

    public List<TileInstance> getTileInstances() {
        return Collections.unmodifiableList(tileInstances);
    }

    public void setMergedTileInstances(List<TileInstance> tiles) {
        mergedTileInstances.clear();
        if (tiles != null) {
            mergedTileInstances.addAll(tiles);
        }
    }

    public List<TileInstance> getMergedTileInstances() {
        return Collections.unmodifiableList(mergedTileInstances);
    }

    public void setMergedTileGraph(Graph<TileInstance, DefaultEdge> graph) {
        if (graph == null) {
            this.mergedTileGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
            return;
        }
        this.mergedTileGraph = graph;
    }

    public Graph<TileInstance, DefaultEdge> getMergedTileGraph() {
        return mergedTileGraph;
    }

    public void setTileMatrices(List<TileMatrix> tileMatrices) {
        this.tileMatrices.clear();
        if (tileMatrices != null) {
            this.tileMatrices.addAll(tileMatrices);
        }
        selectedTileMatrixIndex = 0;
    }

    public List<TileMatrix> getTileMatrices() {
        return Collections.unmodifiableList(tileMatrices);
    }

    public int getSelectedTileMatrixIndex() {
        if (tileMatrices.isEmpty()) {
            return -1;
        }
        if (selectedTileMatrixIndex < 0) {
            selectedTileMatrixIndex = 0;
        }
        if (selectedTileMatrixIndex >= tileMatrices.size()) {
            selectedTileMatrixIndex = tileMatrices.size() - 1;
        }
        return selectedTileMatrixIndex;
    }

    public TileMatrix getSelectedTileMatrix() {
        int idx = getSelectedTileMatrixIndex();
        if (idx < 0) {
            return null;
        }
        return tileMatrices.get(idx);
    }

    public boolean selectPreviousTileMatrix() {
        if (tileMatrices.isEmpty() || selectedTileMatrixIndex <= 0) {
            return false;
        }
        selectedTileMatrixIndex--;
        return true;
    }

    public boolean selectNextTileMatrix() {
        if (tileMatrices.isEmpty() || selectedTileMatrixIndex >= tileMatrices.size() - 1) {
            return false;
        }
        selectedTileMatrixIndex++;
        return true;
    }

    public String selectedTileMatrixSizeText() {
        TileMatrix selected = getSelectedTileMatrix();
        if (selected == null || selected.getM() == null || selected.getM().length == 0) {
            return "0 x 0";
        }
        int rows = selected.getM().length;
        int cols = selected.getM()[0].length;
        return cols + " x " + rows;
    }

    public synchronized void registerTexturePath(int frameId, int textureId, String absolutePath) {
        if (textureId < 0 || absolutePath == null || absolutePath.isBlank()) {
            return;
        }
        Integer currentFirst = textureFirstFrameById.get(textureId);
        if (currentFirst == null || frameId < currentFirst) {
            textureFirstFrameById.put(textureId, frameId);
            texturePathById.put(textureId, absolutePath);
        }
    }

    public synchronized String getTexturePath(int textureId) {
        return texturePathById.get(textureId);
    }

    public synchronized long getGpuTextureBytesAssigned() {
        return gpuTextureBytesAssigned;
    }

    public synchronized boolean markTextureResident(int textureId, long bytes) {
        if (textureId < 0 || bytes <= 0L || residentTextureIds.contains(textureId)) {
            return false;
        }
        residentTextureIds.add(textureId);
        residentTexturesFifo.addLast(textureId);
        gpuTextureBytesAssigned += bytes;
        return true;
    }

    public synchronized Integer popOldestResidentTextureId() {
        while (!residentTexturesFifo.isEmpty()) {
            int textureId = residentTexturesFifo.pollFirst();
            if (residentTextureIds.contains(textureId)) {
                return textureId;
            }
        }
        return null;
    }

    public synchronized void unmarkTextureResident(int textureId, long bytes) {
        if (!residentTextureIds.remove(textureId)) {
            return;
        }
        residentTexturesFifo.removeFirstOccurrence(textureId);
        gpuTextureBytesAssigned = Math.max(0L, gpuTextureBytesAssigned - Math.max(0L, bytes));
    }
}
