package matrixmerger.render;

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

public final class Jogl4TileMatrixRenderer {
    private final Map<String, TextureResident> textureByPath = new HashMap<>();

    public void draw(GL2 gl2, TileMatrix matrix, RendererConfiguration renderingConfiguration, MatrixMergerModel model) {
        if (gl2 == null || matrix == null || renderingConfiguration == null || model == null) {
            return;
        }

        if (renderingConfiguration.isSurfacesSet()) {
            drawSurfaces(gl2, matrix, renderingConfiguration.isTextureSet(), model);
        }
        if (renderingConfiguration.isWiresSet()) {
            drawWires(gl2, matrix);
        }
        if (renderingConfiguration.isPointsSet()) {
            drawPoints(gl2, matrix);
        }
    }

    private void drawSurfaces(GL2 gl2, TileMatrix matrix, boolean textured, MatrixMergerModel model) {
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
            float x0 = j;
            float yTop = -i;
            float x1 = j + 1.0f;
            float yBottom = -(i + 1.0f);

            TextureResident resident = textured ? acquireTexture(gl2, tile.getTextureFile(), model) : null;
            Texture texture = resident == null ? null : resident.texture();
            if (textured && texture != null) {
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
                gl2.glColor3f(0.75f, 0.78f, 0.82f);
                gl2.glBegin(GL2.GL_QUADS);
                gl2.glVertex3f(x0, yBottom, 0f);
                gl2.glVertex3f(x1, yBottom, 0f);
                gl2.glVertex3f(x1, yTop, 0f);
                gl2.glVertex3f(x0, yTop, 0f);
                gl2.glEnd();
            }
        }

        gl2.glBindTexture(GL2.GL_TEXTURE_2D, 0);
        gl2.glDisable(GL2.GL_TEXTURE_2D);
    }

    private void drawWires(GL2 gl2, TileMatrix matrix) {
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glColor3f(0.1f, 0.1f, 0.1f);
        gl2.glLineWidth(1.1f);
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null) {
                continue;
            }
            int i = tile.getI();
            int j = tile.getJ();
            float x0 = j;
            float y0 = -i;
            float x1 = j + 1.0f;
            float y1 = -(i + 1.0f);
            gl2.glBegin(GL2.GL_LINE_LOOP);
            gl2.glVertex3f(x0, y0, 0.001f);
            gl2.glVertex3f(x1, y0, 0.001f);
            gl2.glVertex3f(x1, y1, 0.001f);
            gl2.glVertex3f(x0, y1, 0.001f);
            gl2.glEnd();
        }
    }

    private void drawPoints(GL2 gl2, TileMatrix matrix) {
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glColor3f(1.0f, 0.2f, 0.2f);
        gl2.glPointSize(3.0f);
        gl2.glBegin(GL2.GL_POINTS);
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null) {
                continue;
            }
            float cx = tile.getJ() + 0.5f;
            float cy = -(tile.getI() + 0.5f);
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

    private record TextureResident(Texture texture, long bytesAssigned) {
    }
}
