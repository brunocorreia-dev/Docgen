package com.docgen.api;

import com.docgen.desktop.DesktopBootstrap;
import com.docgen.desktop.DesktopProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class DesktopHandshakeTest extends DesktopTestSupport {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    ConfigurableEnvironment environment;

    private static MockHttpServletRequestBuilder handshake(String nonce) {
        return post("/api/v1/desktop/handshake")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nonce\":\"" + nonce + "\"}")
                .with(localPort(LOCAL_PORT));
    }

    @Test
    void theLegitimateBackendProvesPossessionOfTheLaunchSecret() throws Exception {
        String nonce = DesktopProtocol.newNonce(RANDOM);
        String body = mvc.perform(handshake(nonce))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocol").value(DesktopProtocol.VERSION))
                .andReturn().getResponse().getContentAsString();
        String proof = mapper.readTree(body).path("proof").asString();
        long pid = ProcessHandle.current().pid();

        assertTrue(launcher.session().verifyReadinessProof(nonce, pid, LOCAL_PORT, proof));
        assertFalse(launcher.session().verifyReadinessProof(nonce, pid, LOCAL_PORT + 1, proof), "bound to the port");
        assertFalse(DesktopBootstrap.generate(RANDOM).open().verifyReadinessProof(nonce, pid, LOCAL_PORT, proof),
                "a launcher with another secret must reject it");
        assertFalse(body.contains(launcher.secret()), "the secret is never returned");
        assertEquals(2, mapper.readTree(body).size(), "nothing beyond protocol and proof is disclosed");
    }

    @Test
    void eachChallengeIsAnsweredOnlyOnce() throws Exception {
        String nonce = DesktopProtocol.newNonce(RANDOM);
        mvc.perform(handshake(nonce)).andExpect(status().isOk());
        mvc.perform(handshake(nonce))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("nonce_reused"));
    }

    @Test
    void rejectsMalformedNoncesAndRequests() throws Exception {
        String valid = DesktopProtocol.newNonce(RANDOM);
        for (String nonce : new String[]{"short", valid + "A", valid.substring(0, 42) + "+",
                valid.substring(0, 42) + nonCanonical(valid.charAt(42))}) {
            mvc.perform(handshake(nonce)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/desktop/handshake").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nonce\":\"" + valid + "\",\"extra\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/desktop/handshake").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void webPagesCannotUseTheHandshake() throws Exception {
        String nonce = DesktopProtocol.newNonce(RANDOM);
        mvc.perform(handshake(nonce).header("Origin", "http://127.0.0.1:" + LOCAL_PORT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("origin_rejected"));
        mvc.perform(handshake(DesktopProtocol.newNonce(RANDOM)).header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        // A cross-site form can only send simple content types, which never reach the handler.
        mvc.perform(post("/api/v1/desktop/handshake").contentType(MediaType.TEXT_PLAIN)
                        .content("{\"nonce\":\"" + DesktopProtocol.newNonce(RANDOM) + "\"}"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(options("/api/v1/desktop/handshake")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void dnsRebindingIsStillBlocked() throws Exception {
        mvc.perform(handshake(DesktopProtocol.newNonce(RANDOM)).header("Host", "attacker.example"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_host"));
    }

    @Test
    void desktopModeHasNoUsablePassword(CapturedOutput output) throws Exception {
        Object generated = environment.getPropertySources().get(DesktopEnvironmentPostProcessor.PROPERTY_SOURCE)
                .getProperty("docgen.api.password");
        assertNotNull(generated);
        assertEquals(43, generated.toString().length());

        for (String guess : new String[]{"", "desktop-user", "test-password-change-me"}) {
            mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"desktop-user\",\"password\":\"" + guess + "x\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/projects").with(httpBasic("desktop-user", "test-password-change-me")))
                .andExpect(status().isUnauthorized());
        assertFalse(output.getAll().contains(generated.toString()), "the generated password is never printed");
    }

    @Test
    void neitherTheSecretNorCommandsReachTheLogs(CapturedOutput output) throws Exception {
        mvc.perform(handshake(DesktopProtocol.newNonce(RANDOM))).andExpect(status().isOk());
        launcher.send("PING", "field-value-not-logged");
        Thread.sleep(200);

        assertFalse(output.getAll().contains(launcher.secret()));
        assertFalse(launcher.output().contains(launcher.secret()));
        assertFalse(output.getAll().contains("field-value-not-logged"));
    }

    @Test
    void publicRoutesKeepWorkingInDesktopMode() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        mvc.perform(get("/index.html")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized());
    }

    private static char nonCanonical(char last) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int value = alphabet.indexOf(last);
        return alphabet.charAt((value & ~0b11) | ((value & 0b11) ^ 0b01));
    }
}
