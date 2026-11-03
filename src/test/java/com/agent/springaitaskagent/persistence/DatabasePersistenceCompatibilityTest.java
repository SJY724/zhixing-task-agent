package com.agent.springaitaskagent.persistence;

import com.agent.springaitaskagent.common.TaskLogService;
import com.agent.springaitaskagent.harness.TaskState;
import com.agent.springaitaskagent.rag.KnowledgeService;
import com.agent.springaitaskagent.rag.VectorStoreService;
import com.agent.springaitaskagent.skill.DocumentParseSkill;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Runs against real H2 on every build. Set TASK_AGENT_TEST_MYSQL_URL/USERNAME/PASSWORD
 * to additionally run the same behavioral checks against an isolated, empty MySQL test schema.
 * Cloud clients and model API keys are never involved.
 */
class DatabasePersistenceCompatibilityTest {
    @TempDir Path temporary;

    static Stream<Arguments> databases() {
        Arguments h2 = Arguments.of("H2", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        String mysqlUrl = System.getenv("TASK_AGENT_TEST_MYSQL_URL");
        if (mysqlUrl == null || mysqlUrl.isBlank()) return Stream.of(h2);
        return Stream.of(h2, Arguments.of("MySQL", mysqlUrl,
                System.getenv().getOrDefault("TASK_AGENT_TEST_MYSQL_USERNAME", "zhixing"),
                System.getenv().getOrDefault("TASK_AGENT_TEST_MYSQL_PASSWORD", "")));
    }

    @ParameterizedTest(name = "{0}: task updates preserve Unicode, large snapshots, and unique events")
    @MethodSource("databases")
    void taskSnapshotsAndEventsRemainDurable(String name, String url, String user, String password) {
        JdbcTemplate jdbc = jdbc(url, user, password);
        assertEquals(name, DatabaseDialect.detect(jdbc.getDataSource()).displayName());
        TaskStateRepository repository = com.agent.springaitaskagent.persistence.PersistenceTestSupport.repository(jdbc, mapper());
        String id = "db-test-" + UUID.randomUUID();
        try {
            String content = "中文政策与表情😀".repeat(12_000);
            TaskState task = new TaskState(id, "一线城市住宿是多少？😀", null, content);
            task.addEvent("SUBMIT", "创建任务😀");
            repository.save(task);
            task.setStatus(TaskState.Status.SUCCEEDED);
            task.setAnswer("依据制度，550元/晚。😀");
            task.addEvent("GENERATE", "生成完成");
            repository.save(task);
            repository.save(task);

            TaskStateRepository restarted = com.agent.springaitaskagent.persistence.PersistenceTestSupport.repository(jdbc, mapper());
            TaskState restored = restarted.findAll().stream().filter(value -> value.getTaskId().equals(id)).findFirst().orElseThrow();
            assertEquals(content, restored.getTextContent());
            assertEquals(task.getAnswer(), restored.getAnswer());
            assertEquals(TaskState.Status.SUCCEEDED, restored.getStatus());
            assertEquals(2, restored.getEvents().size());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_events WHERE task_id=?", Integer.class, id));
            assertEquals(task.getTraceId(), jdbc.queryForObject(
                    "SELECT trace_id FROM agent_task_events WHERE task_id=? AND event_sequence=2", String.class, id));
        } finally { removeTask(jdbc, id); }
    }

    @ParameterizedTest(name = "{0}: failed event insert rolls back the snapshot")
    @MethodSource("databases")
    void databaseTransactionRollsBackSnapshotAndJournalTogether(String name, String url, String user, String password) {
        JdbcTemplate jdbc = jdbc(url, user, password);
        TaskStateRepository repository = com.agent.springaitaskagent.persistence.PersistenceTestSupport.repository(jdbc, mapper());
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        String id = "db-test-" + UUID.randomUUID();
        try {
            TaskState task = new TaskState(id, "transaction rollback", null, null);
            task.addEvent("SUBMIT", "created");
            transaction.executeWithoutResult(status -> repository.save(task));
            task.setStatus(TaskState.Status.RUNNING);
            task.addEvent("X".repeat(100), "invalid stage exceeds schema limit");
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> transaction.executeWithoutResult(status -> repository.save(task)));
            TaskState restored = repository.findAll().stream().filter(value -> value.getTaskId().equals(id)).findFirst().orElseThrow();
            assertEquals(TaskState.Status.PENDING, restored.getStatus());
            assertEquals(1, restored.getEvents().size());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_events WHERE task_id=?", Integer.class, id));
        } finally { removeTask(jdbc, id); }
    }

