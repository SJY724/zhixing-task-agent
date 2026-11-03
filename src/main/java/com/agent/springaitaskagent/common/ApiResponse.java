package com.agent.springaitaskagent.common;

import org.slf4j.MDC;

import java.time.LocalDateTime;

/**
 * 统一 API 响应结构：code / message / data / traceId / timestamp。
 */
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;
    private LocalDateTime timestamp;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.code = 200;
        resp.message = "success";
        resp.data = data;
        resp.traceId = MDC.get("traceId");
        resp.timestamp = LocalDateTime.now();
        return resp;
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.code = code;
        resp.message = message;
        resp.traceId = MDC.get("traceId");
        resp.timestamp = LocalDateTime.now();
        return resp;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public String getTraceId() {
        return traceId;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}
