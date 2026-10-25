package com.docgen.api;

import com.docgen.OllamaEndpoint;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 0003 ("somente local") as a build rule: production code of every module
 * may only address the local Ollama. A text scan cannot prove that no traffic
 * happens at run time (an address could be assembled from pieces); it catches
 * the regressions that matter in review: a remote URL, a second HTTP client or
 * a second provider implementation.
 */
class LocalOnlyArchitectureTest {
    private static final Path ROOT = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize()
            .getParent();
    private static final List<String> MODULES = List.of("docgen-core", "docgen-cli", "docgen-web", "docgen-api");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "js", "html", "css", "svg", "json", "yml", "yaml", "properties", "sql", "factories", "txt", "md");
    private static final Pattern URI = Pattern.compile("(?i)https?://[^\\s\"'`<>)\\]]*");
    /** Loopback, optionally with a port; "{@code http://127.0.0.1:}" alone is a port concatenated at run time. */
    private static final Pattern LOOPBACK = Pattern.compile("http://127\\.0\\.0\\.1(?::[0-9]{0,5})?(?:/\\S*)?");

    /** Identifiers that look like URLs but are never fetched, each with the reason it is safe. */
    private static final Map<String, String> NEVER_FETCHED = Map.of(
            "http://www.w3.org/2000/svg", "XML namespace of inline SVG elements; browsers never request it");

    /** The only file that may write a bare scheme: it prefixes the fixed loopback host constant. */
    private static final String ENDPOINT_SOURCE = "docgen-core/src/main/java/com/docgen/OllamaEndpoint.java";

    /** APIs that open connections, allowed only in the Ollama client and the reopen client. */
    private static final List<String> NETWORK_APIS = List.of(
            "java.net.http.HttpClient", "java.net.http.HttpRequest", "HttpURLConnection", "URLConnection",
            "openConnection(", "openStream(", "java.net.Socket;", "new Socket(", "SocketChannel", "DatagramSocket",
            "RestTemplate", "RestClient", "WebClient", "org.apache.http", "okhttp");
    private static final String REOPEN_CLIENT_SOURCE = "docgen-api/src/main/java/com/docgen/desktop/ReopenClient.java";
    private static final Set<String> NETWORK_CLIENT_SOURCES = Set.of(
            "docgen-core/src/main/java/com/docgen/OllamaProvider.java",
            "docgen-core/src/main/java/com/docgen/OllamaClient.java",
            "docgen-core/src/main/java/com/docgen/HttpExchange.java",
            "docgen-core/src/main/java/com/docgen/BoundedUtf8BodySubscriber.java",
            // ADR 0001: a second start of the app asks the running one, on 127.0.0.1, to open a window.
            REOPEN_CLIENT_SOURCE);

    private static final Pattern IMPLEMENTS_PROVIDER = Pattern.compile("implements\\s+[\\w.,\\s<>]*\\bLLMProvider\\b");

    @Test
    void theOllamaHostIsTheLoopbackLiteral() {
        assertEquals("127.0.0.1", OllamaEndpoint.HOST);
    }

    @Test
    void productionCodeAddressesNothingButLoopback() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Source source : productionSources()) {
            for (int number = 0; number < source.lines().size(); number++) {
                Matcher matcher = URI.matcher(source.lines().get(number));
                while (matcher.find()) {
                    String uri = matcher.group();
                    boolean bareSchemeInEndpoint = source.path().equals(ENDPOINT_SOURCE) && uri.equals("http://");
                    if (!bareSchemeInEndpoint && !LOOPBACK.matcher(uri).matches()
                            && !NEVER_FETCHED.containsKey(uri)) {
                        violations.add(source.path() + ":" + (number + 1) + " " + uri);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Only loopback addresses are allowed (ADR 0003): " + violations);
    }

    @Test
    void onlyTheOllamaClientUsesNetworkApis() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Source source : productionSources()) {
            if (!source.path().endsWith(".java") || NETWORK_CLIENT_SOURCES.contains(source.path())) {
                continue;
            }
            for (int number = 0; number < source.lines().size(); number++) {
                String line = source.lines().get(number);
                if (line.strip().startsWith("//") || line.strip().startsWith("*")) {
                    continue;
                }
                for (String api : NETWORK_APIS) {
                    if (line.contains(api)) {
                        violations.add(source.path() + ":" + (number + 1) + " uses " + api);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Network clients outside the Ollama client (ADR 0003): " + violations);
    }

    @Test
    void theReopenClientOnlyTalksToItsOwnLoopbackServer() throws IOException {
        String source = Files.readString(ROOT.resolve(REOPEN_CLIENT_SOURCE), StandardCharsets.UTF_8);

        assertTrue(source.contains("static final String HOST = \"127.0.0.1\";"), "fixed loopback host");
        assertTrue(source.contains("new URI(\"http\", null, HOST, port,"), "address built from the constant host");
        assertTrue(source.contains(".proxy(HttpClient.Builder.NO_PROXY)"), "never through a proxy");
        assertTrue(source.contains(".followRedirects(HttpClient.Redirect.NEVER)"), "never redirected elsewhere");
    }

    @Test
    void ollamaIsTheOnlyProviderImplementation() throws IOException {
        List<String> implementations = new ArrayList<>();
        for (Source source : productionSources()) {
            String text = String.join("\n", source.lines());
            if (source.path().endsWith(".java")
                    && (IMPLEMENTS_PROVIDER.matcher(text).find() || text.contains("new LLMProvider()"))) {
                implementations.add(source.path());
            }
        }
        assertEquals(List.of("docgen-core/src/main/java/com/docgen/OllamaProvider.java"), implementations);
    }

    @Test
    void theServerOnlyListensOnLoopback() throws IOException {
        String configuration = Files.readString(
                ROOT.resolve("docgen-api/src/main/resources/application.yml"), StandardCharsets.UTF_8);

        assertTrue(configuration.contains("  address: 127.0.0.1\n"), "server.address must stay 127.0.0.1");
    }

    @Test
    void theScanCoversEveryModule() throws IOException {
        Set<String> scanned = new java.util.HashSet<>();
        for (Source source : productionSources()) {
            scanned.add(source.path().substring(0, source.path().indexOf('/')));
        }
        assertEquals(Set.copyOf(MODULES), scanned);
    }

    private record Source(String path, List<String> lines) {
    }

    private static List<Source> productionSources() throws IOException {
        assertTrue(Files.isRegularFile(ROOT.resolve("pom.xml")), "repository root not found: " + ROOT);
        List<Source> sources = new ArrayList<>();
        for (String module : MODULES) {
            Path main = ROOT.resolve(module).resolve("src").resolve("main");
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : files.filter(Files::isRegularFile).filter(LocalOnlyArchitectureTest::isText).toList()) {
                    String relative = ROOT.relativize(file).toString().replace('\\', '/');
                    sources.add(new Source(relative, Files.readAllLines(file, StandardCharsets.UTF_8)));
                }
            }
        }
        return sources;
    }

    private static boolean isText(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1));
    }
}
