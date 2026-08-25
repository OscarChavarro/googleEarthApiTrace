package dumpanalyzer.model.replay;

public record ReplayTexture(int unit, String target, int name, int generation, int revision,
                            String imagePath, int width, int height) { }
