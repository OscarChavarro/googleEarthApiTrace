package pyramidalimageexporter.render;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureCoords;
import com.jogamp.opengl.util.texture.TextureIO;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import pyramidalimageexporter.config.Configuration;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.TileCoord;
import pyramidalimageexporter.model.PyramidalImageExporterModel;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.material.RendererConfiguration;

public final class Jogl4MatrixLayerRenderer {
    private final Map<String, TextureResident> textureByPath = new HashMap<>();

    public void draw(GL2 gl2, MatrixLayer matrixLayer, RendererConfiguration renderingConfiguration, PyramidalImageExporterModel model) {
        if (gl2 == null || matrixLayer == null || renderingConfiguration == null || model == null) {
            return;
        }
        if (renderingConfiguration.isSurfacesSet()) {
            drawSurfaces(gl2, matrixLayer, renderingConfiguration.isTextureSet(), model);
        }
        if (renderingConfiguration.isWiresSet()) {
            drawWires(gl2, matrixLayer, model);
        }
        if (renderingConfiguration.isPointsSet()) {
            drawPoints(gl2, matrixLayer, model);
        }
    }

    private void drawSurfaces(GL2 gl2, MatrixLayer matrixLayer, boolean textured, PyramidalImageExporterModel model) {
        float offsetX = -(Math.max(0, matrixLayer.getCols()) * 0.5f);
        float offsetY = (Math.max(0, matrixLayer.getRows()) * 0.5f);
        if (textured) {
            gl2.glEnable(GL2.GL_TEXTURE_2D);
        }
        else {
            gl2.glDisable(GL2.GL_TEXTURE_2D);
        }

        for (TileCoord tile : matrixLayer.getTiles()) {
            if (tile == null) {
                continue;
            }
            float x0 = tile.getJ() + offsetX;
            float yTop = -tile.getI() + offsetY;
            float x1 = tile.getJ() + 1.0f + offsetX;
            float yBottom = -(tile.getI() + 1.0f) + offsetY;
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
                gl2.glColor3f(1f, 1f, 1f);
                gl2.glBegin(GL2.GL_QUADS);
                gl2.glTexCoord2f(tc.left(), tc.bottom()); gl2.glVertex3f(x0, yBottom, 0f);
                gl2.glTexCoord2f(tc.right(), tc.bottom()); gl2.glVertex3f(x1, yBottom, 0f);
                gl2.glTexCoord2f(tc.right(), tc.top()); gl2.glVertex3f(x1, yTop, 0f);
                gl2.glTexCoord2f(tc.left(), tc.top()); gl2.glVertex3f(x0, yTop, 0f);
                gl2.glEnd();
            }
            else {
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

    private void drawWires(GL2 gl2, MatrixLayer matrixLayer, PyramidalImageExporterModel model) {
        float offsetX = -(Math.max(0, matrixLayer.getCols()) * 0.5f);
        float offsetY = (Math.max(0, matrixLayer.getRows()) * 0.5f);
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glLineWidth(1.1f);
        gl2.glColor3f(1.0f, 1.0f, 1.0f);
        for (TileCoord tile : matrixLayer.getTiles()) {
            if (tile == null) {
                continue;
            }
            float x0 = tile.getJ() + offsetX;
            float y0 = -tile.getI() + offsetY;
            float x1 = tile.getJ() + 1.0f + offsetX;
            float y1 = -(tile.getI() + 1.0f) + offsetY;
            if (!QuadFrustumIntersector.intersectsCameraFrustum(model.getViewingCamera(), x0, y0, x1, y1)) {
                continue;
            }
            gl2.glBegin(GL2.GL_LINE_LOOP);
            gl2.glVertex3f(x0, y0, 0.001f);
            gl2.glVertex3f(x1, y0, 0.001f);
            gl2.glVertex3f(x1, y1, 0.001f);
            gl2.glVertex3f(x0, y1, 0.001f);
            gl2.glEnd();
        }
    }

    private void drawPoints(GL2 gl2, MatrixLayer matrixLayer, PyramidalImageExporterModel model) {
        float offsetX = -(Math.max(0, matrixLayer.getCols()) * 0.5f);
        float offsetY = (Math.max(0, matrixLayer.getRows()) * 0.5f);
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glColor3f(1.0f, 0.2f, 0.2f);
        gl2.glPointSize(3.0f);
        gl2.glBegin(GL2.GL_POINTS);
        for (TileCoord tile : matrixLayer.getTiles()) {
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
            gl2.glVertex3f(tile.getJ() + 0.5f + offsetX, -(tile.getI() + 0.5f) + offsetY, 0.002f);
        }
        gl2.glEnd();
    }

    private TextureResident acquireTexture(GL2 gl2, String texturePath, PyramidalImageExporterModel model) {
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

    private boolean isNearEnoughForTexture(PyramidalImageExporterModel model, float x0, float yTop, float x1, float yBottom) {
        Vector3Dd eye = model.getViewingCamera().getPosition();
        double cx = (x0 + x1) * 0.5;
        double cy = (yTop + yBottom) * 0.5;
        double dx = eye.x() - cx;
        double dy = eye.y() - cy;
        double dz = eye.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return distance <= Configuration.MAX_TEXTURED_QUAD_DISTANCE;
    }

    private void enforceTextureBudget(GL2 gl2, PyramidalImageExporterModel model) {
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

    public void dispose(GL2 gl2, PyramidalImageExporterModel model) {
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

    private record TextureResident(Texture texture, long bytesAssigned) {
    }
}
