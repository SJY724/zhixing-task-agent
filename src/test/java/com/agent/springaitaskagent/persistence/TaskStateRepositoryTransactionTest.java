package com.agent.springaitaskagent.persistence;

import com.agent.springaitaskagent.harness.TaskState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import java.util.UUID;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

class TaskStateRepositoryTransactionTest {
    @Test void snapshotUpdateRollsBackIfEventJournalWriteFails() {
        try (var context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            TaskStateRepository repository = context.getBean(TaskStateRepository.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertTrue(AopUtils.isAopProxy(repository));
            TaskState task = new TaskState("task-transaction", "question", null, null);
            task.addEvent("SUBMIT", "created"); repository.save(task);
            jdbc.execute("DROP TABLE agent_task_events");
            task.setStatus(TaskState.Status.RUNNING); task.addEvent("RUNNING", "started");
            assertThrows(org.springframework.dao.DataAccessException.class, () -> repository.save(task));
            TaskState restored = repository.findAll().get(0);
            assertEquals(TaskState.Status.PENDING, restored.getStatus());
            assertEquals(1, restored.getEvents().size());
        }
    }
    @Test void permanentDeleteRollsBackEventRemovalWhenLogDeletionFails() {
        try (var context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            TaskStateRepository repository = context.getBean(TaskStateRepository.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            var logs = com.agent.springaitaskagent.persistence.PersistenceTestSupport.logs(100, jdbc);
            TaskState task = new TaskState("task-purge-rollback", "question", null, null);
            task.setStatus(TaskState.Status.SUCCEEDED);
            task.addEvent("DONE", "original event");
            repository.save(task); logs.record(task.getTaskId(), "DONE", "original log");
            repository.markDeleted(task.getTaskId());
            jdbc.execute("DROP TABLE agent_logs");
            assertThrows(org.springframework.dao.DataAccessException.class, () -> repository.permanentlyDelete(task.getTaskId()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_events WHERE task_id=?", Integer.class, task.getTaskId()));
            assertNotNull(repository.findDeleted(task.getTaskId()));
            assertTrue(repository.findAll().isEmpty());
        }
    }
    @Configuration
    @EnableTransactionManagement
    static class TestConfiguration {
        @Bean DataSource dataSource() { return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean TaskStateRepository taskStateRepository(JdbcTemplate jdbc, ObjectMapper mapper) { return com.agent.springaitaskagent.persistence.PersistenceTestSupport.repository(jdbc, mapper); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
    }
}
