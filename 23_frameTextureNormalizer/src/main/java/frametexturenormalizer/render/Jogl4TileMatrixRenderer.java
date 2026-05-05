package frametexturenormalizer.render;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureCoords;
import com.jogamp.opengl.util.texture.TextureIO;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.model.Line;
import frametexturenormalizer.model.PyramidalImageModel;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.model.TileInstance.TriangleStripGeometry;
import frametexturenormalizer.model.TileInstance.TriangleStripVertex;
import vsdk.toolkit.common.RendererConfiguration;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;

public final class Jogl4TileMatrixRenderer {
    private static final float LINE_BASE_WIDTH = 4.0f;
    private static final float LINE_HIGHLIGHT_WIDTH = 2.25f;
    private static final float LINE_BASE_OFFSET_FACTOR = -10.0f;
    private static final float LINE_BASE_OFFSET_UNITS = -10.0f;
    private static final float LINE_HIGHLIGHT_OFFSET_FACTOR = -14.0f;
    private static final float LINE_HIGHLIGHT_OFFSET_UNITS = -14.0f;
    private final Map<String, TextureResident> residentsByTexturePath = new HashMap<>();

    public void draw(
        GL2 gl2,
        List<TileInstance> tiles,
        List<Line> lines,
        Matrix4x4 projection,
        double[] defaultModelViewMatrix,
        RendererConfiguration renderingConfiguration,
        PyramidalImageModel model,
        int selectedTileIndex
    ) {
        if (gl2 == null || tiles == null || renderingConfiguration == null || model == null) {
            return;
        }
        if (renderingConfiguration.isSurfacesSet()) {
            gl2.glEnable(GL2.GL_POLYGON_OFFSET_FILL);
            gl2.glPolygonOffset(1.0f, 1.0f);
            drawSurfaces(gl2, tiles, defaultModelViewMatrix, renderingConfiguration, model, selectedTileIndex);
            gl2.glDisable(GL2.GL_POLYGON_OFFSET_FILL);
        }
        if (renderingConfiguration.isWiresSet()) {
            gl2.glDisable(GL2.GL_TEXTURE_2D);
            gl2.glColor3d(1.0, 1.0, 1.0);
            gl2.glLineWidth(1.25f);
            for (int i = 0; i < tiles.size(); i++) {
                if (selectedTileIndex != PyramidalImageModel.SELECT_ALL_TILES && selectedTileIndex != i) {
                    continue;
                }
                drawWire(gl2, tiles.get(i), defaultModelViewMatrix);
            }
        }
        if (renderingConfiguration.isPointsSet()) {
            gl2.glDisable(GL2.GL_TEXTURE_2D);
            gl2.glColor3d(1.0, 0.0, 0.0);
            gl2.glPointSize(3.0f);
            for (int i = 0; i < tiles.size(); i++) {
                if (selectedTileIndex != PyramidalImageModel.SELECT_ALL_TILES && selectedTileIndex != i) {
                    continue;
                }
                drawPoints(gl2, tiles.get(i), defaultModelViewMatrix);
            }
        }
        drawIncorrectMappingWires(gl2, tiles, selectedTileIndex, defaultModelViewMatrix);
        drawWestCuttingCellsOverlay(gl2, tiles, selectedTileIndex, defaultModelViewMatrix);
        drawSelectedTilesOverlay(gl2, tiles, selectedTileIndex, defaultModelViewMatrix);
        drawExtractedLines(gl2, lines, projection, defaultModelViewMatrix);
    }

    public void drawForSelection(GL2 gl2, List<TileInstance> tiles, int selectedTileIndex, double[] defaultModelViewMatrix) {
        if (gl2 == null || tiles == null || tiles.isEmpty()) {
            return;
        }
        for (int i = 0; i < tiles.size(); i++) {
            if (selectedTileIndex != PyramidalImageModel.SELECT_ALL_TILES && selectedTileIndex != i) {
                continue;
            }
            TileInstance tile = tiles.get(i);
            if (tile == null || tile.isWestCuttingCell()) {
                continue;
            }
            gl2.glLoadName(tile.getTileId());
            drawFlatGeometry(gl2, tile, defaultModelViewMatrix);
        }
    }

