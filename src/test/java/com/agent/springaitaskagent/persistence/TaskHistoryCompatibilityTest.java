package com.agent.springaitaskagent.persistence;

import com.agent.springaitaskagent.harness.TaskState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TaskHistoryCompatibilityTest {
    @Test void storedSnapshotsAndTrashPreserveTaskIdentityAndApprovalDeadline() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        // Seed persisted fixtures independently of the mapper under test.
        jdbc.execute("CREATE TABLE agent_tasks (task_id VARCHAR(64) PRIMARY KEY, snapshot CLOB NOT NULL)");
        jdbc.execute("CREATE TABLE agent_deleted_tasks (task_id VARCHAR(64) PRIMARY KEY, deleted_at VARCHAR(40) NOT NULL)");
        TaskState waiting = new TaskState("history-waiting", "持久化审批任务😀", null, null);
        waiting.setStatus(TaskState.Status.AWAITING_CONFIRMATION);
        waiting.setApprovalExpiresAt(LocalDateTime.now().plusMinutes(7));
        waiting.addEvent("APPROVAL", "审批截止时间");
        TaskState trash = new TaskState("history-trash", "持久化回收站任务", null, null);
        trash.setStatus(TaskState.Status.SUCCEEDED); trash.setAnswer("保存的回答😀");
        jdbc.update("INSERT INTO agent_tasks VALUES (?,?)", waiting.getTaskId(), json.writeValueAsString(waiting));
        jdbc.update("INSERT INTO agent_tasks VALUES (?,?)", trash.getTaskId(), json.writeValueAsString(trash));
        jdbc.update("INSERT INTO agent_deleted_tasks VALUES (?,?)", trash.getTaskId(), LocalDateTime.now().toString());

        TaskStateRepository repository = PersistenceTestSupport.repository(jdbc, json);
        assertEquals(1, repository.findAll().size());
        TaskState loaded = repository.findAll().get(0);
        assertEquals(waiting.getTraceId(), loaded.getTraceId());
        assertEquals(waiting.getApprovalExpiresAt(), loaded.getApprovalExpiresAt());
        assertEquals(waiting.getEvents(), loaded.getEvents());
        assertEquals("保存的回答😀", repository.findDeleted(trash.getTaskId()).getAnswer());
        repository.save(loaded); repository.save(loaded);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_events WHERE task_id=?", Integer.class, waiting.getTaskId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM agent_tasks", Integer.class));
    }
}
