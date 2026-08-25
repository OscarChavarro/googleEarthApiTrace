package dumpanalyzer.model.replay;

public record ReplayState(double[] color, boolean blendEnabled, String blendSrc, String blendDst,
                          boolean depthTestEnabled, boolean depthMask, String depthFunc,
                          boolean alphaTestEnabled, boolean cullEnabled,
                          boolean texture2dEnabled, int program) {
    public ReplayState {
        color = color == null ? new double[] {1, 1, 1, 1} : color.clone();
    }
    @Override public double[] color() { return color.clone(); }
}
