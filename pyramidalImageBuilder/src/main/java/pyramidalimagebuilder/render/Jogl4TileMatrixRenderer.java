package pyramidalimagebuilder.render;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureCoords;
import com.jogamp.opengl.util.texture.TextureIO;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import pyramidalimagebuilder.config.Configuration;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.model.RenderingConfiguration;
import pyramidalimagebuilder.model.TileInstance;
import pyramidalimagebuilder.model.TileMatrix;

public final class Jogl4TileMatrixRenderer {
    private final Map<String, TextureResident> residentsByTexturePath = new HashMap<>();

    public void draw(
        GL2 gl2,
        TileMatrix matrix,
        RenderingConfiguration renderingConfiguration,
        PyramidalImageModel model
    ) {
        if (gl2 == null || matrix == null || matrix.getM() == null || renderingConfiguration == null || model == null) {
            return;
        }
        TileInstance[][] M = matrix.getM();
        if (M.length == 0 || M[0].length == 0) {
            return;
        }

        if (renderingConfiguration.isSurfacesSet()) {
            gl2.glEnable(GL2.GL_POLYGON_OFFSET_FILL);
            gl2.glPolygonOffset(4.0f, 4.0f);
            if (renderingConfiguration.isTextureSet()) {
                drawTexturedSurfaces(gl2, M, renderingConfiguration, model);
            } else {
                gl2.glBindTexture(GL2.GL_TEXTURE_2D, 0);
                gl2.glDisable(GL2.GL_TEXTURE_2D);
                drawFlatSurfaces(gl2, M, renderingConfiguration);
            }
            gl2.glDisable(GL2.GL_POLYGON_OFFSET_FILL);
        }

        if (renderingConfiguration.isWiresSet()) {
            gl2.glEnable(GL2.GL_POLYGON_OFFSET_LINE);
            gl2.glPolygonOffset(2.0f, 2.0f);
            gl2.glColor3d(1.0, 1.0, 1.0);
            gl2.glLineWidth(1.5f);
            gl2.glBegin(GL2.GL_LINES);
            for (int row = 0; row < M.length; row++) {
                for (int col = 0; col < M[row].length; col++) {
                    if (M[row][col] == null) {
                        continue;
                    }
                    double x0 = col;
                    double y0 = -row;
                    double x1 = x0 + 1.0;
                    double y1 = y0 - 1.0;
                    gl2.glVertex3d(x0, y0, 0.0); gl2.glVertex3d(x1, y0, 0.0);
                    gl2.glVertex3d(x1, y0, 0.0); gl2.glVertex3d(x1, y1, 0.0);
                    gl2.glVertex3d(x1, y1, 0.0); gl2.glVertex3d(x0, y1, 0.0);
                    gl2.glVertex3d(x0, y1, 0.0); gl2.glVertex3d(x0, y0, 0.0);
                }
            }
            gl2.glEnd();
            gl2.glDisable(GL2.GL_POLYGON_OFFSET_LINE);
        }

        if (renderingConfiguration.isPointsSet()) {
            gl2.glEnable(GL2.GL_POLYGON_OFFSET_POINT);
            gl2.glPolygonOffset(-2.0f, -2.0f);
            gl2.glPointSize(5.0f);
            gl2.glColor3d(1.0, 0.0, 0.0);
            gl2.glBegin(GL2.GL_POINTS);
            for (int row = 0; row < M.length; row++) {
                for (int col = 0; col < M[row].length; col++) {
                    if (M[row][col] == null) {
                        continue;
                    }
                    double x0 = col;
                    double y0 = -row;
                    double x1 = x0 + 1.0;
                    double y1 = y0 - 1.0;
                    gl2.glVertex3d(x0, y0, 0.0);
                    gl2.glVertex3d(x1, y0, 0.0);
                    gl2.glVertex3d(x1, y1, 0.0);
                    gl2.glVertex3d(x0, y1, 0.0);
                }
            }
            gl2.glEnd();
            gl2.glDisable(GL2.GL_POLYGON_OFFSET_POINT);
        }
    }

    private void drawFlatSurfaces(GL2 gl2, TileInstance[][] M, RenderingConfiguration renderingConfiguration) {
        if (renderingConfiguration.isWiresSet()) {
            gl2.glColor3d(0.5, 0.5, 0.5);
        } else {
            gl2.glColor3d(1.0, 1.0, 1.0);
        }
        gl2.glBegin(GL2.GL_QUADS);
        for (int row = 0; row < M.length; row++) {
            for (int col = 0; col < M[row].length; col++) {
                if (M[row][col] == null) {
                    continue;
                }
                double x0 = col;
                double y0 = -row;
                double x1 = x0 + 1.0;
                double y1 = y0 - 1.0;
                gl2.glVertex3d(x0, y0, 0.0);
                gl2.glVertex3d(x1, y0, 0.0);
                gl2.glVertex3d(x1, y1, 0.0);
                gl2.glVertex3d(x0, y1, 0.0);
            }
        }
        gl2.glEnd();
    }

    private void drawTexturedSurfaces(
        GL2 gl2,
        TileInstance[][] M,
        RenderingConfiguration renderingConfiguration,
        PyramidalImageModel model
    ) {
        gl2.glEnable(GL2.GL_TEXTURE_2D);
        int lastBound = -1;
        for (int row = 0; row < M.length; row++) {
            for (int col = 0; col < M[row].length; col++) {
                TileInstance tile = M[row][col];
                if (tile == null) {
                    continue;
                }
                TextureResident resident = acquireTexture(gl2, model, tile.getTextureFile());
                double x0 = col;
                double y0 = -row;
                double x1 = x0 + 1.0;
                double y1 = y0 - 1.0;

                if (resident == null || resident.texture() == null) {
                    gl2.glDisable(GL2.GL_TEXTURE_2D);
                    gl2.glColor3d(0.5, 0.5, 0.5);
                    gl2.glBegin(GL2.GL_QUADS);
                    gl2.glVertex3d(x0, y0, 0.0);
                    gl2.glVertex3d(x1, y0, 0.0);
                    gl2.glVertex3d(x1, y1, 0.0);
                    gl2.glVertex3d(x0, y1, 0.0);
                    gl2.glEnd();
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
                TextureCoords tc = resident.texture().getImageTexCoords();
                float s0 = tc.left();
                float t0 = tc.bottom();
                float s1 = tc.right();
                float t1 = tc.top();

                gl2.glBegin(GL2.GL_QUADS);
                gl2.glTexCoord2f(s0, t0); gl2.glVertex3d(x0, y0, 0.0);
                gl2.glTexCoord2f(s1, t0); gl2.glVertex3d(x1, y0, 0.0);
                gl2.glTexCoord2f(s1, t1); gl2.glVertex3d(x1, y1, 0.0);
                gl2.glTexCoord2f(s0, t1); gl2.glVertex3d(x0, y1, 0.0);
                gl2.glEnd();
            }
        }
        gl2.glBindTexture(GL2.GL_TEXTURE_2D, 0);
        gl2.glDisable(GL2.GL_TEXTURE_2D);
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
