package com.agent.springaitaskagent.config;

import com.agent.springaitaskagent.common.RetryExecutor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class HttpClientConfigurationTest {
    @Test void configuredClientTimesOutSlowLocalResponseInsteadOfBlockingWorkerIndefinitely() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handler = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "slow-test-server"); thread.setDaemon(true); return thread;
        });
        server.setExecutor(handler);
        server.createContext("/slow", exchange -> {
            try { Thread.sleep(5000); exchange.sendResponseHeaders(204, -1); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            RestClient.Builder builder = RestClient.builder();
            new HttpClientConfiguration().modelHttpTimeouts(1, 1).customize(builder);
            RestClient client = builder.build();
            assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
                ResourceAccessException failure = assertThrows(ResourceAccessException.class,
                        () -> client.get().uri("http://127.0.0.1:" + server.getAddress().getPort() + "/slow").retrieve().toBodilessEntity());
                assertTrue(RetryExecutor.isTransient(failure));
            });
        } finally { server.stop(0); handler.shutdownNow(); }
    }
}
