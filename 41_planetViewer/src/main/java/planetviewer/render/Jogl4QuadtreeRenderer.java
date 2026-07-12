package planetviewer.render;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureCoords;
import java.util.List;
import planetviewer.io.TileImageLoader;
import planetviewer.model.PyramidalImageInstance;
import planetviewer.model.QuadtreeNode;
import planetviewer.processing.DrawCommand;
import planetviewer.processing.QuadtreeDrawPlanner;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;

/**
 * Draws one PyramidalImageInstance: runs QuadtreeDrawPlanner to select the
 * visible/LOD-appropriate nodes, then for each one binds its own texture or,
 * if not loaded yet, the nearest already GPU-resident ancestor's texture
 * with the sub-rectangle remapped to this node's own quadrant (ported from
 * the old prototype's activateNearestAncestorImage +
 * calculateTextureCoordinates), while asynchronously requesting the node's
 * own tile so the image sharpens progressively once it arrives.
 */
public final class Jogl4QuadtreeRenderer {
    private final TextureCache textureCache = new TextureCache();

    /** Interactive draw: async texture loading through the given loader (never blocks the GL thread). */
    public void draw(GL2 gl2, PyramidalImageInstance instance, Camera camera, double relativeScale, boolean wires, TileImageLoader loader) {
        drawCommands(gl2, instance, QuadtreeDrawPlanner.select(instance, camera, relativeScale), wires, loader);
    }

    /** Draws every leaf tile unconditionally, ignoring camera culling/LOD, with synchronous loading (offline snapshot mode). */
    public void drawAll(GL2 gl2, PyramidalImageInstance instance, double relativeScale, boolean wires) {
        drawCommands(gl2, instance, QuadtreeDrawPlanner.selectAllLeaves(instance, relativeScale), wires, null);
    }

    private void drawCommands(GL2 gl2, PyramidalImageInstance instance, List<DrawCommand> commands, boolean wires, TileImageLoader loader) {
        gl2.glEnable(GL2.GL_TEXTURE_2D);
        gl2.glColor4d(1.0, 1.0, 1.0, instance.getOpacity());
        for (DrawCommand command : commands) {
            drawNode(gl2, command, loader);
        }
        gl2.glBindTexture(GL2.GL_TEXTURE_2D, 0);
        if (wires) {
            drawWires(gl2, commands);
        }
    }

    private void drawNode(GL2 gl2, DrawCommand command, TileImageLoader loader) {
        QuadtreeNode node = command.node();
        Texture texture = null;
        QuadtreeNode textureOwner = null;

        if (node.getTileFile() != null) {
            texture = textureCache.acquire(gl2, node.getTileFile(), loader);
            textureOwner = node;
        }
        if (texture == null) {
            // Node's own tile is not GPU-resident yet (or missing): fall
            // back to the nearest ancestor already loaded, without
            // triggering new loads for ancestors that were never requested.
            for (QuadtreeNode ancestor = node.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                if (ancestor.getTileFile() == null) {
                    continue;
                }
                texture = textureCache.peekResident(ancestor.getTileFile());
                if (texture != null) {
                    textureOwner = ancestor;
                    break;
                }
            }
        }
        if (texture == null) {
            return;
        }
        double[] subRect = textureOwner == node
            ? new double[] {0.0, 0.0, 1.0, 1.0}
            : node.subRectRelativeTo(textureOwner);

        texture.bind(gl2);
        TextureCoords tc = texture.getImageTexCoords();
        float left = interpolate(tc.left(), tc.right(), subRect[0]);
        float right = interpolate(tc.left(), tc.right(), subRect[2]);
        float bottom = interpolate(tc.bottom(), tc.top(), subRect[1]);
        float top = interpolate(tc.bottom(), tc.top(), subRect[3]);

        Vector3Dd[] c = command.corners();
        gl2.glBegin(GL2.GL_QUADS);
        gl2.glTexCoord2f(left, bottom); gl2.glVertex3d(c[0].x(), c[0].y(), c[0].z());
        gl2.glTexCoord2f(right, bottom); gl2.glVertex3d(c[1].x(), c[1].y(), c[1].z());
        gl2.glTexCoord2f(right, top); gl2.glVertex3d(c[2].x(), c[2].y(), c[2].z());
        gl2.glTexCoord2f(left, top); gl2.glVertex3d(c[3].x(), c[3].y(), c[3].z());
        gl2.glEnd();
    }

    private void drawWires(GL2 gl2, List<DrawCommand> commands) {
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glColor3f(1.0f, 1.0f, 1.0f);
        gl2.glLineWidth(1.0f);
        for (DrawCommand command : commands) {
            Vector3Dd[] c = command.corners();
            gl2.glBegin(GL2.GL_LINE_LOOP);
            for (Vector3Dd corner : c) {
                gl2.glVertex3d(corner.x(), corner.y(), corner.z() + 0.0005);
            }
            gl2.glEnd();
        }
    }

    private static float interpolate(float from, float to, double fraction) {
        return (float) (from + fraction * (to - from));
    }

    public long getGpuBytesAssigned() {
        return textureCache.getBytesAssigned();
    }

    public int getResidentTextureCount() {
        return textureCache.getResidentCount();
    }

    public void dispose(GL2 gl2) {
        textureCache.dispose(gl2);
    }
}
