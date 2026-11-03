package com.agent.springaitaskagent.common;

import com.agent.springaitaskagent.harness.exception.TaskExecuteException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RetryExecutorTest {
    @Test void defaultMeansThreeRetriesAfterInitialCall() {
        RetryExecutor executor = executor(); AtomicInteger count = new AtomicInteger();
        TaskExecuteException exception = assertThrows(TaskExecuteException.class,
                () -> executor.executeWithRetry("task-test", () -> { count.incrementAndGet(); throw new SocketTimeoutException("timeout"); }, "network"));
        assertEquals(4, count.get()); assertEquals("RETRY_EXHAUSTED", exception.getErrorCode());
    }
    @Test void authAndValidationFailuresAreNotRetried() {
        RetryExecutor executor = executor(); AtomicInteger count = new AtomicInteger();
        assertThrows(HttpClientErrorException.class, () -> executor.executeWithRetry("task-test", () -> {
            count.incrementAndGet(); throw new HttpClientErrorException(HttpStatus.UNAUTHORIZED);
        }, "auth"));
        assertEquals(1, count.get()); count.set(0);
        assertThrows(IllegalArgumentException.class, () -> executor.executeWithRetry("task-test", () -> {
            count.incrementAndGet(); throw new IllegalArgumentException("invalid input");
        }, "validation")); assertEquals(1, count.get());
    }
    @Test void successfulRetryReportsTransitionsAndHonoursTotalAttemptOverride() {
        RetryExecutor executor = executor(); ReflectionTestUtils.setField(executor, "maxAttempts", 2);
        AtomicInteger calls = new AtomicInteger(); AtomicInteger retries = new AtomicInteger(); AtomicInteger attempts = new AtomicInteger();
        String result = executor.executeWithRetry("task-test", () -> {
            if (calls.incrementAndGet() == 1) { throw new SocketTimeoutException("transient"); } return "ok";
        }, "network", new RetryExecutor.RetryListener() {
            public void beforeAttempt(int attempt) { attempts.incrementAndGet(); }
            public void retrying(int attempt, Exception exception, long millis) { retries.incrementAndGet(); }
        });
        assertEquals("ok", result); assertEquals(2, calls.get()); assertEquals(2, attempts.get()); assertEquals(1, retries.get());
    }
    private RetryExecutor executor() {
        RetryExecutor result = new RetryExecutor(new TaskLogService(100));
        ReflectionTestUtils.setField(result, "initialBackoffMs", 0L); return result;
    }
}
