package com.agent.springaitaskagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;

/** Explicit finite timeouts for the synchronous chat/embedding HTTP client used by Spring AI M4. */
@Configuration
public class HttpClientConfiguration {
    @Bean
    public RestClientCustomizer modelHttpTimeouts(
            @Value("${task-agent.http.connect-timeout-seconds:10}") int connectSeconds,
            @Value("${task-agent.http.read-timeout-seconds:90}") int readSeconds) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, connectSeconds))).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, readSeconds)));
        return builder -> builder.requestFactory(factory);
    }
}
