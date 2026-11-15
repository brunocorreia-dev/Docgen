package com.docgen.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Desktop lifecycle routes (ADR 0001) against a recording stand-in for the
 * launcher, with a clock the tests move by hand.
 */
@Import(DesktopApiTest.FakeDesktop.class)
@TestPropertySource(properties = "docgen.desktop.idle-check-interval=1h")
class DesktopApiTest extends ApiTestSupport {
    static final String SECRET = "S".repeat(43);

    /** Records what the API asked of the launcher. */
    static final class RecordingDesktop implements DesktopControl {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public boolean acceptsInstanceSecret(String candidate) {
            return SECRET.equals(candidate);
        }

        @Override
        public void openWindow() {
            calls.add("open");
        }

        @Override
        public void requestShutdown(String reason) {
            calls.add("shutdown:" + reason);
        }
    }

    @TestConfiguration
    static class FakeDesktop {
        @Bean
        RecordingDesktop desktopControl() {
            return new RecordingDesktop();
        }

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-24T12:00:00Z"));
        }
    }

    @Autowired
    RecordingDesktop desktop;

    @Autowired
    MutableClock clock;

    @Autowired
    IdleShutdownMonitor monitor;

    @BeforeEach
    void clearCalls() {
        desktop.calls.clear();
    }

    private static MockHttpServletRequestBuilder reopen() {
        return post(DesktopControl.REOPEN_PATH).contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    @Test
    void aSecondStartWithTheInstanceSecretOpensAWindow() throws Exception {
        mvc.perform(reopen().header(DesktopControl.INSTANCE_SECRET_HEADER, SECRET)).andExpect(status().isAccepted());

        assertEquals(List.of("open"), desktop.calls);
    }

    @Test
    void reopenIsRefusedWithAWrongOrMissingSecret() throws Exception {
        mvc.perform(reopen().header(DesktopControl.INSTANCE_SECRET_HEADER, "T".repeat(43)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access_denied"));
        mvc.perform(reopen()).andExpect(status().isForbidden());

        assertTrue(desktop.calls.isEmpty());
    }

    @Test
    void reopenIsRefusedWheneverABrowserSendsIt() throws Exception {
        String[][] browserHeaders = {{"Origin", LoopbackMockMvc.ORIGIN}, {"Origin", "https://evil.example"},
                {"Sec-Fetch-Site", "same-origin"}, {"Sec-Fetch-Mode", "cors"}, {"Sec-Fetch-Dest", "empty"}};
        for (String[] header : browserHeaders) {
            mvc.perform(reopen().header(DesktopControl.INSTANCE_SECRET_HEADER, SECRET).header(header[0], header[1]))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("origin_rejected"));
        }

        assertTrue(desktop.calls.isEmpty());
    }

    @Test
    void heartbeatAndShutdownNeedASession() throws Exception {
        mvc.perform(post("/api/v1/desktop/heartbeat").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/desktop/shutdown").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        String token = token();
        mvc.perform(authed(post("/api/v1/desktop/heartbeat"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isNoContent());

        assertTrue(desktop.calls.isEmpty());
    }

    @Test
    void leavingThroughTheUiStopsTheApp() throws Exception {
        String token = token();
        mvc.perform(authed(post("/api/v1/desktop/shutdown"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isAccepted());

        assertEquals(List.of("shutdown:user"), desktop.calls);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void theAppStopsAfterTenMinutesWithoutAHeartbeat() throws Exception {
        String token = token();
        clock.advance(Duration.ofMinutes(9));
        monitor.check();
        mvc.perform(authed(post("/api/v1/desktop/heartbeat"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isNoContent());
        clock.advance(Duration.ofMinutes(9));
        monitor.check();
        assertTrue(desktop.calls.isEmpty(), "a heartbeat restarts the countdown");

        clock.advance(Duration.ofMinutes(1));
        monitor.check();
        monitor.check();

        assertEquals(List.of("shutdown:idle"), desktop.calls, "asked once");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void theAppNeverStopsDuringAGenerationAndCountsFromItsEnd() throws Exception {
        String token = token();
        String project = createProject(token, "ollama");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeLlm.enqueue(FakeLlm.blocking(started, release, validPlanJson()));
        String jobId = json(authed(post("/api/v1/projects/" + project + "/plans"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 202).path("id").asString();
        assertTrue(started.await(10, TimeUnit.SECONDS));

        clock.advance(Duration.ofHours(2));
        monitor.check();
        assertTrue(desktop.calls.isEmpty(), "never during a generation");

        release.countDown();
        awaitJob(token, jobId);
        clock.advance(Duration.ofMinutes(9));
        monitor.check();
        assertTrue(desktop.calls.isEmpty(), "the countdown starts when the generation ends");
        clock.advance(Duration.ofMinutes(1));
        monitor.check();

        assertEquals(List.of("shutdown:idle"), desktop.calls);
    }

    @Test
    void theIdleRuleIsAPureFunction() {
        Instant start = Instant.parse("2026-10-24T12:00:00Z");
        Duration timeout = Duration.ofMinutes(10);

        assertTrue(IdleShutdownMonitor.idleLongEnough(start.plus(timeout), start, false, start, timeout));
        assertTrue(!IdleShutdownMonitor.idleLongEnough(start.plus(timeout).minusMillis(1), start, false, start, timeout));
        assertTrue(!IdleShutdownMonitor.idleLongEnough(start.plus(Duration.ofDays(1)), start, true, start, timeout));
        assertTrue(!IdleShutdownMonitor.idleLongEnough(start.plus(Duration.ofMinutes(15)), start, false,
                start.plus(Duration.ofMinutes(6)), timeout), "counted from the end of the last operation");
    }
}
