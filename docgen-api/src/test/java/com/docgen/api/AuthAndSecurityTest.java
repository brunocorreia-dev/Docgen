package com.docgen.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR 0002: launch token → session token, and the defenses in depth around it. */
class AuthAndSecurityTest extends ApiTestSupport {
    private MvcResult exchange(String launchToken, int expectedStatus) throws Exception {
        return mvc.perform(post("/api/v1/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"launchToken\":\"" + launchToken + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    @Test
    void aLaunchTokenBecomesASessionThatAuthenticatesAndCanBeEnded() throws Exception {
        String token = token();

        mvc.perform(authed(get("/api/v1/session"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value("local"))
                .andExpect(jsonPath("$.idleTimeoutSeconds").value(12 * 3600));
        mvc.perform(authed(delete("/api/v1/session"), token)).andExpect(status().isNoContent());
        mvc.perform(authed(get("/api/v1/session"), token)).andExpect(status().isUnauthorized());
    }

    @Test
    void theExchangeReturnsTheSessionInTheBodyAndNeverSetsACookie() throws Exception {
        MvcResult result = exchange(launchTokens.issue(), 201);

        assertNull(result.getResponse().getHeader("Set-Cookie"));
        String body = result.getResponse().getContentAsString();
        assertEquals("Bearer", mapper.readTree(body).path("tokenType").asString());
        assertEquals(43, mapper.readTree(body).path("accessToken").asString().length());
    }

    @Test
    void unknownOrMalformedLaunchTokensAreRefusedWithoutDetail() throws Exception {
        for (String token : new String[]{"A".repeat(43), "short", "../../etc/passwd", "x".repeat(64)}) {
            exchange(token, 401);
        }
        mvc.perform(post("/api/v1/session").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"launchToken\":\"" + "A".repeat(43) + "\"}"))
                .andExpect(jsonPath("$.code").value("launch_token_invalid"))
                .andExpect(jsonPath("$.message").value("The launch link is invalid or expired."));
    }

    @Test
    void aLaunchTokenWorksOnceAndItsReuseEndsTheSessionItCreated() throws Exception {
        String launch = launchTokens.issue();
        String first = mapper.readTree(exchange(launch, 201).getResponse().getContentAsString())
                .path("accessToken").asString();
        mvc.perform(authed(get("/api/v1/projects"), first)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"launchToken\":\"" + launch + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("launch_token_reused"));

        mvc.perform(authed(get("/api/v1/projects"), first)).andExpect(status().isUnauthorized());
        exchange(launch, 409);
    }

    @Test
    void basicAuthenticationIsNotAcceptedAnyMore() throws Exception {
        mvc.perform(get("/api/v1/projects").with(httpBasic("local", "any-password-at-all")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"docgen-local\""));
        mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a\",\"password\":\"b\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forgedOrMissingTokensAreRejectedWithABearerChallenge() throws Exception {
        mvc.perform(authed(get("/api/v1/projects"), "A".repeat(43)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"docgen-local\""));
        mvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("authentication_required"));
    }

    @Test
    void stateChangingRequestsRequireANonSimpleContentType() throws Exception {
        String token = token();
        // A cross-site HTML form can only send these types, so they must never reach a handler.
        for (String type : new String[]{"text/plain", "application/x-www-form-urlencoded", "multipart/form-data"}) {
            mvc.perform(authed(post("/api/v1/projects"), token).contentType(type).content(projectJson("x", "ollama")))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("unsupported_media_type"));
        }
        mvc.perform(authed(post("/api/v1/projects"), token).content(projectJson("x", "ollama")))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void stateChangingRequestsFromAnotherOriginAreRefusedBeforeAuthentication() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");
        String[] foreignOrigins = {"https://evil.example", "http://127.0.0.1:1", "http://localhost:8080", "null",
                "https://127.0.0.1:" + LoopbackMockMvc.PORT};
        for (String origin : foreignOrigins) {
            mvc.perform(authed(post("/api/v1/projects"), token).header("Origin", origin)
                            .contentType(MediaType.APPLICATION_JSON).content(projectJson("x", "ollama")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("origin_rejected"));
            mvc.perform(authed(put("/api/v1/projects/" + project), token).header("Origin", origin)
                            .contentType(MediaType.APPLICATION_JSON).content(projectJson("x", "ollama")))
                    .andExpect(status().isForbidden());
            mvc.perform(authed(delete("/api/v1/projects/" + project), token).header("Origin", origin))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/session").header("Origin", origin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"launchToken\":\"" + launchTokens.issue() + "\"}"))
                    .andExpect(status().isForbidden());
        }
        for (String site : new String[]{"cross-site", "same-site", "none"}) {
            mvc.perform(authed(delete("/api/v1/projects/" + project), token).header("Sec-Fetch-Site", site))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("origin_rejected"));
        }
        mvc.perform(authed(get("/api/v1/projects/" + project), token)).andExpect(status().isOk());
    }

