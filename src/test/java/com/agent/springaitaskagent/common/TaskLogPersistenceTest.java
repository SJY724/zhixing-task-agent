package com.agent.springaitaskagent.common;

import com.agent.springaitaskagent.aspect.TaskLogAspect;
import com.agent.springaitaskagent.skill.DocumentParseSkill;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskLogPersistenceTest {
    @AfterEach void clear() { MDC.clear(); }
    @Test void persistedLogsSurviveServiceRecreationAndRedactSecrets() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        TaskLogService first = com.agent.springaitaskagent.persistence.PersistenceTestSupport.logs(100, jdbc);
        MDC.put("traceId", "trace-12345");
        first.record("task-123", "TEST", "Authorization: Bearer secret-value key=sk-test-secret");
        TaskLogService second = com.agent.springaitaskagent.persistence.PersistenceTestSupport.logs(100, jdbc);
        assertEquals(1, second.list().size());
        assertEquals("task-123", second.list().get(0).taskId());
        assertEquals("trace-12345", second.list().get(0).traceId());
        assertFalse(second.list().get(0).message().contains("secret-value"));
        assertFalse(second.list().get(0).message().contains("sk-test-secret"));
    }
    @Test void skillAspectUsesRealTaskAndTraceIdentifiers() throws Throwable {
        TaskLogService logs = new TaskLogService(100);
        ProceedingJoinPoint join = mock(ProceedingJoinPoint.class); Signature signature = mock(Signature.class);
        when(join.getSignature()).thenReturn(signature); when(signature.getDeclaringType()).thenReturn(DocumentParseSkill.class);
        when(signature.getName()).thenReturn("parse"); when(join.proceed()).thenReturn("result");
        MDC.put("taskId", "task-real-123"); MDC.put("traceId", "trace-abc-123");
        assertEquals("result", new TaskLogAspect(logs).around(join));
        assertEquals(2, logs.list().size());
        assertTrue(logs.list().stream().allMatch(row -> row.taskId().equals("task-real-123") && row.traceId().equals("trace-abc-123")));
    }
}
