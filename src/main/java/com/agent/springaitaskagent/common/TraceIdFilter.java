package com.agent.springaitaskagent.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.Map;

/**
 * 请求关联标识；任务创建后另行传播 traceId 与真实 taskId 到工作线程。
 * 并在响应头 X-Trace-Id 回传，便于跨调用追踪。
 */
@Component
@Order(1)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || !traceId.matches("[A-Za-z0-9_-]{8,64}")) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.put("traceId", traceId);
        String path = request.getRequestURI();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("/api/agent/tasks/(task-[a-f0-9]+)(?:/.*)?").matcher(path);
        if (matcher.matches()) { MDC.put("taskId", matcher.group(1)); }
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) { MDC.clear(); } else { MDC.setContextMap(previous); }
        }
    }
}
