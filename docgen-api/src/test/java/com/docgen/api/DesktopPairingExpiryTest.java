package com.docgen.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@TestPropertySource(properties = {"docgen.desktop.arm-window=PT1S", "docgen.desktop.pairing-ttl=PT1S"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DesktopPairingExpiryTest extends DesktopTestSupport {
    private MvcResult create() throws Exception {
        return mvc.perform(DesktopPairingTest.fromWorkspace(post("/api/v1/desktop/pairings"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
    }

    @Test
    void theArmWindowCloses() throws Exception {
        launcher.send("ARM");
        Thread.sleep(1_300);
        MvcResult late = create();
        assertEquals(409, late.getResponse().getStatus());
        assertTrue(late.getResponse().getContentAsString().contains("pairing_unavailable"));
    }

    @Test
    void anUnconfirmedRequestExpires() throws Exception {
        launcher.send("ARM");
        Thread.sleep(150);
        MvcResult created = create();
        assertEquals(201, created.getResponse().getStatus());
        JsonNode body = mapper.readTree(created.getResponse().getContentAsString());
        String pairingId = body.path("pairingId").asString();

        Thread.sleep(1_300);
        launcher.send("APPROVE", pairingId, body.path("code").asString());
        Thread.sleep(150);
        MvcResult expired = mvc.perform(DesktopPairingTest.fromWorkspace(post("/api/v1/desktop/pairings/" + pairingId + "/token"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pollSecret\":\"" + body.path("pollSecret").asString() + "\"}")).andReturn();

        assertEquals(410, expired.getResponse().getStatus());
        assertTrue(expired.getResponse().getContentAsString().contains("pairing_expired"));
        assertEquals(List.of(pairingId, "expired"), launcher.awaitEvent("PAIRING_CLOSED", Duration.ofSeconds(5)).fields());
    }
}
