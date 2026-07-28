package demresampler.gdal;

import demresampler.model.RasterMetadata;

public record TiffHeaderMetadata(RasterMetadata raster, boolean wgs84Geographic) {
    String serialize() {
        return String.join(",",
            Integer.toString(raster.width()),
            Integer.toString(raster.height()),
            Double.toString(raster.originX()),
            Double.toString(raster.pixelWidth()),
            Double.toString(raster.rotationX()),
            Double.toString(raster.originY()),
            Double.toString(raster.rotationY()),
            Double.toString(raster.pixelHeight()),
            Double.toString(raster.noData()),
            Boolean.toString(raster.hasNoData()),
            Integer.toString(raster.gdalDataType()),
            Boolean.toString(wgs84Geographic));
    }

    static TiffHeaderMetadata parse(String serialized) {
        String[] fields = serialized.split(",", -1);
        if (fields.length != 12) {
            throw new IllegalArgumentException("expected 12 TIFF metadata fields");
        }
        RasterMetadata raster = new RasterMetadata(
            Integer.parseInt(fields[0]),
            Integer.parseInt(fields[1]),
            Double.parseDouble(fields[2]),
            Double.parseDouble(fields[3]),
            Double.parseDouble(fields[4]),
            Double.parseDouble(fields[5]),
            Double.parseDouble(fields[6]),
            Double.parseDouble(fields[7]),
            Double.parseDouble(fields[8]),
            Boolean.parseBoolean(fields[9]),
            Integer.parseInt(fields[10]));
        return new TiffHeaderMetadata(raster, Boolean.parseBoolean(fields[11]));
    }
}
