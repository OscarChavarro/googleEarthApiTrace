package pyramidalimagebuilder.processing;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pyramidalimagebuilder.model.TileInstance;
import vsdk.toolkit.io.image.ImagePersistence;

public class ImageBorderFilterer {
    private static final String NORTH = "NORTH";
    private static final String SOUTH = "SOUTH";
    private static final String EAST = "EAST";
    private static final String WEST = "WEST";

    public static void filter(List<TileInstance> input, double imageBorderThreshold) {
        if (input == null || input.isEmpty()) {
            return;
        }
        List<MutableTileNeighbors> mutableTiles = new ArrayList<>(input.size());
        Map<Integer, MutableTileNeighbors> tileById = new HashMap<>();
        for (TileInstance tile : input) {
            if (tile != null) {
                MutableTileNeighbors mt = new MutableTileNeighbors(tile);
                mutableTiles.add(mt);
                tileById.put(tile.getTileId(), mt);
            }
        }

        Map<String, Object> imageCache = new HashMap<>();
        for (MutableTileNeighbors source : mutableTiles) {
            if (source == null) {
                continue;
            }
            probeNeighbor(source, source.north, NORTH, tileById, imageCache, imageBorderThreshold);
            probeNeighbor(source, source.south, SOUTH, tileById, imageCache, imageBorderThreshold);
            probeNeighbor(source, source.east, EAST, tileById, imageCache, imageBorderThreshold);
            probeNeighbor(source, source.west, WEST, tileById, imageCache, imageBorderThreshold);
        }

        for (int i = 0; i < input.size(); i++) {
            TileInstance current = input.get(i);
            if (current == null) {
                continue;
            }
            MutableTileNeighbors mt = tileById.get(current.getTileId());
            if (mt == null) {
                continue;
            }
            input.set(i, mt.toTileInstance());
        }
    }

    private static void probeNeighbor(
        MutableTileNeighbors source,
        Integer neighborId,
        String direction,
        Map<Integer, MutableTileNeighbors> tileById,
        Map<String, Object> imageCache,
        double imageBorderThreshold
    ) {
        if (neighborId == null) {
            return;
        }
        MutableTileNeighbors target = tileById.get(neighborId);
        double distance = estimateBorderDistance(source, target, direction, imageCache);
        System.out.println(
            "ImageBorderFilterer: sourceTileId=" + source.tileId
                + " targetTileId=" + neighborId
                + " direction=" + direction
                + " distance=" + distance
                + " threshold=" + imageBorderThreshold
        );
        if (distance > imageBorderThreshold) {
            clearDirection(source, direction);
            if (target != null) {
                clearReciprocal(target, source.tileId, opposite(direction));
            }
        }
    }

    private static double estimateBorderDistance(
        MutableTileNeighbors source,
        MutableTileNeighbors target,
        String direction,
        Map<String, Object> imageCache
    ) {
        Object srcImg = loadImage(source == null ? null : source.textureFile, imageCache);
        Object dstImg = loadImage(target == null ? null : target.textureFile, imageCache);
        if (srcImg == null || dstImg == null) {
            return Double.POSITIVE_INFINITY;
        }

        int srcW = imageWidth(srcImg);
        int srcH = imageHeight(srcImg);
        int dstW = imageWidth(dstImg);
        int dstH = imageHeight(dstImg);
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            return Double.POSITIVE_INFINITY;
        }

        boolean horizontal = EAST.equals(direction) || WEST.equals(direction);
        int samples = horizontal ? Math.min(srcH, dstH) : Math.min(srcW, dstW);
        if (samples <= 0) {
            return Double.POSITIVE_INFINITY;
        }