    @ParameterizedTest(name = "{0}: audit identity and credential redaction survive reconstruction")
    @MethodSource("databases")
    void auditLogsPersistWithoutCredentials(String name, String url, String user, String password) {
        JdbcTemplate jdbc = jdbc(url, user, password);
        String id = "db-test-" + UUID.randomUUID();
        try {
            TaskLogService first = com.agent.springaitaskagent.persistence.PersistenceTestSupport.logs(100, jdbc);
            first.record(id, "TEST", "中文😀 sk-example-not-real");
            first.record(id, "TEST", "second");
            TaskLogService restarted = com.agent.springaitaskagent.persistence.PersistenceTestSupport.logs(100, jdbc);
            List<TaskLogService.LogRecord> records = restarted.list().stream().filter(value -> id.equals(value.taskId())).toList();
            assertEquals(2, records.size());
            assertEquals("中文😀 [密钥已隐藏]", records.get(0).message());
            assertEquals("second", records.get(1).message());
        } finally { jdbc.update("DELETE FROM agent_logs WHERE task_id=?", id); }
    }

    @ParameterizedTest(name = "{0}: document catalog and index settings can insert, update, restore, delete")
    @MethodSource("databases")
    void knowledgeCatalogAndSettingsWorkWithActualDialect(String name, String url, String user, String password) throws Exception {
        JdbcTemplate jdbc = jdbc(url, user, password);
        DocumentParseSkill parser = mock(DocumentParseSkill.class);
        when(parser.parse(isNull(), anyString())).thenAnswer(call -> List.of(new Document(call.getArgument(1, String.class))));
        VectorStoreService vectors = mock(VectorStoreService.class);
        Set<String> ids = new HashSet<>();
        when(vectors.getEmbeddingIdentity()).thenReturn("database-test-embedding");
        when(vectors.getVectorIds()).thenAnswer(call -> new HashSet<>(ids));
        doAnswer(call -> { List<Document> documents = call.getArgument(0); documents.forEach(document -> ids.add(document.getId())); return null; })
                .when(vectors).addDocuments(anyList());
        doAnswer(call -> { List<String> removed = call.getArgument(0); ids.removeAll(removed); return null; }).when(vectors).delete(anyList());
        doAnswer(call -> { Files.writeString(call.getArgument(0, Path.class), String.join("\n", ids)); return null; }).when(vectors).save(any(Path.class));
        Path snapshot = temporary.resolve(UUID.randomUUID() + ".json");
        KnowledgeService service = com.agent.springaitaskagent.persistence.PersistenceTestSupport.knowledge(jdbc, mapper(), parser, vectors, snapshot.toString());
        assertEquals(name, service.storageName());
        assertTrue(service.list().isEmpty(), "MySQL integration URL must use a separate, empty test schema");
        String content = "测试知识😀，住宿550元/晚。".repeat(6000);
        KnowledgeService.KnowledgeDocument stored = service.ingest("数据库测试😀.md", content);
        try {
            assertEquals("READY", stored.status());
            assertTrue(service.isReady());
            assertEquals(content, jdbc.queryForObject("SELECT content FROM knowledge_documents WHERE id=?", String.class, stored.id()));
            assertEquals("true", jdbc.queryForObject("SELECT setting_value FROM knowledge_settings WHERE setting_key='seeded'", String.class));
            assertNotEquals("DIRTY", jdbc.queryForObject("SELECT setting_value FROM knowledge_settings WHERE setting_key='manifest'", String.class));
            KnowledgeService reopened = com.agent.springaitaskagent.persistence.PersistenceTestSupport.knowledge(jdbc, mapper(), parser, vectors, snapshot.toString());
            assertEquals(stored.id(), reopened.require(stored.id()).id());
            service.delete(stored.id());
            assertTrue(service.list().isEmpty());
            assertFalse(service.isReady());
        } finally { jdbc.update("DELETE FROM knowledge_documents WHERE id=?", stored.id()); }
    }

