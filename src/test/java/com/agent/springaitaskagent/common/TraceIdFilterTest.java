package com.agent.springaitaskagent.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class TraceIdFilterTest {
    @AfterEach void clear() { MDC.clear(); }
    @Test void separatesRealTaskIdFromRequestTraceAndRestoresThreadContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/tasks/task-abcd1234");
        request.addHeader("X-Trace-Id", "request-trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put("previous", "value");
        new TraceIdFilter().doFilter(request, response, (req, res) -> {
            assertEquals("request-trace-123", MDC.get("traceId")); assertEquals("task-abcd1234", MDC.get("taskId"));
        });
        assertEquals("request-trace-123", response.getHeader("X-Trace-Id"));
        assertNull(MDC.get("taskId")); assertNull(MDC.get("traceId")); assertEquals("value", MDC.get("previous"));
    }
    @Test void invalidTraceHeaderIsReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.addHeader("X-Trace-Id", "bad trace"); MockHttpServletResponse response = new MockHttpServletResponse();
        new TraceIdFilter().doFilter(request, response, (req, res) -> assertTrue(MDC.get("traceId").matches("[a-f0-9]{16}")));
        assertNotEquals("bad trace", response.getHeader("X-Trace-Id"));
    }
}
