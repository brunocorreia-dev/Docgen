package com.docgen.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the static launch page at {@code /launch}, the address the desktop launcher opens. */
@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/launch").setViewName("forward:/launch.html");
    }
}
