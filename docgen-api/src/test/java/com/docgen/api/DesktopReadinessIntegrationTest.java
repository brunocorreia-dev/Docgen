package com.docgen.api;

import com.docgen.desktop.DesktopBootstrap;
import com.docgen.desktop.DesktopMessage;
import com.docgen.desktop.DesktopProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real HTTP path the launcher uses: the API binds a port, announces it
 * over the authenticated channel, and proves its identity on that port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "docgen.desktop.enabled=true",
        "docgen.api.username=desktop-user",
        "docgen.storage.in-memory=true",
        "docgen.remote.enabled=false"
})
@Import({DesktopTestSupport.FakeLauncher.class, ApiTestSupport.FakeProviders.class})
class DesktopReadinessIntegrationTest {
    private static final SecureRandom RANDOM = new SecureRandom();

    @LocalServerPort
    int port;

    @Autowired
    FakeLauncherSide launcher;

    @Autowired
    JsonMapper mapper;

    private HttpResponse<String> post(String path, String json, String... headers) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            for (int index = 0; index < headers.length; index += 2) {
                request.header(headers[index], headers[index + 1]);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void announcesTheBoundPortAndProvesItsIdentityThere() throws Exception {
        DesktopMessage ready = launcher.awaitEvent("READY", Duration.ofSeconds(10));
        long pid = ProcessHandle.current().pid();
        assertEquals(List.of(String.valueOf(port), String.valueOf(pid)), ready.fields());

        String nonce = DesktopProtocol.newNonce(RANDOM);
        HttpResponse<String> response = post("/api/v1/desktop/handshake", "{\"nonce\":\"" + nonce + "\"}");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        JsonNode body = mapper.readTree(response.body());

        assertTrue(launcher.session().verifyReadinessProof(nonce, pid, port, body.path("proof").asString()));
        assertFalse(DesktopBootstrap.generate(RANDOM).open()
                .verifyReadinessProof(nonce, pid, port, body.path("proof").asString()), "wrong secret");
    }

    @Test
    void healthAloneProvesNothing() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()) {
            HttpResponse<String> health = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/api/v1/health")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertFalse(health.body().contains("proof"));
        }
    }

    @Test
    void browsersAreRefusedOverRealHttpToo() throws Exception {
        HttpResponse<String> response = post("/api/v1/desktop/handshake",
                "{\"nonce\":\"" + DesktopProtocol.newNonce(RANDOM) + "\"}", "Origin", "http://127.0.0.1:" + port);
        assertEquals(403, response.statusCode());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }
}
