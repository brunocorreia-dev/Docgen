package com.docgen.api;

import com.docgen.desktop.DesktopProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The traditional server (docgen-server, CLI users) has no desktop surface at all. */
class DesktopModeDisabledTest extends ApiTestSupport {
    @Autowired
    ApplicationContext context;

    @Test
    void desktopBeansAndRoutesDoNotExist() throws Exception {
        assertTrue(context.getBeansOfType(DesktopLauncherLink.class).isEmpty());
        String body = "{\"nonce\":\"" + DesktopProtocol.newNonce(new SecureRandom()) + "\"}";
        mvc.perform(post("/api/v1/desktop/handshake").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(authed(post("/api/v1/desktop/handshake"), token()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void passwordLoginsKeepWorking() throws Exception {
        token();
    }
}
