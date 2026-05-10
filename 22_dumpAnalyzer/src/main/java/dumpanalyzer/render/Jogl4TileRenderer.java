package dumpanalyzer.render;

import java.util.List;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL4;

import dumpanalyzer.model.DumpAnalyzerModel;
import dumpanalyzer.model.Line;
import dumpanalyzer.model.TileInstance;
import vsdk.toolkit.common.RendererConfiguration;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.common.linealAlgebra.Vector3D;
import vsdk.toolkit.environment.camera.Camera;

final class Jogl4TileRenderer {
    private static final float SURFACE_POLYGON_OFFSET_FACTOR = 1.0f;
    private static final float SURFACE_POLYGON_OFFSET_UNITS = 1.0f;
    private static final float POINT_POLYGON_OFFSET_FACTOR = -2.0f;
    private static final float POINT_POLYGON_OFFSET_UNITS = -2.0f;
    private static final float LINE_POLYGON_OFFSET_FACTOR = -4.0f;
    private static final float LINE_POLYGON_OFFSET_UNITS = -4.0f;

    private Jogl4TileRenderer() {
    }

    static void drawTile(
        GL4 gl,
        GL2 gl2,
        TileInstance tile,
        int frameId,
        Matrix4x4 projection,
        double[] frameModelViewMatrix,
        boolean drawAabb,
        boolean singleTileMode,
        DumpAnalyzerModel model,
        Jogl4HudRenderer hudRenderer,
        Camera camera
    ) {
        RendererConfiguration quality = model.getRendererConfiguration();
        boolean fullResolution = tile.isFullResolutionWithRespectToTexture();
        boolean forceRedWires = !fullResolution;
        boolean multiPrimitiveNonFullRes = forceRedWires && tile.getStrips() != null && tile.getStrips().size() > 1;
        boolean textured = quality.isTextureSet();
        int activeTextureId = 0;
        if (textured) {
            activeTextureId = hudRenderer.activateTexture(gl, model, model.getTexturePath(frameId, tile.getContentId()));
            if (activeTextureId > 0) {
                gl2.glActiveTexture(GL2.GL_TEXTURE0);
                gl2.glEnable(GL2.GL_TEXTURE_2D);
                gl2.glBindTexture(GL2.GL_TEXTURE_2D, activeTextureId);
            }
            else {
                textured = false;
            }
        }
        float[] mvp = projection.exportToFloatArrayColumnOrder();
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glPushMatrix();
        gl2.glLoadMatrixf(mvp, 0);
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glPushMatrix();
        if (frameModelViewMatrix != null && frameModelViewMatrix.length == 16) {
            float[] mv = new float[16];
            for (int i = 0; i < 16; i++) {
                mv[i] = (float)frameModelViewMatrix[i];
            }
            gl2.glLoadMatrixf(mv, 0);
        }
        else {
            gl2.glLoadIdentity();
        }
        if (drawAabb && textured && activeTextureId > 0) {
            gl2.glActiveTexture(GL2.GL_TEXTURE0);
            gl2.glEnable(GL2.GL_TEXTURE_2D);
            gl2.glBindTexture(GL2.GL_TEXTURE_2D, activeTextureId);
        }
        if (quality.isSurfacesSet()) {
            gl2.glDisable(GL2.GL_LIGHTING);
            gl2.glEnable(GL2.GL_DEPTH_TEST);
            gl2.glDepthMask(true);
            gl2.glDepthFunc(GL2.GL_LESS);
            gl2.glEnable(GL2.GL_POLYGON_OFFSET_FILL);
            gl2.glPolygonOffset(SURFACE_POLYGON_OFFSET_FACTOR, SURFACE_POLYGON_OFFSET_UNITS);
            gl2.glColor3d(0.85, 0.85, 0.85);
            List<List<Vector3D>> strips = tile.getStrips();
            List<List<Vector3D>> stripTexCoords = tile.getStripTexCoords();
            for (int stripIndex = 0; stripIndex < strips.size(); stripIndex++) {
                List<Vector3D> strip = strips.get(stripIndex);
                if (strip.size() < 3) {
                    continue;
                }
                List<Vector3D> uvStrip = stripIndex < stripTexCoords.size() ? stripTexCoords.get(stripIndex) : List.of();
                gl2.glBegin(GL2.GL_TRIANGLE_STRIP);
                for (int i = 0; i < strip.size(); i++) {
                    Vector3D p = strip.get(i);
                    if (textured && i < uvStrip.size()) {
                        Vector3D uv = uvStrip.get(i);
                        gl2.glTexCoord2d(uv.x(), uv.y());
                    }
                    gl2.glVertex3d(p.x(), p.y(), p.z());
                }
                gl2.glEnd();
            }
            gl2.glDisable(GL2.GL_POLYGON_OFFSET_FILL);
        }
        if (quality.isWiresSet() || forceRedWires) {
            if (textured) {
                gl2.glBindTexture(GL2.GL_TEXTURE_2D, 0);
                gl2.glDisable(GL2.GL_TEXTURE_2D);
            }
            gl2.glDisable(GL2.GL_LIGHTING);
            gl2.glEnable(GL2.GL_DEPTH_TEST);
            gl2.glDepthMask(false);
            gl2.glDepthFunc(GL2.GL_LEQUAL);
            if (forceRedWires) {
                if (multiPrimitiveNonFullRes) {
                    gl2.glColor3d(128.0 / 255.0, 1.0, 1.0);
                }
                else {
                    gl2.glColor3d(1.0, 0.0, 0.0);
                }
            }
            else {
                gl2.glColor3d(1.0, 1.0, 1.0);
            }
            gl2.glLineWidth(1.0f);
            for (List<Vector3D> strip : tile.getStrips()) {
                if (strip.size() < 2) {
                    continue;
                }
                gl2.glBegin(GL2.GL_LINE_STRIP);
                for (Vector3D p : strip) {
                    gl2.glVertex3d(p.x(), p.y(), p.z());
                }
                gl2.glEnd();
            }
        }
        if (quality.isPointsSet()) {
            if (textured) {
                gl2.glBindTexture(GL2.GL_TEXTURE_2D, 0);
                gl2.glDisable(GL2.GL_TEXTURE_2D);
            }
            gl2.glDisable(GL2.GL_LIGHTING);
            gl2.glEnable(GL2.GL_DEPTH_TEST);
            gl2.glDepthMask(false);
            gl2.glDepthFunc(GL2.GL_LEQUAL);
            gl2.glEnable(GL2.GL_POLYGON_OFFSET_POINT);
            gl2.glPolygonOffset(POINT_POLYGON_OFFSET_FACTOR, POINT_POLYGON_OFFSET_UNITS);
            gl2.glPointSize(3.0f);
            Jogl4TileVertexRenderer.drawTilePoints(gl2, tile, singleTileMode);
            gl2.glDisable(GL2.GL_POLYGON_OFFSET_POINT);
        }
        gl2.glDepthMask(true);
        gl2.glDepthFunc(GL2.GL_LESS);

        gl2.glPopMatrix();
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glPopMatrix();
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        if (textured) {
            gl2.glBindTexture(GL2.GL_TEXTURE_2D, 0);
            gl2.glDisable(GL2.GL_TEXTURE_2D);
        }
    }

