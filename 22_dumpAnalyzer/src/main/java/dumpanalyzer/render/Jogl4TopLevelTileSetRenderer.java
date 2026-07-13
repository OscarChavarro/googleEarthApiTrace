package dumpanalyzer.render;

import com.jogamp.opengl.GL2;
import dumpanalyzer.model.TopLevelTileSet;
import dumpanalyzer.model.TileInstance;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

final class Jogl4TopLevelTileSetRenderer {
    private Jogl4TopLevelTileSetRenderer() {
    }

    static void drawWireOverlay(GL2 gl2, TileInstance tile, TopLevelTileSet globeLevelTileSet) {
        if (gl2 == null || tile == null || globeLevelTileSet == null) {
            return;
        }
        gl2.glColor3d(128.0 / 255.0, 1.0, 128.0 / 255.0);
        gl2.glLineWidth(1.0f);
        for (List<Vector3Dd> strip : tile.getStrips()) {
            if (strip == null || strip.size() < 2) {
                continue;
            }
            gl2.glBegin(GL2.GL_LINE_STRIP);
            for (Vector3Dd p : strip) {
                gl2.glVertex3d(p.x(), p.y(), p.z());
            }
            gl2.glEnd();
        }
    }
}
