package com.docgen.api;

import com.docgen.desktop.DesktopMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every test gets a fresh API process state (arm window, pending pairing, launcher channel). */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ExtendWith(OutputCaptureExtension.class)
class DesktopPairingTest extends DesktopTestSupport {
    static final String HOST = "127.0.0.1:" + LOCAL_PORT;
    static final String ORIGIN = "http://" + HOST;
    private static final Duration WAIT = Duration.ofSeconds(5);

    record Browser(String pairingId, String pollSecret, String code) {
    }

    /** A request exactly as the workspace page sends it. */
    static MockHttpServletRequestBuilder fromWorkspace(MockHttpServletRequestBuilder builder) {
        return builder.header("Host", HOST).header("Origin", ORIGIN).header(SecurityConfig.WEB_CLIENT_HEADER, "web")
                .header("Sec-Fetch-Site", "same-origin");
    }

    private MvcResult createPairing() throws Exception {
        return mvc.perform(fromWorkspace(post("/api/v1/desktop/pairings")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
    }

    /** "Abrir DocGen": the launcher arms, then the tab asks for a pairing (the command is processed asynchronously). */
    private Browser armAndPair() throws Exception {
        launcher.send("ARM");
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            MvcResult result = createPairing();
            if (result.getResponse().getStatus() == 201) {
                JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
                return new Browser(body.path("pairingId").asString(), body.path("pollSecret").asString(),
                        body.path("code").asString());
            }
            assertEquals(409, result.getResponse().getStatus());
            Thread.sleep(20);
        }
        throw new AssertionError("the pairing was never armed");
    }

    private MvcResult poll(String pairingId, String pollSecret) throws Exception {
        return mvc.perform(fromWorkspace(post("/api/v1/desktop/pairings/" + pairingId + "/token"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pollSecret\":\"" + pollSecret + "\"}")).andReturn();
    }

    private MvcResult pollUntilDecided(Browser browser) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        MvcResult result = poll(browser.pairingId(), browser.pollSecret());
        while (result.getResponse().getStatus() == 202 && System.nanoTime() < deadline) {
            Thread.sleep(20);
            result = poll(browser.pairingId(), browser.pollSecret());
        }
        return result;
    }

    private DesktopMessage requested(Browser browser) throws InterruptedException {
        DesktopMessage event = launcher.awaitEvent("PAIRING_REQUESTED", WAIT);
        assertEquals(browser.pairingId(), event.field(0));
        assertEquals(browser.code(), event.field(1));
        return event;
    }

    @Test
    void theConfirmedBrowserReceivesAnOrdinarySessionExactlyOnce(CapturedOutput output) throws Exception {
        Browser browser = armAndPair();
        assertTrue(browser.code().matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}"));
        requested(browser);
        assertEquals(202, poll(browser.pairingId(), browser.pollSecret()).getResponse().getStatus());

        launcher.send("APPROVE", browser.pairingId(), browser.code());
        MvcResult issued = pollUntilDecided(browser);

        assertEquals(200, issued.getResponse().getStatus());
        JsonNode session = mapper.readTree(issued.getResponse().getContentAsString());
        assertEquals("Bearer", session.path("tokenType").asString());
        assertEquals("desktop-user", session.path("username").asString());
        String token = session.path("accessToken").asString();
        mvc.perform(ApiTestSupport.authed(get("/api/v1/auth/session"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("desktop-user"));
        assertEquals(404, poll(browser.pairingId(), browser.pollSecret()).getResponse().getStatus(), "single use");
        assertEquals(List.of(browser.pairingId(), "completed"), launcher.awaitEvent("PAIRING_CLOSED", WAIT).fields());

        mvc.perform(ApiTestSupport.authed(delete("/api/v1/auth/token"), token)).andExpect(status().isNoContent());
        mvc.perform(ApiTestSupport.authed(get("/api/v1/auth/session"), token)).andExpect(status().isUnauthorized());

        String logs = output.getAll() + launcher.output();
        assertFalse(logs.contains(token), "tokens are never logged");
        assertFalse(logs.contains(browser.pollSecret()), "poll secrets are never logged");
    }

    @Test
    void nothingCanBeRequestedWithoutTheLauncher() throws Exception {
        MvcResult result = createPairing();
        assertEquals(409, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("pairing_unavailable"));
    }

    @Test
    void aSecondRequestCancelsBothAndDisarms() throws Exception {
        Browser first = armAndPair();
        requested(first);

        MvcResult second = createPairing();
        assertEquals(409, second.getResponse().getStatus());
        assertTrue(second.getResponse().getContentAsString().contains("pairing_conflict"));
        assertEquals(List.of(first.pairingId(), "conflict"), launcher.awaitEvent("PAIRING_CLOSED", WAIT).fields());
        assertEquals(404, poll(first.pairingId(), first.pollSecret()).getResponse().getStatus());

        launcher.send("APPROVE", first.pairingId(), first.code());
        Thread.sleep(200);
        assertEquals(404, poll(first.pairingId(), first.pollSecret()).getResponse().getStatus(),
                "a cancelled pairing can never be approved later");
        assertTrue(createPairing().getResponse().getContentAsString().contains("pairing_unavailable"));
    }

    @Test
    void approvalMustNameTheDisplayedCode() throws Exception {
        Browser browser = armAndPair();
        requested(browser);

        launcher.send("APPROVE", browser.pairingId(), "AAAA-AAAA");
        Thread.sleep(200);
        assertEquals(202, poll(browser.pairingId(), browser.pollSecret()).getResponse().getStatus());

        launcher.send("APPROVE", browser.pairingId(), browser.code());
        assertEquals(200, pollUntilDecided(browser).getResponse().getStatus());
    }

    @Test
    void aDeniedRequestIssuesNothing() throws Exception {
        Browser browser = armAndPair();
        requested(browser);

        launcher.send("DENY", browser.pairingId());
        MvcResult denied = pollUntilDecided(browser);

        assertEquals(403, denied.getResponse().getStatus());
        assertTrue(denied.getResponse().getContentAsString().contains("pairing_denied"));
        assertEquals(404, poll(browser.pairingId(), browser.pollSecret()).getResponse().getStatus());
        assertEquals(List.of(browser.pairingId(), "denied"), launcher.awaitEvent("PAIRING_CLOSED", WAIT).fields());
    }

    @Test
    void onlyTheHolderOfThePollSecretCanCollectTheSession() throws Exception {
        Browser browser = armAndPair();
        requested(browser);
        launcher.send("APPROVE", browser.pairingId(), browser.code());
        Thread.sleep(200);

        String forged = "A".repeat(43);
        for (int attempt = 0; attempt < DesktopPairingService.MAX_FAILED_POLLS; attempt++) {
            assertEquals(404, poll(browser.pairingId(), forged).getResponse().getStatus());
        }
        assertEquals(404, poll(browser.pairingId(), browser.pollSecret()).getResponse().getStatus(),
                "repeated guesses cancel the pairing, even for the rightful tab");
        assertEquals(List.of(browser.pairingId(), "cancelled"), launcher.awaitEvent("PAIRING_CLOSED", WAIT).fields());
    }

    @Test
    void reopeningFromTheLauncherReplacesAPendingRequest() throws Exception {
        Browser old = armAndPair();
        requested(old);

        Browser fresh = armAndPair();

        assertEquals(List.of(old.pairingId(), "replaced"), launcher.awaitEvent("PAIRING_CLOSED", WAIT).fields());
        assertEquals(404, poll(old.pairingId(), old.pollSecret()).getResponse().getStatus());
        requested(fresh);
        assertEquals(202, poll(fresh.pairingId(), fresh.pollSecret()).getResponse().getStatus());
    }

    @Test
    void otherWebPagesAndProcessesCannotUseThePairingRoutes() throws Exception {
        launcher.send("ARM");
        Thread.sleep(200);
        MockHttpServletRequestBuilder[] attempts = {
                post("/api/v1/desktop/pairings").header("Host", HOST).header(SecurityConfig.WEB_CLIENT_HEADER, "web"),
                post("/api/v1/desktop/pairings").header("Host", HOST).header("Origin", "https://evil.example")
                        .header(SecurityConfig.WEB_CLIENT_HEADER, "web"),
                post("/api/v1/desktop/pairings").header("Host", HOST).header("Origin", "null")
                        .header(SecurityConfig.WEB_CLIENT_HEADER, "web"),
                post("/api/v1/desktop/pairings").header("Host", HOST).header("Origin", "http://localhost:" + LOCAL_PORT)
                        .header(SecurityConfig.WEB_CLIENT_HEADER, "web"),
                post("/api/v1/desktop/pairings").header("Host", HOST).header("Origin", ORIGIN),
                post("/api/v1/desktop/pairings").header("Host", HOST).header("Origin", ORIGIN)
                        .header(SecurityConfig.WEB_CLIENT_HEADER, "web").header("Sec-Fetch-Site", "cross-site"),
        };
        for (MockHttpServletRequestBuilder attempt : attempts) {
            mvc.perform(attempt.contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("origin_rejected"));
        }
        // Simple content types (cross-site forms) and foreign host names never reach the handler.
        mvc.perform(fromWorkspace(post("/api/v1/desktop/pairings")).contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/v1/desktop/pairings").header("Host", "attacker.example:" + LOCAL_PORT)
                        .header("Origin", "http://attacker.example:" + LOCAL_PORT).header(SecurityConfig.WEB_CLIENT_HEADER, "web")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        // None of the refused attempts consumed the arm window.
        assertEquals(201, createPairing().getResponse().getStatus());
    }

    @Test
    void reportsDesktopModeWithoutRevealingPairingState() throws Exception {
        mvc.perform(get("/api/v1/desktop/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("desktop"))
                .andExpect(jsonPath("$.armed").doesNotExist());
        mvc.perform(fromWorkspace(post("/api/v1/desktop/pairings/not-a-uuid/token")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pollSecret\":\"" + "A".repeat(43) + "\"}"))
                .andExpect(status().isNotFound());
    }
}
