package ro.sellfluence.test;

import ro.sellfluence.emagapi.EmagApi;
import ro.sellfluence.emagapi.ProductOffer;
import ro.sellfluence.support.Arguments;
import ro.sellfluence.support.UserPassword;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Inspect the direct product_offer/read API without updating the offers database. */
public class FetchProductOffer {
    public static void main(String[] args) throws IOException, InterruptedException {
        var arguments = new Arguments(args);
        if (arguments.hasFlag("help") || arguments.hasFlag("h")) {
            System.out.println("Usage: FetchProductOffer --account=<alias> [--output=<path>] [--help]");
            System.out.println("Default output: target/product-offers.json (overwritten on each run)");
            return;
        }
        for (var argument : args) {
            if (!argument.startsWith("--account=") && !argument.startsWith("--output=")) {
                throw new IllegalArgumentException("Unknown argument '%s'. Use --help for usage.".formatted(argument));
            }
        }
        var account = arguments.getOption("account");
        if (account == null || account.isBlank()) {
            account = "sellfusion";
            IO.println("Missing account replaced by default: sellfusion");
//            throw new IllegalArgumentException("An account alias is required: --account=<alias>. Use --help for usage.");
        }
        var outputOption = arguments.getOption("output", "/tmp/product-offers.json");
        if (outputOption.isBlank()) {
            throw new IllegalArgumentException("The --output option must contain a file path.");
        }
        var output = Path.of(outputOption).toAbsolutePath().normalize();
        var credentials = UserPassword.findAlias(account);
        if (credentials == null) {
            throw new IllegalArgumentException("No credentials found for account '%s'. Configure its alias in Secrets/userpws.txt."
                    .formatted(account));
        }
        try {
            var count = fetch(new EmagApi(credentials), output, System.out);
            System.err.printf("Fetched %d product offers for %s. Raw response pages saved to %s%n", count, account, output);
        } catch (IOException | InterruptedException | RuntimeException exception) {
            System.err.printf("Could not fetch product offers for %s: %s%n", account, exception.getMessage());
            System.err.printf("Any captured response pages are retained at %s%n", output);
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw exception;
        }
    }

    /** Archive each response before decoding it, and print only a completely fetched result. */
    static int fetch(EmagApi api, Path output, PrintStream stdout) throws IOException, InterruptedException {
        var absoluteOutput = output.toAbsolutePath();
        Files.createDirectories(absoluteOutput.getParent());
        List<ProductOffer> offers;
        try (var writer = Files.newBufferedWriter(absoluteOutput, StandardCharsets.UTF_8)) {
            try (var archive = new RawResponses(writer)) {
                offers = api.readProductOffers(Map.of(), archive);
            }
        }
        offers.forEach(stdout::println);
        return offers.size();
    }

    private static final class RawResponses implements EmagApi.ProductOfferPageConsumer, AutoCloseable {
        private final BufferedWriter writer;
        private boolean first = true;

        private RawResponses(BufferedWriter writer) throws IOException {
            this.writer = writer;
            writer.write("[\n");
            writer.flush();
        }

        @Override
        public void accept(int currentPage, String responseBody) throws IOException {
            if (!first) {
                writer.write(",\n");
            }
            writer.write(responseBody);
            writer.flush();
            first = false;
        }

        @Override
        public void close() throws IOException {
            writer.write("\n]\n");
            writer.flush();
        }
    }
}
