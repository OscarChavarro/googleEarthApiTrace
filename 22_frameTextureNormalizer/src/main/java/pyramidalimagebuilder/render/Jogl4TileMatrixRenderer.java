package pyramidalimagebuilder.render;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureCoords;
import com.jogamp.opengl.util.texture.TextureIO;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pyramidalimagebuilder.config.Configuration;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.model.TileInstance;
import pyramidalimagebuilder.model.TileInstance.TriangleStripGeometry;
import pyramidalimagebuilder.model.TileInstance.TriangleStripVertex;
import vsdk.toolkit.common.RendererConfiguration;

public final class Jogl4TileMatrixRenderer {
    private final Map<String, TextureResident> residentsByTexturePath = new HashMap<>();

    public void draw(
        GL2 gl2,
        List<TileInstance> tiles,
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
            drawSurfaces(gl2, tiles, renderingConfiguration, model, selectedTileIndex);
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
                drawWire(gl2, tiles.get(i));
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
                drawPoints(gl2, tiles.get(i));
            }
        }
    }

    private void drawSurfaces(
        GL2 gl2,
        List<TileInstance> tiles,
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
                drawFlatGeometry(gl2, tile);
                continue;
            }

            TextureResident resident = acquireTexture(gl2, model, tile.getTextureFile());
            if (resident == null || resident.texture() == null) {
                gl2.glDisable(GL2.GL_TEXTURE_2D);
                gl2.glColor3d(0.7, 0.7, 0.7);
                drawFlatGeometry(gl2, tile);
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
            drawTexturedGeometry(gl2, tile, resident.texture().getImageTexCoords());
        }
        gl2.glBindTexture(GL2.GL_TEXTURE_2D, 0);
        gl2.glDisable(GL2.GL_TEXTURE_2D);
    }

    private static void drawFlatGeometry(GL2 gl2, TileInstance tile) {
        TriangleStripGeometry strip = tile == null ? null : tile.getTriangleStrip();
        if (strip == null || strip.vertices() == null || strip.vertices().size() < 3) {
            return;
        }
        gl2.glBegin(GL2.GL_TRIANGLE_STRIP);
        for (TriangleStripVertex v : strip.vertices()) {
            gl2.glVertex3d(v.x(), v.y(), v.z());
        }
        gl2.glEnd();
    }

    private static void drawTexturedGeometry(GL2 gl2, TileInstance tile, TextureCoords tc) {
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
        gl2.glBegin(GL2.GL_TRIANGLE_STRIP);
        for (TriangleStripVertex v : strip.vertices()) {
            float s = (float)(s0 + v.u() * (s1 - s0));
            float t = (float)(v0 + (1.0 - v.v()) * (v1 - v0));
            gl2.glTexCoord2f(s, t);
            gl2.glVertex3d(v.x(), v.y(), v.z());
        }
        gl2.glEnd();
    }

    private static void drawWire(GL2 gl2, TileInstance tile) {
        TriangleStripGeometry strip = tile == null ? null : tile.getTriangleStrip();
        if (strip == null || strip.vertices() == null || strip.vertices().size() < 3) {
            return;
        }
        gl2.glBegin(GL2.GL_LINE_STRIP);
        for (TriangleStripVertex v : strip.vertices()) {
            gl2.glVertex3d(v.x(), v.y(), v.z());
        }
        gl2.glEnd();
    }

    private static void drawPoints(GL2 gl2, TileInstance tile) {
        TriangleStripGeometry strip = tile == null ? null : tile.getTriangleStrip();
        if (strip == null || strip.vertices() == null || strip.vertices().isEmpty()) {
            return;
        }
        gl2.glBegin(GL2.GL_POINTS);
        for (TriangleStripVertex v : strip.vertices()) {
            gl2.glVertex3d(v.x(), v.y(), v.z());
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
