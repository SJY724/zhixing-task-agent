package com.agent.springaitaskagent.common;

import com.agent.springaitaskagent.harness.exception.TaskExecuteException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/** Retry transient failures only. max-attempts includes the initial call: default 4 = 1 + 3 retries. */
@Component
public class RetryExecutor {
    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);
    private final TaskLogService taskLogService;
    @Value("${task-agent.retry.max-attempts:4}") private int maxAttempts = 4;
    @Value("${task-agent.retry.initial-backoff-ms:500}") private long initialBackoffMs = 500;
    @Value("${task-agent.retry.max-backoff-ms:5000}") private long maxBackoffMs = 5000;
    public RetryExecutor(TaskLogService taskLogService) { this.taskLogService = taskLogService; }

    public <T> T executeWithRetry(String taskId, RetryableSupplier<T> action, String actionName) {
        return executeWithRetry(taskId, action, actionName, new RetryListener() { });
    }
    public <T> T executeWithRetry(String taskId, RetryableSupplier<T> action, String actionName, RetryListener listener) {
        int total = Math.max(1, maxAttempts);
        long backoff = Math.max(0, initialBackoffMs);
        for (int attempt = 1; attempt <= total; attempt++) {
            if (Thread.currentThread().isInterrupted()) { throw new CancellationException("任务已中断"); }
            listener.beforeAttempt(attempt);
            try {
                return action.get();
            } catch (Exception failure) {
                if (failure instanceof CancellationException) { throw (CancellationException) failure; }
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("任务已中断");
                }
                if (!isTransient(failure)) {
                    if (failure instanceof RuntimeException runtime) { throw runtime; }
                    throw new TaskExecuteException("OPERATION_FAILED", "操作 " + actionName + " 失败：" + failure.getMessage(), failure);
                }
                if (attempt == total) {
                    throw new TaskExecuteException("RETRY_EXHAUSTED", "操作 " + actionName + " 共尝试 " + total + " 次（重试 " + (total - 1) + " 次）仍失败", failure);
                }
                listener.retrying(attempt, failure, backoff);
                taskLogService.record(taskId, "RETRY", actionName + " 第 " + attempt + " 次失败，" + backoff + "ms 后重试");
                log.warn("Transient failure in {} attempt {}/{}; retry in {}ms", actionName, attempt, total, backoff);
                sleep(backoff);
                backoff = Math.min(Math.max(0, maxBackoffMs), backoff > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : backoff * 2);
            }
        }
        throw new IllegalStateException("unreachable");
    }
    public static boolean isTransient(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof IllegalArgumentException || current instanceof SecurityException
                    || current instanceof javax.net.ssl.SSLHandshakeException) { return false; }
            if (current instanceof RestClientResponseException response) {
                int code = response.getStatusCode().value();
                return code == 408 || code == 429 || code >= 500;
            }
            if (current.getClass().getSimpleName().equals("NonTransientAiException")) { return false; }
            if (current.getClass().getSimpleName().equals("TransientAiException")) { return true; }
            if (current instanceof ConnectException || current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException || current instanceof TimeoutException
                    || current instanceof SocketException) { return true; }
        }
        return false;
    }
    @FunctionalInterface public interface RetryableSupplier<T> { T get() throws Exception; }
    public interface RetryListener {
        default void beforeAttempt(int attempt) { }
        default void retrying(int failedAttempt, Exception failure, long delayMillis) { }
    }
    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("重试等待已中断");
        }
    }
}
