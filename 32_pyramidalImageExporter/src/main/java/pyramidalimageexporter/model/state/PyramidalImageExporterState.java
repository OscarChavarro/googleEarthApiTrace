package pyramidalimageexporter.model.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;
import pyramidalimageexporter.processing.content.ContentHashCatalog;
import pyramidalimageexporter.processing.uncles.UncleRmsAnalyzer;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.material.RendererConfiguration;

public final class PyramidalImageExporterState {
    private final Camera viewingCamera = new Camera();
    private final RendererConfiguration renderingConfiguration = new RendererConfiguration();
    private final List<MatrixLayer> matrixLayers = new ArrayList<>();
    private final Set<String> residentTexturePaths = new HashSet<>();
    private final ArrayDeque<String> residentTexturesFifo = new ArrayDeque<>();
    private String inputFolder;
    private String sessionPyramidalImageExportPath;
    private String lastExportStatus;
    private long gpuTextureBytesAssigned = 0L;
    private int selectedLayerIndex = 0;
    private Map<String, String> cataloguedQuadPathsByImagePath = Map.of();
    private Map<String, String> referenceQuadPathsByImagePath = Map.of();
    private Map<String, String> referenceContentHashByImagePath = Map.of();
    private ContentHashCatalog contentHashCatalog = new ContentHashCatalog();
    private String referencePyramidFolder;
    private Map<String, String> mergedFullPathByOriginalId = Map.of();
    private UncleRmsAnalyzer.Analysis uncleRmsAnalysis = UncleRmsAnalyzer.Analysis.empty();
    private Supplier<UncleRmsAnalyzer.Analysis> uncleRmsAnalysisLoader;
    private boolean uncleRmsAnalysisLoaded = true;
    private boolean rmsHeatMapEnabled;

    public PyramidalImageExporterState() {
        viewingCamera.setName("OrbiterCamera");
        renderingConfiguration.setWires(false);
    }

    public Camera getViewingCamera() {
        return viewingCamera;
    }

    public RendererConfiguration getRenderingConfiguration() {
        return renderingConfiguration;
    }

    public String getInputFolder() {
        return inputFolder;
    }

    public void setInputFolder(String inputFolder) {
        this.inputFolder = (inputFolder == null || inputFolder.isBlank()) ? null : inputFolder;
    }

    public Map<String, String> getCataloguedQuadPathsByImagePath() {
        return cataloguedQuadPathsByImagePath;
    }

    public void setCataloguedQuadPathsByImagePath(Map<String, String> cataloguedQuadPathsByImagePath) {
        this.cataloguedQuadPathsByImagePath = cataloguedQuadPathsByImagePath == null
            ? Map.of()
            : Map.copyOf(cataloguedQuadPathsByImagePath);
    }

    public Map<String, String> getReferenceQuadPathsByImagePath() {
        return referenceQuadPathsByImagePath;
    }

    public void setReferenceQuadPathsByImagePath(Map<String, String> referenceQuadPathsByImagePath) {
        this.referenceQuadPathsByImagePath = referenceQuadPathsByImagePath == null
            ? Map.of()
            : Map.copyOf(referenceQuadPathsByImagePath);
    }

    public Map<String, String> getReferenceContentHashByImagePath() {
        return referenceContentHashByImagePath;
    }

    public void setReferenceContentHashByImagePath(Map<String, String> referenceContentHashByImagePath) {
        this.referenceContentHashByImagePath = referenceContentHashByImagePath == null
            ? Map.of()
            : Map.copyOf(referenceContentHashByImagePath);
    }

    public ContentHashCatalog getContentHashCatalog() {
        return contentHashCatalog;
    }

    public void setContentHashCatalog(ContentHashCatalog contentHashCatalog) {
        this.contentHashCatalog = contentHashCatalog == null
            ? new ContentHashCatalog()
            : contentHashCatalog;
    }

    public String getReferencePyramidFolder() {
        return referencePyramidFolder;
    }

    public void setReferencePyramidFolder(String referencePyramidFolder) {
        this.referencePyramidFolder = referencePyramidFolder == null || referencePyramidFolder.isBlank()
            ? null
            : referencePyramidFolder;
    }

    public Map<String, String> getMergedFullPathByOriginalId() {
        return mergedFullPathByOriginalId;
    }

    public void setMergedFullPathByOriginalId(Map<String, String> mergedFullPathByOriginalId) {
        this.mergedFullPathByOriginalId = mergedFullPathByOriginalId == null
            ? Map.of()
            : Map.copyOf(mergedFullPathByOriginalId);
    }

    public void setUncleRmsAnalysis(UncleRmsAnalyzer.Analysis uncleRmsAnalysis) {
        this.uncleRmsAnalysis = uncleRmsAnalysis == null
            ? UncleRmsAnalyzer.Analysis.empty()
            : uncleRmsAnalysis;
        uncleRmsAnalysisLoader = null;
        uncleRmsAnalysisLoaded = true;
    }

    public void setUncleRmsAnalysisLoader(Supplier<UncleRmsAnalyzer.Analysis> loader) {
        uncleRmsAnalysis = UncleRmsAnalyzer.Analysis.empty();
        uncleRmsAnalysisLoader = loader;
        uncleRmsAnalysisLoaded = loader == null;
    }

