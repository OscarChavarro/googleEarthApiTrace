package pyramidalimagebuilder.render;

import com.jogamp.opengl.GL2;
import pyramidalimagebuilder.model.RenderingConfiguration;
import pyramidalimagebuilder.model.TileInstance;
import pyramidalimagebuilder.model.TileMatrix;

public final class Jogl4TileMatrixRenderer {
    public void draw(GL2 gl2, TileMatrix matrix, RenderingConfiguration renderingConfiguration) {
        if (gl2 == null || matrix == null || matrix.getM() == null || renderingConfiguration == null) {
            return;
        }
        TileInstance[][] M = matrix.getM();
        if (M.length == 0 || M[0].length == 0) {
            return;
        }

        if (renderingConfiguration.isSurfacesSet()) {
            gl2.glEnable(GL2.GL_POLYGON_OFFSET_FILL);
            gl2.glPolygonOffset(4.0f, 4.0f);
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
}
