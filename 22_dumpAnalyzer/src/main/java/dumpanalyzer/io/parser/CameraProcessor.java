package dumpanalyzer.io.parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

public final class CameraProcessor {
    private static final Pattern MATRIX_MODE_LINE_PATTERN = Pattern.compile("\\bglMatrixMode\\s*\\((.*)\\)");
    private static final Pattern LOAD_MATRIXF_LINE_PATTERN = Pattern.compile("\\bglLoadMatrixf\\s*\\((.*)\\)");
    private static final Pattern MATRIX_MODE_VALUE_PATTERN = Pattern.compile("mode\\s*=\\s*([A-Z0-9_]+)");
    private static final Pattern MATRIX_VALUES_PATTERN = Pattern.compile("m\\s*=\\s*\\{([^}]*)\\}");
    private static final Pattern DRAW_ARRAYS_PATTERN = Pattern.compile("\\bglDrawArrays\\s*\\((.*)\\)");
    private static final Pattern DRAW_ELEMENTS_PATTERN = Pattern.compile("\\bglDrawElements\\s*\\((.*)\\)");
    private static final Pattern DRAW_MODE_VALUE_PATTERN = Pattern.compile("mode\\s*=\\s*([A-Z0-9_]+)");
    private static final Pattern DRAW_COUNT_VALUE_PATTERN = Pattern.compile("count\\s*=\\s*([0-9]+)");

    private CameraProcessor() {
    }

    public static double[] extractProjectionMatrix(String normalizedContent) {
        SceneMatrices scene = extractLastSceneMatrices(normalizedContent);
        if (scene.projection != null) {
            return scene.projection;
        }
        return extractLastMatrixForMode(normalizedContent, "GL_PROJECTION");
    }

    public static double[] extractModelViewMatrix(String normalizedContent) {
        SceneMatrices scene = extractLastSceneMatrices(normalizedContent);
        if (scene.modelView != null) {
            return scene.modelView;
        }
        return extractLastMatrixForMode(normalizedContent, "GL_MODELVIEW");
    }

    private static double[] extractLastMatrixForMode(String normalizedContent, String targetMode) {
        if (normalizedContent == null || normalizedContent.isBlank()) {
            return null;
        }
        String[] lines = normalizedContent.split("\\n");
        boolean targetMatrixMode = false;
        double[] lastMatrix = null;
        for (String line : lines) {
            Matcher matrixModeMatcher = MATRIX_MODE_LINE_PATTERN.matcher(line);
            if (matrixModeMatcher.find()) {
                String mode = extractToken(MATRIX_MODE_VALUE_PATTERN, matrixModeMatcher.group(1));
                targetMatrixMode = targetMode.equals(mode);
                continue;
            }

            Matcher loadMatrixMatcher = LOAD_MATRIXF_LINE_PATTERN.matcher(line);
            if (targetMatrixMode && loadMatrixMatcher.find()) {
                double[] parsed = parseMatrix4(loadMatrixMatcher.group(1));
                if (parsed != null) {
                    lastMatrix = parsed;
                }
            }
        }
        return lastMatrix;
    }

    public static void applyProjectionMatrixToCamera(Camera camera, double[] projectionMatrix) {
        if (camera == null || projectionMatrix == null || projectionMatrix.length != 16) {
            return;
        }
        double m0 = projectionMatrix[0];
        double m5 = projectionMatrix[5];
        double m10 = projectionMatrix[10];
        double m14 = projectionMatrix[14];

        if (Math.abs(m5) > 1.0e-12) {
            double fovRad = 2.0 * Math.atan(1.0 / m5);
            double fovDeg = Math.toDegrees(fovRad);
            if (isFinite(fovDeg) && fovDeg > 0.1 && fovDeg < 179.0) {
                camera.setFov(fovDeg);
            }
        }

        if (Math.abs(m10 - 1.0) > 1.0e-12 && Math.abs(m10 + 1.0) > 1.0e-12) {
            double near = m14 / (m10 - 1.0);
            double far = m14 / (m10 + 1.0);
            near = Math.abs(near);
            far = Math.abs(far);
            if (isFinite(near) && isFinite(far) && near > 1.0e-6 && far > near) {
                camera.setNearPlaneDistance(near);
                camera.setFarPlaneDistance(far);
            }
        }

        if (Math.abs(m0) > 1.0e-12 && Math.abs(m5) > 1.0e-12) {
            double aspect = m5 / m0;
            if (isFinite(aspect) && aspect > 0.01) {
                double vy = Math.max(1.0, camera.getViewportYSize());
                int viewportY = (int)Math.max(1, Math.round(vy));
                int viewportX = (int)Math.max(1, Math.round(aspect * vy));
                camera.updateViewportResize(viewportX, viewportY);
            }
        }
    }

    public static void applyModelViewMatrixToCamera(Camera camera, double[] modelViewMatrix) {
        if (camera == null || modelViewMatrix == null || modelViewMatrix.length != 16) {
            return;
        }

        double[] eye = extractEyePosition(modelViewMatrix);
        double[] forward = extractForwardDirection(modelViewMatrix);
        if (eye == null || forward == null) {
            return;
        }
        if (!isFinite(eye[0]) || !isFinite(eye[1]) || !isFinite(eye[2])) {
            return;
        }
        if (!isFinite(forward[0]) || !isFinite(forward[1]) || !isFinite(forward[2])) {
            return;
        }

        double fLen = Math.sqrt(
            forward[0] * forward[0] +
            forward[1] * forward[1] +
            forward[2] * forward[2]
        );
        if (!isFinite(fLen) || fLen < 1.0e-12) {
            return;
        }
        double fx = forward[0] / fLen;
        double fy = forward[1] / fLen;
        double fz = forward[2] / fLen;

        camera.setPosition(new Vector3Dd(eye[0], eye[1], eye[2]));
        camera.setFocusedPositionMaintainingOrthogonality(
            new Vector3Dd(eye[0] + fx, eye[1] + fy, eye[2] + fz)
        );
    }