    @Test
    void theAppsOwnOriginIsAccepted() throws Exception {
        String token = token();
        for (String origin : new String[]{LoopbackMockMvc.ORIGIN, "http://localhost:" + LoopbackMockMvc.PORT}) {
            mvc.perform(authed(post("/api/v1/projects"), token).header("Origin", origin)
                            .header("Sec-Fetch-Site", "same-origin")
                            .contentType(MediaType.APPLICATION_JSON).content(projectJson("x", "ollama")))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void grantsNoCrossOriginAccess() throws Exception {
        mvc.perform(options("/api/v1/projects")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/v1/health").header("Origin", "https://evil.example"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void rejectsForeignHostsAndPortsToBlockDnsRebinding() throws Exception {
        String[] foreignHosts = {"attacker.example", "attacker.example:" + LoopbackMockMvc.PORT, "127.0.0.1",
                "127.0.0.1:8080", "localhost", "localhost:1", "[::1]:" + LoopbackMockMvc.PORT,
                "127.0.0.2:" + LoopbackMockMvc.PORT};
        for (String host : foreignHosts) {
            mvc.perform(get("/api/v1/health").header("Host", host))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_host"));
            mvc.perform(get("/launch").header("Host", host)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/health").header("Host", LoopbackMockMvc.HOST)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/health").header("Host", "LOCALHOST:" + LoopbackMockMvc.PORT))
                .andExpect(status().isOk());
    }

    @Test
    void servesTheWorkspaceAndTheLaunchPageWithAStrictContentSecurityPolicy() throws Exception {
        for (String page : new String[]{"/index.html", "/launch"}) {
            mvc.perform(get(page))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
                    .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
        mvc.perform(get("/js/main.js")).andExpect(status().isOk());
        mvc.perform(get("/js/launch.js")).andExpect(status().isOk());
    }

    @Test
    void theLaunchPageIsServedAtLaunch() throws Exception {
        mvc.perform(get("/launch")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("/launch.html"));
        String html = mvc.perform(get("/launch.html")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("<script type=\"module\" src=\"/js/launch.js\"></script>"));
    }

    @Test
    void unknownRoutesAreDeniedWithoutLeakingDetails() throws Exception {
        String token = token();
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(authed(get("/api/v2/anything"), token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Access is not authorized."));
        mvc.perform(authed(get("/api/v1/does-not-exist"), token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void withoutTheDesktopLauncherTheLifecycleRoutesRefuse() throws Exception {
        mvc.perform(post(DesktopControl.REOPEN_PATH).header(DesktopControl.INSTANCE_SECRET_HEADER, "S".repeat(43))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(authed(post("/api/v1/desktop/shutdown"), token())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("desktop_unavailable"));
    }

    @Test
    void theRemovedRoutesNoLongerExist() throws Exception {
        String token = token();
        mvc.perform(get("/api/v1/providers")).andExpect(status().isUnauthorized());
        mvc.perform(authed(get("/api/v1/providers"), token)).andExpect(status().isNotFound());
        mvc.perform(authed(get("/api/v1/auth/session"), token)).andExpect(status().isNotFound());
    }
}
