package dumpanalyzer.gui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.List;

import dumpanalyzer.Vector3DMixin;
import dumpanalyzer.model.DumpAnalyzerModel;
import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import vsdk.toolkit.gui.AwtSystem;
import vsdk.toolkit.gui.CameraControllerOrbiter;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

public final class KeyboardInteractionTechnique implements KeyListener {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final DefaultPrettyPrinter JSON_PRETTY_PRINTER = createPrettyPrinter();
    static {
        JSON_MAPPER.addMixIn(Vector3D.class, Vector3DMixin.class);
    }

    private final DumpAnalyzerModel model;
    private final Runnable closeAction;
    private final CameraControllerOrbiter cameraController;
    private final Runnable repaintAction;

    public KeyboardInteractionTechnique(
        DumpAnalyzerModel model,
        Runnable closeAction,
        CameraControllerOrbiter cameraController,
        Runnable repaintAction
    ) {
        this.model = model;
        this.closeAction = closeAction;
        this.cameraController = cameraController;
        this.repaintAction = repaintAction;
    }

    @Override
    public void keyPressed(KeyEvent e) {
        vsdk.toolkit.gui.KeyEvent event = AwtSystem.awt2vsdkEvent(e);
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ESCAPE -> closeAction.run();
            case KeyEvent.VK_1 -> {
                model.selectPreviousFrame();
            }
            case KeyEvent.VK_2 -> {
                model.selectNextFrame();
            }
            case KeyEvent.VK_3 -> model.selectPreviousTile();
            case KeyEvent.VK_4 -> model.selectNextTile();
            default -> {
                if (model.processRendererConfigurationKey(event)) {
                    repaintAction.run();
                }
                else if (cameraController != null && cameraController.processKeyPressedEvent(event)) {
                    repaintAction.run();
                }
            }
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (cameraController != null && cameraController.processKeyReleasedEvent(AwtSystem.awt2vsdkEvent(e))) {
            repaintAction.run();
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }

    private void printSelectedFrameJson() {
        DumpAnalyzerModel.HudState state = model.snapshotHudState();
        List<Frame> frames = model.snapshotFrames();

        int idx = state.selectedFrameIndex();
        if (idx < 0 || idx >= frames.size()) {
            System.out.println("[dumpAnalyzer] No frame selected.");
            return;
        }

        Frame selected = frames.get(idx);
        try {
            String json = JSON_MAPPER.writer(JSON_PRETTY_PRINTER).writeValueAsString(selected);
            String aabbSummary = buildGlobalAabbSummary(selected);
            System.out.println("[dumpAnalyzer] Selected frame JSON:\n" + json + "\n---------------\n" + aabbSummary);
        } catch (JsonProcessingException ex) {
            System.out.println("[dumpAnalyzer] Could not serialize selected frame: " + ex.getMessage());
        }
    }

    private static String buildGlobalAabbSummary(Frame frame) {
        Vector3D min = null;
        Vector3D max = null;
        for (TileInstance tile : frame.getTiles()) {
            if (tile.getMin() == null || tile.getMax() == null) {
                continue;
            }
            if (min == null) {
                min = tile.getMin();
                max = tile.getMax();
                continue;
            }
            min = new Vector3D(
                Math.min(min.x(), tile.getMin().x()),
                Math.min(min.y(), tile.getMin().y()),
                Math.min(min.z(), tile.getMin().z())
            );
            max = new Vector3D(
                Math.max(max.x(), tile.getMax().x()),
                Math.max(max.y(), tile.getMax().y()),
                Math.max(max.z(), tile.getMax().z())
            );
        }

        if (min == null || max == null) {
            return "Global AABB: null";
        }
        return "Global AABB:\n  min = " + min + "\n  max = " + max;
    }

    private static DefaultPrettyPrinter createPrettyPrinter() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", System.lineSeparator());
        printer = printer.withArrayIndenter(indenter);
        printer = printer.withObjectIndenter(indenter);
        return printer;
    }
}
