package dumpanalyzer.io.parser;

@FunctionalInterface
public interface ReplayTextureResolver {
    String resolveTexturePath(int frameId, int textureId);
}
