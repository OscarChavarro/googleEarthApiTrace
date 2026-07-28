package demresampler;

import demresampler.options.CliOptions;

public final class Main {
    private Main() {
    }

    public static void main(String[] arguments) {
        try {
            CliOptions options = CliOptions.parse(arguments);
            new PyramidBuilder().build(options);
        } catch (IllegalArgumentException exception) {
            System.err.println("ERROR: " + exception.getMessage());
            System.err.println(CliOptions.usage());
            System.exit(1);
        } catch (Exception exception) {
            System.err.println("ERROR: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