    private static double[] extractEyePosition(double[] m) {
        double tx = m[12];
        double ty = m[13];
        double tz = m[14];

        // view rotation matrix R (world->eye), column-major
        // eye = -R^T * t
        double ex = -(m[0] * tx + m[1] * ty + m[2] * tz);
        double ey = -(m[4] * tx + m[5] * ty + m[6] * tz);
        double ez = -(m[8] * tx + m[9] * ty + m[10] * tz);
        return new double[] { ex, ey, ez };
    }

    private static double[] extractForwardDirection(double[] m) {
        // In this viewer camera convention, "front" must point from eye to scene.
        // Using third row of the view rotation matches the expected direction.
        return new double[] { m[2], m[6], m[10] };
    }

    private static String extractToken(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) return null;
        return matcher.group(1);
    }

    private static SceneMatrices extractLastSceneMatrices(String normalizedContent) {
        if (normalizedContent == null || normalizedContent.isBlank()) {
            return new SceneMatrices(null, null);
        }

        String[] lines = normalizedContent.split("\\n");
        String currentMode = null;
        double[] currentProjection = null;
        double[] currentModelView = null;
        double[] lastSceneProjection = null;
        double[] lastSceneModelView = null;
        int bestCount = -1;
        double bestFar = -1.0;

        for (String line : lines) {
            Matcher matrixModeMatcher = MATRIX_MODE_LINE_PATTERN.matcher(line);
            if (matrixModeMatcher.find()) {
                currentMode = extractToken(MATRIX_MODE_VALUE_PATTERN, matrixModeMatcher.group(1));
                continue;
            }

            Matcher loadMatrixMatcher = LOAD_MATRIXF_LINE_PATTERN.matcher(line);
            if (loadMatrixMatcher.find()) {
                double[] parsed = parseMatrix4(loadMatrixMatcher.group(1));
                if (parsed == null) {
                    continue;
                }
                if ("GL_PROJECTION".equals(currentMode)) {
                    currentProjection = parsed;
                }
                else if ("GL_MODELVIEW".equals(currentMode)) {
                    currentModelView = parsed;
                }
                continue;
            }

            int count = extractSceneDrawCount(line);
            if (count >= 0 && isPerspectiveProjection(currentProjection)) {
                double far = estimateFarPlane(currentProjection);
                if (count > bestCount || (count == bestCount && far > bestFar)) {
                    bestCount = count;
                    bestFar = far;
                    lastSceneProjection = currentProjection == null ? null : currentProjection.clone();
                    lastSceneModelView = currentModelView == null ? null : currentModelView.clone();
                }
            }
        }

        return new SceneMatrices(lastSceneProjection, lastSceneModelView);
    }

    private static int extractSceneDrawCount(String line) {
        Matcher arraysMatcher = DRAW_ARRAYS_PATTERN.matcher(line);
        if (arraysMatcher.find()) {
            String mode = extractToken(DRAW_MODE_VALUE_PATTERN, arraysMatcher.group(1));
            if (!"GL_TRIANGLE_STRIP".equals(mode) && !"GL_TRIANGLES".equals(mode)) {
                return -1;
            }
            String count = extractToken(DRAW_COUNT_VALUE_PATTERN, arraysMatcher.group(1));
            return parsePositiveInt(count);
        }
        Matcher elementsMatcher = DRAW_ELEMENTS_PATTERN.matcher(line);
        if (elementsMatcher.find()) {
            String mode = extractToken(DRAW_MODE_VALUE_PATTERN, elementsMatcher.group(1));
            if (!"GL_TRIANGLE_STRIP".equals(mode) && !"GL_TRIANGLES".equals(mode)) {
                return -1;
            }
            String count = extractToken(DRAW_COUNT_VALUE_PATTERN, elementsMatcher.group(1));
            return parsePositiveInt(count);
        }
        return -1;
    }

    private static int parsePositiveInt(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        }
        catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static boolean isPerspectiveProjection(double[] m) {
        if (m == null || m.length != 16) {
            return false;
        }
        return Math.abs(m[11] + 1.0) < 1.0e-6 && Math.abs(m[15]) < 1.0e-6;
    }

    private static double estimateFarPlane(double[] projectionMatrix) {
        if (projectionMatrix == null || projectionMatrix.length != 16) {
            return -1.0;
        }
        double m10 = projectionMatrix[10];
        double m14 = projectionMatrix[14];
        if (Math.abs(m10 + 1.0) <= 1.0e-12) {
            return -1.0;
        }
        double far = Math.abs(m14 / (m10 + 1.0));
        if (!isFinite(far)) {
            return -1.0;
        }
        return far;
    }

    private static double[] parseMatrix4(String args) {
        Matcher mm = MATRIX_VALUES_PATTERN.matcher(args);
        if (!mm.find()) {
            return null;
        }
        String[] tokens = mm.group(1).split(",");
        if (tokens.length != 16) {
            return null;
        }
        double[] out = new double[16];
        for (int i = 0; i < 16; i++) {
            try {
                out[i] = Double.parseDouble(tokens[i].trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return out;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private record SceneMatrices(double[] projection, double[] modelView) {}
}
