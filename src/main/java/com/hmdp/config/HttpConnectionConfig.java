package com.hmdp.config;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Boot 2.3 does not expose these connector settings through ServerProperties. */
@Configuration
public class HttpConnectionConfig {
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> upstreamKeepAlive(
            @Value("${app.http.keep-alive-requests:10000}") int requests,
            @Value("${app.http.keep-alive-timeout-ms:65000}") int timeoutMs) {
        if (requests < 1 || timeoutMs < 1) throw new IllegalArgumentException("Invalid HTTP keep-alive limits");
        return factory -> factory.addConnectorCustomizers(connector -> {
            if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol) {
                AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>) connector.getProtocolHandler();
                protocol.setMaxKeepAliveRequests(requests);
                // Slightly longer than Nginx's 60s upstream idle lifetime.
                protocol.setKeepAliveTimeout(timeoutMs);
            }
        });
    }
}
