package planetdemviewer.render.jogl4;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import java.awt.Color;
import java.awt.Font;
import java.util.List;
import java.util.Locale;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.render.FrameRateCounter;
import planetdemviewer.render.HudFrameStats;
import planetdemviewer.render.View;
import planetdemviewer.render.ViewportLayout;
import planetdemviewer.scene.TerrainSurfaceSelection;
import planetdemviewer.terrain.TerrainSubdivisionScheme;

/** Writes the localized status text, the frame statistics and the selected surface point over the canvas. */
final class Jogl4HudRenderer {
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final Color SELECTION_COLOR = new Color(45, 125, 205);
    private static final int STATUS_LEFT = 16;
    private static final int STATUS_TOP_MARGIN = 24;
    private static final int STATUS_LINE_HEIGHT = 22;

    private final PlanetViewerModel model;
    private final ViewportLayout viewports;
    private final FrameRateCounter frameRate = new FrameRateCounter(System.nanoTime());
    private Jogl4TextRenderer statusText;
    private Jogl4TextRenderer selectionText;
    private Jogl4TextRenderer performanceText;

    Jogl4HudRenderer(PlanetViewerModel model, ViewportLayout viewports) {
        this.model = model;
        this.viewports = viewports;
    }

    void init() {
        statusText = new Jogl4TextRenderer(new Font("SansSerif", Font.BOLD, 16));
        selectionText = new Jogl4TextRenderer(new Font("Arial", Font.PLAIN, 32));
        performanceText = new Jogl4TextRenderer(new Font("Arial", Font.BOLD, 32));
    }

    void dispose(GL4 gl) {
        if (statusText == null) {
            return;
        }
        statusText.dispose(gl);
        selectionText.dispose(gl);
        performanceText.dispose(gl);
        statusText = null;
        selectionText = null;
        performanceText = null;
    }

    void draw(GL4 gl, int canvasWidth, int canvasHeight, HudFrameStats stats) {
        frameRate.frameRendered(System.nanoTime());
        if (statusText == null) {
            return;
        }
        gl.glDisable(GL.GL_DEPTH_TEST);
        int baseline = canvasHeight - STATUS_TOP_MARGIN;
        for (String line : statusLines(stats)) {
            statusText.draw(gl, line, STATUS_LEFT, baseline, canvasWidth, canvasHeight);
            baseline -= STATUS_LINE_HEIGHT;
        }
        drawPerformance(gl, canvasWidth, canvasHeight, stats);
        drawSelection(gl, canvasWidth, canvasHeight);
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

    private void drawPerformance(GL4 gl, int canvasWidth, int canvasHeight, HudFrameStats stats) {
        int right = canvasWidth - 18;
        performanceText.drawRightAligned(gl, model.text("IDM_HUD_FPS", frameRate.framesPerSecond()),
            right, 94, canvasWidth, canvasHeight, Color.YELLOW);
        performanceText.drawRightAligned(gl, model.text("IDM_HUD_TILE_RAM", formatBytes(stats.tileRamBytes())),
            right, 58, canvasWidth, canvasHeight, Color.YELLOW);
        performanceText.drawRightAligned(gl, model.text("IDM_HUD_TILE_GPU", formatBytes(stats.totalGpuBytes())),
            right, 22, canvasWidth, canvasHeight, Color.YELLOW);
    }

    private void drawSelection(GL4 gl, int canvasWidth, int canvasHeight) {
        TerrainSurfaceSelection selection = model.getTerrainSurfaceSelection();
        if (selection == null) {
            return;
        }
        selectionText.draw(gl, model.text("IDM_HUD_SURFACE",
            String.format(Locale.ROOT, "%.6f", selection.position().longitude()),
            String.format(Locale.ROOT, "%.6f", selection.position().latitude()),
            String.format(Locale.ROOT, "%.2f", selection.heightAboveMeanSeaLevelMetres())),
            16, 76, canvasWidth, canvasHeight, SELECTION_COLOR);
        selectionText.draw(gl, model.text("IDM_HUD_SELECTED_TILE",
            selection.tile().quadkey(), selection.tile().level(),
            String.format(Locale.ROOT, "%.2f", selection.geoidXMetres()),
            String.format(Locale.ROOT, "%.2f", selection.geoidYMetres())),
            16, 38, canvasWidth, canvasHeight, SELECTION_COLOR);
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
