package demresampler.gdal;

import demresampler.io.FabdemSourceTile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class GdalVrtBuilder {
    private static final String SOURCE_NODATA = "-9999";

    private GdalVrtBuilder() {
    }

    public static Path build(Path workDirectory, List<FabdemSourceTile> sources)
        throws IOException, InterruptedException {
        System.out.printf(
            "Building GDAL VRT from %,d readable TIFF files...%n",
            sources.size());
        Path inputList = workDirectory.resolve("input-files.txt");
        StringBuilder paths = new StringBuilder(sources.size() * 96);
        for (FabdemSourceTile source : sources) {
            paths.append(source.path()).append('\n');
        }
        Files.writeString(inputList, paths, StandardCharsets.UTF_8);

        Path vrt = workDirectory.resolve("fabdem.vrt");
        Process process = new ProcessBuilder(
            "gdalbuildvrt",
            "-overwrite",
            "-input_file_list", inputList.toString(),
            "-srcnodata", SOURCE_NODATA,
            "-vrtnodata", SOURCE_NODATA,
            "-resolution", "user",
            "-tr", "0.0002777777777777778", "0.0002777777777777778",
            "-te",
            "-180.0001388888888888889",
            "-89.9998611111111111111",
            "179.9998611111111111111",
            "90.0001388888888888889",
            vrt.toString()
        ).inheritIO().start();
        int exitCode = process.waitFor();
        if (exitCode != 0 || !Files.isRegularFile(vrt)) {
            throw new IOException("gdalbuildvrt failed with exit code " + exitCode);
        }
        return vrt;
    }
}
