package pyramidalimagebuilder.render;

import com.jogamp.opengl.GL2;
import pyramidalimagebuilder.model.TileInstance;
import pyramidalimagebuilder.model.TileMatrix;

public final class Jogl4TileMatrixRenderer {
    public void draw(GL2 gl2, TileMatrix matrix) {
        if (gl2 == null || matrix == null || matrix.getM() == null) {
            return;
        }
        TileInstance[][] M = matrix.getM();
        if (M.length == 0 || M[0].length == 0) {
            return;
        }

        gl2.glColor3d(1.0, 1.0, 1.0);
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
}
