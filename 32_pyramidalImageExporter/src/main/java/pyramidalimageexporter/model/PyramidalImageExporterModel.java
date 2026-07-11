package pyramidalimageexporter.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.material.RendererConfiguration;

public final class PyramidalImageExporterModel {
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

    public PyramidalImageExporterModel() {
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