    @ParameterizedTest(name = "{0}: task deletion and recovery preserve snapshots, events and logs")
    @MethodSource("databases")
    void deletionMarkerSurvivesRecreationWithoutLosingHistory(String name, String url, String user, String password) {
        JdbcTemplate jdbc = jdbc(url, user, password);
        TaskStateRepository repository = com.agent.springaitaskagent.persistence.PersistenceTestSupport.repository(jdbc, mapper());
        TaskLogService logs = com.agent.springaitaskagent.persistence.PersistenceTestSupport.logs(100, jdbc);
        String id = "db-test-" + UUID.randomUUID();
        try {
            TaskState task = new TaskState(id, "可恢复删除😀", null, null);
            task.setStatus(TaskState.Status.SUCCEEDED);
            task.setAnswer("保留的原始回答😀");
            task.addEvent("DONE", "completed");
            repository.save(task);
            logs.record(id, "DONE", "original log");
            repository.markDeleted(id);
            TaskStateRepository reopened = com.agent.springaitaskagent.persistence.PersistenceTestSupport.repository(jdbc, mapper());
            assertTrue(reopened.findAll().stream().noneMatch(item -> item.getTaskId().equals(id)));
            TaskState deleted = reopened.findDeleted(id);
            assertNotNull(deleted);
            assertEquals(task.getAnswer(), deleted.getAnswer());
            assertEquals(task.getEvents(), deleted.getEvents());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_logs WHERE task_id=?", Integer.class, id));
            // A previously issued callback saving its terminal snapshot cannot undo a deletion.
            repository.save(task);
            assertTrue(reopened.findAll().stream().noneMatch(item -> item.getTaskId().equals(id)));
            assertTrue(reopened.restoreDeleted(id));
            assertFalse(reopened.restoreDeleted(id));
            assertNotNull(reopened.findAll().stream().filter(item -> item.getTaskId().equals(id)).findFirst().orElse(null));
        } finally {
            jdbc.update("DELETE FROM agent_deleted_tasks WHERE task_id=?", id);
            jdbc.update("DELETE FROM agent_logs WHERE task_id=?", id);
            removeTask(jdbc, id);
        }
    }

    @ParameterizedTest(name = "{0}: permanent deletion only purges selected trash history")
    @MethodSource("databases")
    void permanentDeletionRemovesAllLinkedRowsButPreservesOtherTasks(String name, String url, String user, String password) {
        JdbcTemplate jdbc = jdbc(url, user, password);
        TaskStateRepository repository = com.agent.springaitaskagent.persistence.PersistenceTestSupport.repository(jdbc, mapper());
        TaskLogService logs = com.agent.springaitaskagent.persistence.PersistenceTestSupport.logs(100, jdbc);
        String id = "db-test-" + UUID.randomUUID();
        String otherId = "db-test-" + UUID.randomUUID();
        try {
            for (String taskId : List.of(id, otherId)) {
                TaskState task = new TaskState(taskId, "history", null, null);
                task.setStatus(TaskState.Status.SUCCEEDED); task.setAnswer("original answer");
                task.addEvent("DONE", "original event");
                repository.save(task); logs.record(taskId, "DONE", "original log");
            }
            assertFalse(repository.permanentlyDelete(otherId), "normal-list tasks must not be purged");
            repository.markDeleted(id);
            assertTrue(repository.permanentlyDelete(id));
            for (String table : List.of("agent_tasks", "agent_task_events", "agent_logs", "agent_deleted_tasks")) {
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id=?", Integer.class, id));
            }
            assertNull(com.agent.springaitaskagent.persistence.PersistenceTestSupport.repository(jdbc, mapper()).findDeleted(id));
            assertFalse(repository.restoreDeleted(id));
            assertFalse(repository.permanentlyDelete(id));
            assertEquals("original answer", repository.findAll().stream().filter(task -> task.getTaskId().equals(otherId)).findFirst().orElseThrow().getAnswer());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_logs WHERE task_id=?", Integer.class, otherId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_events WHERE task_id=?", Integer.class, otherId));
        } finally {
            for (String taskId : List.of(id, otherId)) {
                jdbc.update("DELETE FROM agent_deleted_tasks WHERE task_id=?", taskId);
                jdbc.update("DELETE FROM agent_logs WHERE task_id=?", taskId);
                removeTask(jdbc, taskId);
            }
        }
    }

    private static JdbcTemplate jdbc(String url, String user, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    }
    private static ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
    private static void removeTask(JdbcTemplate jdbc, String id) {
        jdbc.update("DELETE FROM agent_task_events WHERE task_id=?", id);
        jdbc.update("DELETE FROM agent_tasks WHERE task_id=?", id);
    }
}