    private static void drawWestCuttingCellsOverlay(
        GL2 gl2,
        List<TileInstance> tiles,
        int selectedTileIndex,
        double[] defaultModelViewMatrix
    ) {
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glEnable(GL2.GL_POLYGON_OFFSET_LINE);
        gl2.glPolygonOffset(-1.0f, -1.0f);
        gl2.glPolygonMode(GL2.GL_FRONT_AND_BACK, GL2.GL_LINE);
        gl2.glLineWidth(2.0f);
        gl2.glColor3d(1.0, 0.0, 0.0);
        for (int i = 0; i < tiles.size(); i++) {
            if (selectedTileIndex != PyramidalImageModel.SELECT_ALL_TILES && selectedTileIndex != i) {
                continue;
            }
            TileInstance tile = tiles.get(i);
            if (tile == null || !tile.isWestCuttingCell()) {
                continue;
            }
            drawFlatGeometry(gl2, tile, defaultModelViewMatrix);
        }
        gl2.glLineWidth(1.0f);
        gl2.glPolygonMode(GL2.GL_FRONT_AND_BACK, GL2.GL_FILL);
        gl2.glDisable(GL2.GL_POLYGON_OFFSET_LINE);
    }

    private void drawIncorrectMappingWires(
        GL2 gl2,
        List<TileInstance> tiles,
        int selectedTileIndex,
        double[] defaultModelViewMatrix
    ) {
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glColor3d(1.0, 0.0, 0.0);
        gl2.glLineWidth(2.5f);
        for (int i = 0; i < tiles.size(); i++) {
            if (selectedTileIndex != PyramidalImageModel.SELECT_ALL_TILES && selectedTileIndex != i) {
                continue;
            }
            TileInstance tile = tiles.get(i);
            if (tile == null || !tile.isIncorrectMatrixMapping()) {
                continue;
            }
            drawWire(gl2, tile, defaultModelViewMatrix);
        }
        gl2.glLineWidth(1.0f);
    }

    private void drawSurfaces(
        GL2 gl2,
        List<TileInstance> tiles,
        double[] defaultModelViewMatrix,
        RendererConfiguration renderingConfiguration,
        PyramidalImageModel model,
        int selectedTileIndex
    ) {
        boolean textured = renderingConfiguration.isTextureSet();
        if (textured) {
            gl2.glEnable(GL2.GL_TEXTURE_2D);
        }
        int lastBound = -1;
        for (int i = 0; i < tiles.size(); i++) {
            if (selectedTileIndex != PyramidalImageModel.SELECT_ALL_TILES && selectedTileIndex != i) {
                continue;
            }
            TileInstance tile = tiles.get(i);
            if (tile == null) {
                continue;
            }
            if (!textured) {
                gl2.glColor3d(0.9, 0.9, 0.9);
                drawFlatGeometry(gl2, tile, defaultModelViewMatrix);
                continue;
            }

            TextureResident resident = acquireTexture(gl2, model, tile.getTextureFile());
            if (resident == null || resident.texture() == null) {
                gl2.glDisable(GL2.GL_TEXTURE_2D);
                gl2.glColor3d(0.7, 0.7, 0.7);
                drawFlatGeometry(gl2, tile, defaultModelViewMatrix);
                gl2.glEnable(GL2.GL_TEXTURE_2D);
                lastBound = -1;
                continue;
            }
            int texId = resident.texture().getTextureObject(gl2);
            if (texId != lastBound) {
                resident.texture().bind(gl2);
                lastBound = texId;
            }
            gl2.glColor3d(1.0, 1.0, 1.0);
            drawTexturedGeometry(gl2, tile, resident.texture().getImageTexCoords(), defaultModelViewMatrix);
        }
        gl2.glBindTexture(GL2.GL_TEXTURE_2D, 0);
        gl2.glDisable(GL2.GL_TEXTURE_2D);
    }

    private static void drawSelectedTilesOverlay(
        GL2 gl2,
        List<TileInstance> tiles,
        int selectedTileIndex,
        double[] defaultModelViewMatrix
    ) {
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glEnable(GL2.GL_POLYGON_OFFSET_LINE);
        gl2.glPolygonOffset(-1.0f, -1.0f);
        gl2.glPolygonMode(GL2.GL_FRONT_AND_BACK, GL2.GL_LINE);
        gl2.glLineWidth(3.0f);
        gl2.glColor3d(1.0, 1.0, 0.0);
        for (int i = 0; i < tiles.size(); i++) {
            if (selectedTileIndex != PyramidalImageModel.SELECT_ALL_TILES && selectedTileIndex != i) {
                continue;
            }
            TileInstance tile = tiles.get(i);
            if (tile == null || !tile.isSelected()) {
                continue;
            }
            drawFlatGeometry(gl2, tile, defaultModelViewMatrix);
        }
        gl2.glLineWidth(1.0f);
        gl2.glPolygonMode(GL2.GL_FRONT_AND_BACK, GL2.GL_FILL);
        gl2.glDisable(GL2.GL_POLYGON_OFFSET_LINE);
    }