    static void drawExtractedLineStrips(
        GL2 gl2,
        List<Line> lines,
        Matrix4x4 projection,
        double[] defaultModelViewMatrix
    ) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        float[] mvp = projection.exportToFloatArrayColumnOrder();
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glPushMatrix();
        gl2.glLoadMatrixf(mvp, 0);
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glPushMatrix();

        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glDisable(GL2.GL_LIGHTING);
        gl2.glEnable(GL2.GL_DEPTH_TEST);
        gl2.glDepthMask(false);
        gl2.glDepthFunc(GL2.GL_LEQUAL);
        gl2.glEnable(GL2.GL_POLYGON_OFFSET_LINE);
        gl2.glPolygonOffset(LINE_POLYGON_OFFSET_FACTOR, LINE_POLYGON_OFFSET_UNITS);
        gl2.glColor3d(1.0, 1.0, 0.0);
        gl2.glLineWidth(2.0f);
        for (Line line : lines) {
            List<Vector3D> lineStrip = line == null ? List.of() : line.getPoints();
            if (lineStrip.size() < 2) {
                continue;
            }
            double[] modelViewMatrix = line == null ? null : line.getModelViewMatrix();
            if (modelViewMatrix == null || modelViewMatrix.length != 16) {
                modelViewMatrix = defaultModelViewMatrix;
            }
            if (modelViewMatrix != null && modelViewMatrix.length == 16) {
                float[] mv = new float[16];
                for (int i = 0; i < 16; i++) {
                    mv[i] = (float)modelViewMatrix[i];
                }
                gl2.glLoadMatrixf(mv, 0);
            }
            else {
                gl2.glLoadIdentity();
            }
            gl2.glBegin(GL2.GL_LINE_STRIP);
            for (Vector3D p : lineStrip) {
                gl2.glVertex3d(p.x(), p.y(), p.z());
            }
            gl2.glEnd();
        }
        gl2.glDisable(GL2.GL_POLYGON_OFFSET_LINE);
        gl2.glDepthMask(true);
        gl2.glDepthFunc(GL2.GL_LESS);

        gl2.glPopMatrix();
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glPopMatrix();
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
    }
}