    public UncleRmsAnalyzer.TileScore getUncleRmsScore(MatrixLayer layer, MatrixLayerTile tile) {
        return loadUncleRmsAnalysis().scoreFor(layer, tile);
    }

    public int getComparedUncleRelationshipCount() {
        return uncleRmsAnalysisLoaded ? uncleRmsAnalysis.matches().size() : 0;
    }

    public boolean isRmsHeatMapEnabled() {
        return rmsHeatMapEnabled;
    }

    public void setRmsHeatMapEnabled(boolean rmsHeatMapEnabled) {
        this.rmsHeatMapEnabled = rmsHeatMapEnabled;
        if (rmsHeatMapEnabled) {
            loadUncleRmsAnalysis();
        }
    }

    public void toggleRmsHeatMap() {
        setRmsHeatMapEnabled(!rmsHeatMapEnabled);
    }

    private synchronized UncleRmsAnalyzer.Analysis loadUncleRmsAnalysis() {
        if (uncleRmsAnalysisLoaded) {
            return uncleRmsAnalysis;
        }
        Supplier<UncleRmsAnalyzer.Analysis> loader = uncleRmsAnalysisLoader;
        UncleRmsAnalyzer.Analysis loaded = loader == null ? null : loader.get();
        uncleRmsAnalysis = loaded == null ? UncleRmsAnalyzer.Analysis.empty() : loaded;
        uncleRmsAnalysisLoader = null;
        uncleRmsAnalysisLoaded = true;
        return uncleRmsAnalysis;
    }

    public String getSessionPyramidalImageExportPath() {
        return sessionPyramidalImageExportPath;
    }

    public void setSessionPyramidalImageExportPath(String sessionPyramidalImageExportPath) {
        this.sessionPyramidalImageExportPath = (sessionPyramidalImageExportPath == null || sessionPyramidalImageExportPath.isBlank())
            ? null
            : sessionPyramidalImageExportPath;
    }

    public String getLastExportStatus() {
        return lastExportStatus;
    }

    public void setLastExportStatus(String lastExportStatus) {
        this.lastExportStatus = lastExportStatus;
    }

    public void setMatrixLayers(List<MatrixLayer> layers) {
        matrixLayers.clear();
        if (layers != null) {
            matrixLayers.addAll(layers);
        }
        selectedLayerIndex = 0;
        normalizeSelection();
    }

    public List<MatrixLayer> getMatrixLayers() {
        return Collections.unmodifiableList(matrixLayers);
    }

    public MatrixLayer getSelectedMatrixLayer() {
        if (matrixLayers.isEmpty() || selectedLayerIndex < 0 || selectedLayerIndex >= matrixLayers.size()) {
            return null;
        }
        return matrixLayers.get(selectedLayerIndex);
    }

    public int getSelectedMatrixLayerOrdinal() {
        return matrixLayers.isEmpty() ? 0 : selectedLayerIndex + 1;
    }

    public int getMatrixLayerCount() {
        return matrixLayers.size();
    }

    public String getSelectedFrameLabel() {
        MatrixLayer selected = getSelectedMatrixLayer();
        if (selected == null || selected.getFrameId() < 0) {
            return "?";
        }
        return String.format("%05d", selected.getFrameId());
    }

    public boolean selectLayerIndex(int index) {
        if (matrixLayers.isEmpty() || index < 0 || index >= matrixLayers.size()) {
            return false;
        }
        selectedLayerIndex = index;
        return true;
    }

    public boolean selectPreviousLayer() {
        if (matrixLayers.isEmpty() || selectedLayerIndex <= 0) {
            return false;
        }
        selectedLayerIndex--;
        return true;
    }

    public boolean selectNextLayer() {
        if (matrixLayers.isEmpty() || selectedLayerIndex >= matrixLayers.size() - 1) {
            return false;
        }
        selectedLayerIndex++;
        return true;
    }

    public void markTextureResident(String texturePath, long bytesAssigned) {
        if (texturePath == null || texturePath.isBlank() || bytesAssigned <= 0L) {
            return;
        }
        if (residentTexturePaths.add(texturePath)) {
            residentTexturesFifo.addLast(texturePath);
            gpuTextureBytesAssigned += bytesAssigned;
        }
    }

    public void unmarkTextureResident(String texturePath, long bytesAssigned) {
        if (texturePath == null || texturePath.isBlank()) {
            return;
        }
        if (residentTexturePaths.remove(texturePath)) {
            residentTexturesFifo.remove(texturePath);
            gpuTextureBytesAssigned = Math.max(0L, gpuTextureBytesAssigned - Math.max(0L, bytesAssigned));
        }
    }

    public String popOldestResidentTexturePath() {
        return residentTexturesFifo.pollFirst();
    }

    public long getGpuTextureBytesAssigned() {
        return gpuTextureBytesAssigned;
    }

    private void normalizeSelection() {
        if (matrixLayers.isEmpty()) {
            selectedLayerIndex = 0;
            return;
        }
        if (selectedLayerIndex < 0) {
            selectedLayerIndex = 0;
        }
        else if (selectedLayerIndex >= matrixLayers.size()) {
            selectedLayerIndex = matrixLayers.size() - 1;
        }
    }
}
