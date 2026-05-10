package matrixmerger.render;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureCoords;
import com.jogamp.opengl.util.texture.TextureIO;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import matrixmerger.config.Configuration;
import matrixmerger.io.TileMatrix;
import matrixmerger.model.MatrixMergerModel;
import vsdk.toolkit.common.RendererConfiguration;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

public final class Jogl4TileMatrixRenderer {
    private final Map<String, TextureResident> textureByPath = new HashMap<>();

    public void draw(GL2 gl2, TileMatrix matrix, RendererConfiguration renderingConfiguration, MatrixMergerModel model) {
        if (gl2 == null || matrix == null || renderingConfiguration == null || model == null) {
            return;
        }

        if (renderingConfiguration.isSurfacesSet()) {
            drawSurfaces(gl2, matrix, renderingConfiguration.isTextureSet(), model);
        }
        drawWestCutterWires(gl2, matrix, model);
        if (renderingConfiguration.isWiresSet()) {
            drawWires(gl2, matrix, model);
        }
        if (renderingConfiguration.isPointsSet()) {
            drawPoints(gl2, matrix, model);
        }
    }

    private void drawSurfaces(GL2 gl2, TileMatrix matrix, boolean textured, MatrixMergerModel model) {
        float offsetX = -(Math.max(0, matrix.getCols()) * 0.5f);
        float offsetY = (Math.max(0, matrix.getRows()) * 0.5f);
        if (textured) {
            gl2.glEnable(GL2.GL_TEXTURE_2D);
        } else {
            gl2.glDisable(GL2.GL_TEXTURE_2D);
        }

        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            
            if (tile == null) {
                continue;
            }
            int i = tile.getI();
            int j = tile.getJ();
            float x0 = j + offsetX;
            float yTop = -i + offsetY;
            float x1 = j + 1.0f;
            x1 += offsetX;
            float yBottom = -(i + 1.0f) + offsetY;

            if (!QuadFrustumIntersector.intersectsCameraFrustum(model.getViewingCamera(), x0, yTop, x1, yBottom)) {
                continue;
            }

            boolean nearEnoughForTexture = isNearEnoughForTexture(model, x0, yTop, x1, yBottom);
            boolean drawTextured = textured && nearEnoughForTexture;
            TextureResident resident = drawTextured ? acquireTexture(gl2, tile.getTextureFile(), model) : null;
            Texture texture = resident == null ? null : resident.texture();
            if (drawTextured && texture != null) {
                texture.bind(gl2);
                TextureCoords tc = texture.getImageTexCoords();
                float s0 = tc.left();
                float t0 = tc.bottom();
                float s1 = tc.right();
                float t1 = tc.top();
                gl2.glColor3f(1f, 1f, 1f);
                gl2.glBegin(GL2.GL_QUADS);
                gl2.glTexCoord2f(s0, t0); gl2.glVertex3f(x0, yBottom, 0f);
                gl2.glTexCoord2f(s1, t0); gl2.glVertex3f(x1, yBottom, 0f);
                gl2.glTexCoord2f(s1, t1); gl2.glVertex3f(x1, yTop, 0f);
                gl2.glTexCoord2f(s0, t1); gl2.glVertex3f(x0, yTop, 0f);
                gl2.glEnd();
            } else {
                float rx0 = x0;
                float ry0 = yBottom;
                float rx1 = x1;
                float ry1 = yTop;
                if (textured && !nearEnoughForTexture) {
                    float cx = (x0 + x1) * 0.5f;
                    float cy = (yTop + yBottom) * 0.5f;
                    float halfW = (x1 - x0) * 0.5f * Configuration.FAR_QUAD_SCALE;
                    float halfH = (yTop - yBottom) * 0.5f * Configuration.FAR_QUAD_SCALE;
                    rx0 = cx - halfW;
                    rx1 = cx + halfW;
                    ry0 = cy - halfH;
                    ry1 = cy + halfH;
                }
                gl2.glColor3f(0.75f, 0.78f, 0.82f);
                gl2.glBegin(GL2.GL_QUADS);
                gl2.glVertex3f(rx0, ry0, 0f);
                gl2.glVertex3f(rx1, ry0, 0f);
                gl2.glVertex3f(rx1, ry1, 0f);
                gl2.glVertex3f(rx0, ry1, 0f);
                gl2.glEnd();
            }
        }

