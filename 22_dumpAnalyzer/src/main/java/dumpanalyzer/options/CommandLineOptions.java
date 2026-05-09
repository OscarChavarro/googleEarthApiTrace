package dumpanalyzer.options;

public final class CommandLineOptions {
    private final boolean offline;
    private final int startFrame;
    private final int endFrame;
    private final int width;
    private final int height;
    private final String outputPath;
    private final String tileContentId;

    private CommandLineOptions(
        boolean offline,
        int startFrame,
        int endFrame,
        int width,
        int height,
        String outputPath,
        String tileContentId
    ) {
        this.offline = offline;
        this.startFrame = startFrame;
        this.endFrame = endFrame;
        this.width = width;
        this.height = height;
        this.outputPath = outputPath;
        this.tileContentId = tileContentId;
    }

    public static CommandLineOptions parseArgs(String[] args) {
        boolean offline = false;
        int startFrame = 1;
        int endFrame = 100000;
        int width = 1280;
        int height = 720;
        String outputPath = "/tmp/vitral/testsuite/_APITests/_JOGL4PbufferExample/src/output.png";
        String tileContentId = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--offline".equals(a)) {
                offline = true;
                continue;
            }
            if ("--start-frame".equals(a) && i + 1 < args.length) {
                startFrame = Math.max(1, safeParseInt(args[++i], startFrame));
                continue;
            }
            if ("--end-frame".equals(a) && i + 1 < args.length) {
                endFrame = Math.max(1, safeParseInt(args[++i], endFrame));
                continue;
            }
            if ("--width".equals(a) && i + 1 < args.length) {
                width = Math.max(1, safeParseInt(args[++i], width));
                continue;
            }
            if ("--height".equals(a) && i + 1 < args.length) {
                height = Math.max(1, safeParseInt(args[++i], height));
                continue;
            }
            if ("--output".equals(a) && i + 1 < args.length) {
                outputPath = args[++i];
                continue;
            }
            if ("--tile-content-id".equals(a) && i + 1 < args.length) {
                tileContentId = args[++i];
            }
        }

        return new CommandLineOptions(offline, startFrame, endFrame, width, height, outputPath, tileContentId);
    }

    public boolean offline() {
        return offline;
    }

    public int startFrame() {
        return startFrame;
    }

    public int endFrame() {
        return endFrame;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public String outputPath() {
        return outputPath;
    }

    public String tileContentId() {
        return tileContentId;
    }

    private static int safeParseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        }
        catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