    private static void drawFlatGeometry(GL2 gl2, TileInstance tile, double[] defaultModelViewMatrix) {
        TriangleStripGeometry strip = tile == null ? null : tile.getTriangleStrip();
        if (strip == null || strip.vertices() == null || strip.vertices().size() < 3) {
            return;
        }
        gl2.glPushMatrix();
        applyModelView(gl2, tile, defaultModelViewMatrix);
        gl2.glBegin(GL2.GL_TRIANGLE_STRIP);
        for (TriangleStripVertex v : strip.vertices()) {
            gl2.glVertex3d(v.x(), v.y(), v.z());
        }
        gl2.glEnd();
        gl2.glPopMatrix();
    }

    private static void drawTexturedGeometry(
        GL2 gl2,
        TileInstance tile,
        TextureCoords tc,
        double[] defaultModelViewMatrix
    ) {
        TriangleStripGeometry strip = tile == null ? null : tile.getTriangleStrip();
        if (strip == null || strip.vertices() == null || strip.vertices().size() < 3 || tc == null) {
            return;
        }
        float s0 = tc.left();
        float t0 = tc.bottom();
        float s1 = tc.right();
        float t1 = tc.top();
        float v0 = 1.0f - t0;
        float v1 = 1.0f - t1;
        gl2.glPushMatrix();
        applyModelView(gl2, tile, defaultModelViewMatrix);
        gl2.glBegin(GL2.GL_TRIANGLE_STRIP);
        for (TriangleStripVertex v : strip.vertices()) {
            float s = (float)(s0 + v.u() * (s1 - s0));
            float t = (float)(v0 + (1.0 - v.v()) * (v1 - v0));
            gl2.glTexCoord2f(s, t);
            gl2.glVertex3d(v.x(), v.y(), v.z());
        }
        gl2.glEnd();
        gl2.glPopMatrix();
    }

    private static void drawWire(GL2 gl2, TileInstance tile, double[] defaultModelViewMatrix) {
        TriangleStripGeometry strip = tile == null ? null : tile.getTriangleStrip();
        if (strip == null || strip.vertices() == null || strip.vertices().size() < 3) {
            return;
        }
        gl2.glPushMatrix();
        applyModelView(gl2, tile, defaultModelViewMatrix);
        gl2.glBegin(GL2.GL_LINE_STRIP);
        for (TriangleStripVertex v : strip.vertices()) {
            gl2.glVertex3d(v.x(), v.y(), v.z());
        }
        gl2.glEnd();
        gl2.glPopMatrix();
    }

    private static void drawPoints(GL2 gl2, TileInstance tile, double[] defaultModelViewMatrix) {
        TriangleStripGeometry strip = tile == null ? null : tile.getTriangleStrip();
        if (strip == null || strip.vertices() == null || strip.vertices().isEmpty()) {
            return;
        }
        gl2.glPushMatrix();
        applyModelView(gl2, tile, defaultModelViewMatrix);
        gl2.glBegin(GL2.GL_POINTS);
        for (TriangleStripVertex v : strip.vertices()) {
            gl2.glVertex3d(v.x(), v.y(), v.z());
        }
        gl2.glEnd();
        gl2.glPopMatrix();
    }

    private static void applyModelView(GL2 gl2, TileInstance tile, double[] defaultModelViewMatrix) {
        double[] modelView = tile == null ? null : tile.getModelViewMatrix();
        if (modelView == null || modelView.length != 16) {
            modelView = defaultModelViewMatrix;
        }
        if (modelView == null || modelView.length != 16) {
            gl2.glLoadIdentity();
            return;
        }
        float[] mv = new float[16];
        for (int i = 0; i < 16; i++) {
            mv[i] = (float)modelView[i];
        }
        gl2.glLoadMatrixf(mv, 0);
    }