        double distance = 0.0;
        for (int i = 0; i < samples; i++) {
            int sx;
            int sy;
            int tx;
            int ty;
            if (NORTH.equals(direction)) {
                sx = i;
                sy = 0;
                tx = i;
                ty = dstH - 1;
            }
            else if (SOUTH.equals(direction)) {
                sx = i;
                sy = srcH - 1;
                tx = i;
                ty = 0;
            }
            else if (EAST.equals(direction)) {
                sx = srcW - 1;
                sy = i;
                tx = 0;
                ty = i;
            }
            else {
                sx = 0;
                sy = i;
                tx = dstW - 1;
                ty = i;
            }

            double[] sc = rgbAt(srcImg, sx, sy);
            double[] tc = rgbAt(dstImg, tx, ty);
            if (sc == null || tc == null) {
                return Double.POSITIVE_INFINITY;
            }
            distance += Math.abs(sc[0] - tc[0]) + Math.abs(sc[1] - tc[1]) + Math.abs(sc[2] - tc[2]);
        }
        return distance;
    }

    private static Object loadImage(String path, Map<String, Object> imageCache) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Object cached = imageCache.get(path);
        if (cached != null || imageCache.containsKey(path)) {
            return cached;
        }
        try {
            Object image = ImagePersistence.importRGB(new File(path));
            imageCache.put(path, image);
            return image;
        }
        catch (Exception ex) {
            imageCache.put(path, null);
            return null;
        }
    }

    private static int imageWidth(Object image) {
        Number n = invokeNumberMethod(image, "getXSize");
        return n == null ? -1 : n.intValue();
    }

    private static int imageHeight(Object image) {
        Number n = invokeNumberMethod(image, "getYSize");
        return n == null ? -1 : n.intValue();
    }

    private static double[] rgbAt(Object image, int x, int y) {
        Object pixel = invokeMethod(image, "getPixelRgb", x, y);
        if (pixel == null) {
            pixel = invokeMethod(image, "getPixel", x, y);
        }
        if (pixel == null) {
            return null;
        }

        if (pixel instanceof Number packed) {
            int v = packed.intValue();
            return new double[] {((v >> 16) & 0xFF), ((v >> 8) & 0xFF), (v & 0xFF)};
        }

        Double r = readColorComponent(pixel, "r", "getR", "getRed");
        Double g = readColorComponent(pixel, "g", "getG", "getGreen");
        Double b = readColorComponent(pixel, "b", "getB", "getBlue");
        if (r == null || g == null || b == null) {
            return null;
        }
        return new double[] {normalizeColorComponent(r), normalizeColorComponent(g), normalizeColorComponent(b)};
    }

    private static Double readColorComponent(Object pixel, String fieldName, String getterA, String getterB) {
        Number n = invokeNumberMethod(pixel, getterA);
        if (n == null) {
            n = invokeNumberMethod(pixel, getterB);
        }
        if (n == null) {
            n = readNumberField(pixel, fieldName);
        }
        return n == null ? null : n.doubleValue();
    }

    private static double normalizeColorComponent(double c) {
        if (c < 0.0) {
            return c + 256.0;
        }
        if (c <= 1.0) {
            return c * 255.0;
        }
        return c;
    }

    private static Number readNumberField(Object target, String name) {
        try {
            Field f = target.getClass().getField(name);
            Object v = f.get(target);
            if (v instanceof Number n) {
                return n;
            }
            return null;
        }
        catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static Number invokeNumberMethod(Object target, String name, Object... args) {
        Object v = invokeMethod(target, name, args);
        if (v instanceof Number n) {
            return n;
        }
        return null;
    }

    private static Object invokeMethod(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        Class<?>[] argTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            argTypes[i] = primitiveType(args[i].getClass());
        }
        try {
            Method m = target.getClass().getMethod(name, argTypes);
            return m.invoke(target, args);
        }
        catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static Class<?> primitiveType(Class<?> c) {
        if (Integer.class.equals(c)) return int.class;
        if (Long.class.equals(c)) return long.class;
        if (Double.class.equals(c)) return double.class;
        if (Float.class.equals(c)) return float.class;
        if (Boolean.class.equals(c)) return boolean.class;
        if (Short.class.equals(c)) return short.class;
        if (Byte.class.equals(c)) return byte.class;
        if (Character.class.equals(c)) return char.class;
        return c;
    }

    private static void clearDirection(MutableTileNeighbors source, String direction) {
        if (NORTH.equals(direction)) {
            source.north = null;
        }
        else if (SOUTH.equals(direction)) {
            source.south = null;
        }
        else if (EAST.equals(direction)) {
            source.east = null;
        }
        else if (WEST.equals(direction)) {
            source.west = null;
        }
    }

    private static void clearReciprocal(MutableTileNeighbors target, int sourceId, String oppositeDirection) {
        if (NORTH.equals(oppositeDirection) && target.north != null && target.north == sourceId) {
            target.north = null;
        }
        if (SOUTH.equals(oppositeDirection) && target.south != null && target.south == sourceId) {
            target.south = null;
        }
        if (EAST.equals(oppositeDirection) && target.east != null && target.east == sourceId) {
            target.east = null;
        }
        if (WEST.equals(oppositeDirection) && target.west != null && target.west == sourceId) {
            target.west = null;
        }
        if (target.north != null && target.north == sourceId) target.north = null;
        if (target.south != null && target.south == sourceId) target.south = null;
        if (target.east != null && target.east == sourceId) target.east = null;
        if (target.west != null && target.west == sourceId) target.west = null;
    }

    private static String opposite(String direction) {
        if (NORTH.equals(direction)) return SOUTH;
        if (SOUTH.equals(direction)) return NORTH;
        if (EAST.equals(direction)) return WEST;
        return EAST;
    }

    private static final class MutableTileNeighbors {
        private final int tileId;
        private final int frameId;
        private final String textureFile;
        private Integer south;
        private Integer north;
        private Integer east;
        private Integer west;

        private MutableTileNeighbors(TileInstance tile) {
            this.tileId = tile.getTileId();
            this.frameId = tile.getFrameId();
            this.textureFile = tile.getTextureFile();
            this.south = tile.getSouthNeighbor();
            this.north = tile.getNorthNeighbor();
            this.east = tile.getEastNeighbor();
            this.west = tile.getWestNeighbor();
        }

        private TileInstance toTileInstance() {
            return new TileInstance(tileId, frameId, textureFile, south, north, east, west);
        }
    }
}