        gl2.glBindTexture(GL2.GL_TEXTURE_2D, 0);
        gl2.glDisable(GL2.GL_TEXTURE_2D);
    }

    private void drawWires(GL2 gl2, TileMatrix matrix, MatrixMergerModel model) {
        float offsetX = -(Math.max(0, matrix.getCols()) * 0.5f);
        float offsetY = (Math.max(0, matrix.getRows()) * 0.5f);
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glLineWidth(1.1f);
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null) {
                continue;
            }
            if (model.isWestCutterTileId(tile.getId())) {
                continue;
            }
            int i = tile.getI();
            int j = tile.getJ();
            float x0 = j + offsetX;
            float y0 = -i + offsetY;
            float x1 = j + 1.0f + offsetX;
            float y1 = -(i + 1.0f) + offsetY;
            if (!QuadFrustumIntersector.intersectsCameraFrustum(model.getViewingCamera(), x0, y0, x1, y1)) {
                continue;
            }
            if (hasUncles(tile)) {
                gl2.glColor3f(0.1f, 1.0f, 0.1f);
            }
            else {
                gl2.glColor3f(1.0f, 1.0f, 1.0f);
            }
            gl2.glBegin(GL2.GL_LINE_LOOP);
            gl2.glVertex3f(x0, y0, 0.001f);
            gl2.glVertex3f(x1, y0, 0.001f);
            gl2.glVertex3f(x1, y1, 0.001f);
            gl2.glVertex3f(x0, y1, 0.001f);
            gl2.glEnd();
        }
    }

    private void drawWestCutterWires(GL2 gl2, TileMatrix matrix, MatrixMergerModel model) {
        float offsetX = -(Math.max(0, matrix.getCols()) * 0.5f);
        float offsetY = (Math.max(0, matrix.getRows()) * 0.5f);
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glLineWidth(2.0f);
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null || !model.isWestCutterTileId(tile.getId())) {
                continue;
            }
            int i = tile.getI();
            int j = tile.getJ();
            float x0 = j + offsetX;
            float y0 = -i + offsetY;
            float x1 = j + 1.0f + offsetX;
            float y1 = -(i + 1.0f) + offsetY;
            if (!QuadFrustumIntersector.intersectsCameraFrustum(model.getViewingCamera(), x0, y0, x1, y1)) {
                continue;
            }
            if (hasUncles(tile)) {
                gl2.glColor3f(0.1f, 1.0f, 0.1f);
            }
            else {
                gl2.glColor3f(1.0f, 0.1f, 0.1f);
            }
            gl2.glBegin(GL2.GL_LINE_LOOP);
            gl2.glVertex3f(x0, y0, 0.002f);
            gl2.glVertex3f(x1, y0, 0.002f);
            gl2.glVertex3f(x1, y1, 0.002f);
            gl2.glVertex3f(x0, y1, 0.002f);
            gl2.glEnd();
        }
    }

    private void drawPoints(GL2 gl2, TileMatrix matrix, MatrixMergerModel model) {
        float offsetX = -(Math.max(0, matrix.getCols()) * 0.5f);
        float offsetY = (Math.max(0, matrix.getRows()) * 0.5f);
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glColor3f(1.0f, 0.2f, 0.2f);
        gl2.glPointSize(3.0f);
        gl2.glBegin(GL2.GL_POINTS);
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            
            if (tile == null) {
                continue;
            }
            float x0 = tile.getJ() + offsetX;
            float y0 = -tile.getI() + offsetY;
            float x1 = x0 + 1.0f;
            float y1 = y0 - 1.0f;
            if (!QuadFrustumIntersector.intersectsCameraFrustum(model.getViewingCamera(), x0, y0, x1, y1)) {
                continue;
            }
            float cx = tile.getJ() + 0.5f + offsetX;
            float cy = -(tile.getI() + 0.5f) + offsetY;
            gl2.glVertex3f(cx, cy, 0.002f);
        }
        gl2.glEnd();
    }

    private TextureResident acquireTexture(GL2 gl2, String texturePath, MatrixMergerModel model) {
        if (texturePath == null || texturePath.isBlank()) {
            return null;
        }
        TextureResident resident = textureByPath.get(texturePath);
        if (resident != null) {
            return resident;
        }

        Texture texture;
        try {
            texture = TextureIO.newTexture(new File(texturePath), true);
            texture.bind(gl2);
            // Ensure near-view pixel-perfect sampling and avoid seams between adjacent tiles.
            gl2.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
            gl2.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
            gl2.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL2.GL_CLAMP_TO_EDGE);
            gl2.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL2.GL_CLAMP_TO_EDGE);
        }
        catch (IOException ex) {
            return null;
        }

        long bytes = texture.getEstimatedMemorySize();
        if (bytes <= 0L) {
            int w = Math.max(1, texture.getImageWidth());
            int h = Math.max(1, texture.getImageHeight());
            bytes = (long)w * (long)h * 4L;
        }

        TextureResident loaded = new TextureResident(texture, bytes);
        textureByPath.put(texturePath, loaded);
        model.markTextureResident(texturePath, bytes);
        enforceTextureBudget(gl2, model);
        return textureByPath.get(texturePath);
    }

    private boolean isNearEnoughForTexture(MatrixMergerModel model, float x0, float yTop, float x1, float yBottom) {
        Vector3D eye = model.getViewingCamera().getPosition();
        double cx = (x0 + x1) * 0.5;
        double cy = (yTop + yBottom) * 0.5;
        double dx = eye.x() - cx;
        double dy = eye.y() - cy;
        double dz = eye.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return distance <= Configuration.MAX_TEXTURED_QUAD_DISTANCE;
    }

    private void enforceTextureBudget(GL2 gl2, MatrixMergerModel model) {
        while (model.getGpuTextureBytesAssigned() > Configuration.MAX_GPU_TEXTURE_MEMORY) {
            String oldest = model.popOldestResidentTexturePath();
            if (oldest == null) {
                return;
            }
            TextureResident resident = textureByPath.remove(oldest);
            if (resident == null) {
                continue;
            }
            if (resident.texture() != null) {
                resident.texture().destroy(gl2);
            }
            model.unmarkTextureResident(oldest, resident.bytesAssigned());
        }
    }

    public void dispose(GL2 gl2, MatrixMergerModel model) {
        for (Map.Entry<String, TextureResident> entry : textureByPath.entrySet()) {
            TextureResident resident = entry.getValue();
            if (resident != null && resident.texture() != null) {
                resident.texture().destroy(gl2);
            }
            if (resident != null) {
                model.unmarkTextureResident(entry.getKey(), resident.bytesAssigned());
            }
        }
        textureByPath.clear();
    }

    private static boolean hasUncles(TileMatrix.TileCoord tile) {
        return tile != null && tile.getUncles() != null && !tile.getUncles().isEmpty();
    }

    private record TextureResident(Texture texture, long bytesAssigned) {
    }
}
