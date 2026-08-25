package dumpanalyzer.render;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL4;
import dumpanalyzer.model.Frame;
import dumpanalyzer.model.replay.ReplayDraw;
import dumpanalyzer.model.replay.ReplayVertex;
import dumpanalyzer.model.replay.ReplayViewport;
import dumpanalyzer.model.state.DumpAnalyzerState;

/** Fixed-function emulation for the captured screen-space pass. */
final class Jogl4ReplayHudRenderer {
    void draw(GL4 gl4, GL2 gl, DumpAnalyzerState model, Frame frame, int surfaceWidth, int surfaceHeight, Jogl4HudRenderer textures) {
        if (!model.isOriginalGoogleEarthHudEnabled() || frame == null || frame.getReplayDraws().isEmpty()) return;
        ReplayViewport source = frame.getCaptureSurface();
        if (source.width() <= 0 || source.height() <= 0) return;
        gl.glPushAttrib(GL2.GL_ENABLE_BIT | GL2.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT | GL2.GL_SCISSOR_BIT | GL2.GL_TEXTURE_BIT | GL2.GL_TRANSFORM_BIT | GL2.GL_VIEWPORT_BIT);
        gl4.glUseProgram(0);
        for (ReplayDraw draw : frame.getReplayDraws()) {
            if (!"SCREEN_SPACE".equals(draw.pass()) || draw.vertices().size() < 3 || draw.texture() == null || draw.texture().imagePath() == null) continue;
            applyState(gl, draw, source, surfaceWidth, surfaceHeight);
            int texture = textures.activateTexture(gl4, model, draw.texture().imagePath());
            if (texture <= 0) continue;
            gl.glEnable(GL2.GL_TEXTURE_2D);
            gl.glBindTexture(GL2.GL_TEXTURE_2D, texture);
            load(gl, GL2.GL_PROJECTION, draw.projectionMatrix());
            load(gl, GL2.GL_MODELVIEW, draw.modelViewMatrix());
            load(gl, GL2.GL_TEXTURE, draw.textureMatrix());
            gl.glBegin("GL_TRIANGLES".equals(draw.primitive()) ? GL2.GL_TRIANGLES : GL2.GL_TRIANGLE_STRIP);
            for (ReplayVertex v : draw.vertices()) { gl.glTexCoord2d(v.u(), v.v()); gl.glVertex3d(v.x(), v.y(), v.z()); }
            gl.glEnd();
            gl.glMatrixMode(GL2.GL_TEXTURE); gl.glLoadIdentity();
        }
        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glPopAttrib();
    }
    private static void applyState(GL2 gl, ReplayDraw d, ReplayViewport source, int width, int height) {
        double sx = width / (double) source.width(), sy = height / (double) source.height();
        ReplayViewport v = d.viewport();
        gl.glViewport((int)Math.round(v.x()*sx), (int)Math.round(v.y()*sy), Math.max(1,(int)Math.round(v.width()*sx)), Math.max(1,(int)Math.round(v.height()*sy)));
        if (d.scissor().enabled()) { gl.glEnable(GL2.GL_SCISSOR_TEST); gl.glScissor((int)Math.round(d.scissor().x()*sx), (int)Math.round(d.scissor().y()*sy), Math.max(1,(int)Math.round(d.scissor().width()*sx)), Math.max(1,(int)Math.round(d.scissor().height()*sy))); } else gl.glDisable(GL2.GL_SCISSOR_TEST);
        if (d.state().blendEnabled()) { gl.glEnable(GL2.GL_BLEND); gl.glBlendFunc(blend(d.state().blendSrc()), blend(d.state().blendDst())); } else gl.glDisable(GL2.GL_BLEND);
        if (d.state().depthTestEnabled()) gl.glEnable(GL2.GL_DEPTH_TEST); else gl.glDisable(GL2.GL_DEPTH_TEST);
        gl.glDepthMask(d.state().depthMask());
        if (d.state().cullEnabled()) gl.glEnable(GL2.GL_CULL_FACE); else gl.glDisable(GL2.GL_CULL_FACE);
        double[] c = d.state().color(); gl.glColor4d(c[0], c[1], c[2], c[3]);
    }
    private static int blend(String v) { return "GL_SRC_ALPHA".equals(v) ? GL2.GL_SRC_ALPHA : "GL_ONE_MINUS_SRC_ALPHA".equals(v) ? GL2.GL_ONE_MINUS_SRC_ALPHA : "GL_ZERO".equals(v) ? GL2.GL_ZERO : GL2.GL_ONE; }
    private static void load(GL2 gl, int mode, double[] matrix) { gl.glMatrixMode(mode); if (matrix == null || matrix.length != 16) gl.glLoadIdentity(); else { float[] f = new float[16]; for(int i=0;i<16;i++) f[i]=(float)matrix[i]; gl.glLoadMatrixf(f,0); } }
}
