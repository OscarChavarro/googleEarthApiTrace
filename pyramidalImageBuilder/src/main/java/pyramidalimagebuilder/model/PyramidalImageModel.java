package pyramidalimagebuilder.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import vsdk.toolkit.environment.Camera;

public final class PyramidalImageModel {
    private final Camera viewingCamera = new Camera();
    private final List<TileInstance> tileInstances = new ArrayList<>();
    private final List<TileInstance> mergedTileInstances = new ArrayList<>();
    private Graph<TileInstance, DefaultEdge> mergedTileGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
    private final List<TileMatrix> tileMatrices = new ArrayList<>();
    private int selectedTileMatrixIndex = 0;

    public PyramidalImageModel() {
        viewingCamera.setName("OrbiterCamera");
    }

    public Camera getViewingCamera() {
        return viewingCamera;
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
}
