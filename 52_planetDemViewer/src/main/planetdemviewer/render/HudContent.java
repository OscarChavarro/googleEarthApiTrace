package planetdemviewer.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.scene.TerrainSurfaceSelection;
import planetdemviewer.terrain.TerrainSubdivisionScheme;
import vsdk.toolkit.common.color.ColorRgb;

/** Composes the localized status text, the frame statistics and the selected surface point. */
public final class HudContent {
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final ColorRgb SELECTION_COLOR = new ColorRgb(45 / 255.0, 125 / 255.0, 205 / 255.0);
    private static final int STATUS_LEFT = 16;
    private static final int STATUS_TOP_MARGIN = 24;
    private static final int STATUS_LINE_HEIGHT = 22;
    private static final int PERFORMANCE_RIGHT_MARGIN = 18;

    private final PlanetViewerModel model;
    private final ViewportLayout viewports;
    private final FrameRateCounter frameRate = new FrameRateCounter(System.nanoTime());

    HudContent(PlanetViewerModel model, ViewportLayout viewports) {
        this.model = model;
        this.viewports = viewports;
    }

    void frameRendered() {
        frameRate.frameRendered(System.nanoTime());
    }

    List<TextLabel> labels(int canvasWidth, int canvasHeight, HudFrameStats stats) {
        List<TextLabel> labels = new ArrayList<>();
        int baseline = canvasHeight - STATUS_TOP_MARGIN;
        for (String line : statusLines(stats)) {
            labels.add(TextLabel.left(line, TextStyle.STATUS, STATUS_LEFT, baseline, TextLabel.WHITE));
            baseline -= STATUS_LINE_HEIGHT;
        }
        int right = canvasWidth - PERFORMANCE_RIGHT_MARGIN;
        labels.add(TextLabel.rightAligned(model.text("IDM_HUD_FPS", frameRate.framesPerSecond()),
            TextStyle.PERFORMANCE, right, 94, TextLabel.YELLOW));
        labels.add(TextLabel.rightAligned(model.text("IDM_HUD_TILE_RAM", formatBytes(stats.tileRamBytes())),
            TextStyle.PERFORMANCE, right, 58, TextLabel.YELLOW));
        labels.add(TextLabel.rightAligned(model.text("IDM_HUD_TILE_GPU", formatBytes(stats.totalGpuBytes())),
            TextStyle.PERFORMANCE, right, 22, TextLabel.YELLOW));
        TerrainSurfaceSelection selection = model.getTerrainSurfaceSelection();
        if (selection != null) {
            labels.add(TextLabel.left(model.text("IDM_HUD_SURFACE",
                String.format(Locale.ROOT, "%.6f", selection.position().longitude()),
                String.format(Locale.ROOT, "%.6f", selection.position().latitude()),
                String.format(Locale.ROOT, "%.2f", selection.heightAboveMeanSeaLevelMetres())),
                TextStyle.SELECTION, 16, 76, SELECTION_COLOR));
            labels.add(TextLabel.left(model.text("IDM_HUD_SELECTED_TILE",
                selection.tile().quadkey(), selection.tile().level(),
                String.format(Locale.ROOT, "%.2f", selection.geoidXMetres()),
                String.format(Locale.ROOT, "%.2f", selection.geoidYMetres())),
                TextStyle.SELECTION, 16, 38, SELECTION_COLOR));
        }
        return labels;
    }

    List<String> statusLines(HudFrameStats stats) {
        return List.of(
            selectedImageLabel(stats),
            model.text("IDM_HUD_CAMERA", model.getCurrentPSC(),
                String.format("%.4f", viewports.mainView().getCamera().getPosition().z()),
                stats.residentTextures(),
                stats.rasterGpuBytes() / BYTES_PER_MB,
                viewSummary()),
            model.text("IDM_HUD_MODE", operationModeLabel(),
                String.format("%.4gx", model.getHeightExagerationFactor()),
                stats.residentMeshes(),
                stats.terrainGpuBytes() / BYTES_PER_MB,
                model.text(stats.uniformLod() ? "IDM_HUD_ON" : "IDM_HUD_OFF"),
                subdivisionSchemeLabel()),
            model.text("IDM_HUD_PALETTE", model.getPalettes().selectedName(),
                model.getPalettes().minimumElevation(), model.getPalettes().maximumElevation()),
            model.text("IDM_HUD_KEYS"));
    }

    private String viewSummary() {
        String selected = viewTitle(model, viewports, viewports.selectedView());
        return viewports.isFullScreen()
            ? model.text("IDM_HUD_VIEWS_FULL_SCREEN", viewports.views().size(), selected)
            : model.text("IDM_HUD_VIEWS_LAYOUT", viewports.views().size(), viewports.layoutStyle(), selected);
    }

    static String viewTitle(PlanetViewerModel model, ViewportLayout viewports, View view) {
        return model.text("IDM_HUD_VIEW_TITLE", viewports.numberOf(view));
    }

    private String subdivisionSchemeLabel() {
        return model.text(model.getTerrainSubdivisionScheme() == TerrainSubdivisionScheme.DIAMOND_SQUARE
            ? "IDM_LAYER_SUBDIVISION_DIAMOND_SQUARE" : "IDM_LAYER_SUBDIVISION_TRIANGLE_EDGE");
    }

    private String operationModeLabel() {
        return model.text(model.getOperationMode() == OperationModes.BASIC_TRIANGULATION
            ? "IDM_LAYER_ELEVATION_MESH" : "IDM_LAYER_ELEVATION_PALETTE");
    }

    private String selectedImageLabel(HudFrameStats stats) {
        PyramidalImageInstance selected = model.getSelectedInstance();
        if (selected == null) {
            return model.text("IDM_HUD_NO_IMAGE");
        }
        return model.text("IDM_HUD_IMAGE", model.getSelectedIndex() + 1, model.getInstanceCount(),
            selected.getImage().getSourceFolder(),
            selected.getImage().getTileCount(), selected.getImage().getHeight(),
            stats.pendingTreeDiscoveries(),
            String.format("%.3f", selected.getOpacity()),
            String.format("%.2f", selected.getZOffset()));
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        }
        while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