    private static void drawExtractedLines(
        GL2 gl2,
        List<Line> lines,
        Matrix4x4 projection,
        double[] defaultModelViewMatrix
    ) {
        if (gl2 == null || lines == null || lines.isEmpty()) {
            return;
        }
        float[] mvp = projection == null ? null : projection.exportToFloatArrayColumnOrder();
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glPushMatrix();
        if (mvp != null) {
            gl2.glLoadMatrixf(mvp, 0);
        }
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glPushMatrix();
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glDisable(GL2.GL_LIGHTING);
        gl2.glEnable(GL2.GL_DEPTH_TEST);
        gl2.glDepthMask(false);
        gl2.glDepthFunc(GL2.GL_LEQUAL);
        gl2.glEnable(GL2.GL_POLYGON_OFFSET_LINE);
        for (Line line : lines) {
            List<Line.Vertex> lineStrip = line == null ? List.of() : line.getPoints();
            if (lineStrip.size() < 2) {
                continue;
            }
            double[] modelView = line == null ? null : line.getModelViewMatrix();
            if (modelView == null || modelView.length != 16) {
                modelView = defaultModelViewMatrix;
            }
            if (modelView != null && modelView.length == 16) {
                float[] mv = new float[16];
                for (int i = 0; i < 16; i++) {
                    mv[i] = (float)modelView[i];
                }
                gl2.glLoadMatrixf(mv, 0);
            }
            else {
                gl2.glLoadIdentity();
            }
            drawLinePass(gl2, lineStrip, LINE_BASE_OFFSET_FACTOR, LINE_BASE_OFFSET_UNITS, LINE_BASE_WIDTH, 0.18, 0.18, 0.0);
            drawLinePass(gl2, lineStrip, LINE_HIGHLIGHT_OFFSET_FACTOR, LINE_HIGHLIGHT_OFFSET_UNITS, LINE_HIGHLIGHT_WIDTH, 1.0, 1.0, 0.0);
        }
        gl2.glLineWidth(1.0f);
        gl2.glDisable(GL2.GL_POLYGON_OFFSET_LINE);
        gl2.glDepthMask(true);
        gl2.glDepthFunc(GL2.GL_LESS);
        gl2.glPopMatrix();
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glPopMatrix();
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
    }

    private static void drawLinePass(
        GL2 gl2,
        List<Line.Vertex> lineStrip,
        float polygonOffsetFactor,
        float polygonOffsetUnits,
        float lineWidth,
        double red,
        double green,
        double blue
    ) {
        gl2.glPolygonOffset(polygonOffsetFactor, polygonOffsetUnits);
        gl2.glColor3d(red, green, blue);
        gl2.glLineWidth(lineWidth);
        gl2.glBegin(GL2.GL_LINE_STRIP);
        for (Line.Vertex p : lineStrip) {
            gl2.glVertex3d(p.x(), p.y(), p.z());
        }
        gl2.glEnd();
    }

    private TextureResident acquireTexture(GL2 gl2, PyramidalImageModel model, String texturePath) {
        TextureResident resident = residentsByTexturePath.get(texturePath);
        if (resident != null) {
            return resident;
        }
        if (texturePath == null || texturePath.isBlank()) {
            return null;
        }
        Texture texture;
        try {
            texture = TextureIO.newTexture(new File(texturePath), true);
        }
        catch (IOException ex) {
            return null;
        }
        if (texture == null) {
            return null;
        }

        long bytes = texture.getEstimatedMemorySize();
        if (bytes <= 0L) {
            int w = Math.max(1, texture.getImageWidth());
            int h = Math.max(1, texture.getImageHeight());
            bytes = (long)w * (long)h * 4L;
        }

        residentsByTexturePath.put(texturePath, new TextureResident(texture, bytes));
        model.markTextureResident(texturePath, bytes);
        enforceTextureBudget(gl2, model);
        return residentsByTexturePath.get(texturePath);
    }

    private void enforceTextureBudget(GL2 gl2, PyramidalImageModel model) {
        while (model.getGpuTextureBytesAssigned() > Configuration.MAX_GPU_TEXTURE_MEMORY) {
            String oldest = model.popOldestResidentTexturePath();
            if (oldest == null) {
                return;
            }
            TextureResident resident = residentsByTexturePath.remove(oldest);
            if (resident == null) {
                continue;
            }
            if (resident.texture() != null) {
                resident.texture().destroy(gl2);
            }
            model.unmarkTextureResident(oldest, resident.bytesAssigned());
        }
    }

    private record TextureResident(Texture texture, long bytesAssigned) {
    }
}
