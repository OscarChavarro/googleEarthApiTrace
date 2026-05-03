package matrixmerger.render;

import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import matrixmerger.model.MatrixMergerModel;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.render.jogl.Jogl4CameraRenderer;
import vsdk.toolkit.render.jogl.Jogl4MatrixRenderer;

public final class Jogl4MatrixMergerRenderer implements GLEventListener {
    private final MatrixMergerModel model;

    public Jogl4MatrixMergerRenderer(MatrixMergerModel model) {
        this.model = model;
    }

    public GLCanvas createCanvas() {
        GLProfile profile = GLProfile.get(GLProfile.GL4);
        GLCapabilities caps = new GLCapabilities(profile);
        GLCanvas canvas = new GLCanvas(caps);
        canvas.addGLEventListener(this);
        return canvas;
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        gl.glClearColor(0.08f, 0.1f, 0.12f, 1f);
        gl.glClear(GL4.GL_COLOR_BUFFER_BIT | GL4.GL_DEPTH_BUFFER_BIT);
        drawScene(gl);
    }

    @Override
    public void init(GLAutoDrawable drawable) {
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        Jogl4CameraRenderer.dispose(gl);
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int xSize, int ySize) {
        GL4 gl = drawable.getGL().getGL4();
        gl.glViewport(0, 0, xSize, ySize);
        model.getCamera().updateViewportResize(xSize, ySize);
    }

    private void drawScene(GL4 gl) {
        gl.glEnable(GL4.GL_DEPTH_TEST);
        Matrix4x4 projection = Jogl4CameraRenderer.activate(gl, model.getCamera());
        Jogl4MatrixRenderer.draw(gl, projection, Matrix4x4.identityMatrix());
    }
}
