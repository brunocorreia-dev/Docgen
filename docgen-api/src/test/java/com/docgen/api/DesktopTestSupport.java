package com.docgen.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * An API context in desktop mode, as the launcher starts it: no test profile
 * (so no extra password users), in-memory storage, and the launcher channel
 * replaced by {@link FakeLauncherSide}.
 */
@SpringBootTest(properties = {
        "docgen.desktop.enabled=true",
        "docgen.api.username=desktop-user",
        "docgen.storage.in-memory=true",
        "docgen.remote.enabled=false"
})
@AutoConfigureMockMvc
@Import({DesktopTestSupport.FakeLauncher.class, ApiTestSupport.FakeProviders.class})
abstract class DesktopTestSupport {
    static final int LOCAL_PORT = 48_123;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    FakeLauncherSide launcher;

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeLauncher {
        @Bean
        FakeLauncherSide fakeLauncherSide() {
            return new FakeLauncherSide();
        }

        @Bean
        @Primary
        DesktopLauncherLink.Streams testDesktopStreams(FakeLauncherSide side) {
            return side.streams();
        }

        @Bean
        @Primary
        DesktopLauncherLink.Shutdown testDesktopShutdown(FakeLauncherSide side) {
            return side::recordShutdown;
        }
    }

    /** MockMvc requests report port 80 unless told otherwise; the proof binds the local port. */
    static RequestPostProcessor localPort(int port) {
        return request -> {
            request.setLocalPort(port);
            return request;
        };
    }
}
