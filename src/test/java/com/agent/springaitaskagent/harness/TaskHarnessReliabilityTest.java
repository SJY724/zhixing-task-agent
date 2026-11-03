package com.agent.springaitaskagent.harness;

import com.agent.springaitaskagent.common.RetryExecutor;
import com.agent.springaitaskagent.common.TaskLogService;
import com.agent.springaitaskagent.harness.exception.TaskExecuteException;
import com.agent.springaitaskagent.persistence.TaskStateRepository;
import com.agent.springaitaskagent.rag.VectorStoreService;
import com.agent.springaitaskagent.skill.DocumentParseSkill;
import com.agent.springaitaskagent.skill.InformationRetrieveSkill;
import com.agent.springaitaskagent.skill.ResultGenerateSkill;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskHarnessReliabilityTest {
    private final List<TaskHarness> harnesses = new ArrayList<>();
    @AfterEach void close() { harnesses.forEach(TaskHarness::shutdown); MDC.clear(); }

    @Test void waitingApprovalsDoNotOccupyWorkerAndSnapshotsAreDefensive() throws Exception {
        Fixture fixture = fixture(true);
        String first = fixture.harness.submit("first", null, null).getTaskId();
        await(fixture.harness, first, state -> state.getStatus() == TaskState.Status.AWAITING_CONFIRMATION);
        String second = fixture.harness.submit("second", null, null).getTaskId();
        TaskState awaiting = await(fixture.harness, second, state -> state.getStatus() == TaskState.Status.AWAITING_CONFIRMATION);
        assertNotNull(awaiting.getApprovalExpiresAt());
        awaiting.setStatus(TaskState.Status.SUCCEEDED);
        assertEquals(TaskState.Status.AWAITING_CONFIRMATION, fixture.harness.requireTask(second).getStatus());
        verify(fixture.generate, never()).generate(anyString(), anyString());
    }

    @Test void concurrentApprovalClaimsOnlyOnce() throws Exception {
        Fixture fixture = fixture(true);
        CountDownLatch generation = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(fixture.generate.generate(anyString(), anyString())).thenAnswer(invocation -> {
            generation.countDown(); release.await(2, TimeUnit.SECONDS); return "approved answer";
        });
        String id = fixture.harness.submit("approval", null, null).getTaskId();
        await(fixture.harness, id, state -> state.getStatus() == TaskState.Status.AWAITING_CONFIRMATION);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        var callers = Executors.newFixedThreadPool(2);
        try {
            var call = (java.util.concurrent.Callable<Void>) () -> {
                barrier.await();
                try { fixture.harness.confirm(id, true, "reviewed"); accepted.incrementAndGet(); }
                catch (TaskExecuteException e) { assertEquals("CONFIRM_NOT_ALLOWED", e.getErrorCode()); rejected.incrementAndGet(); }
                return null;
            };
            var a = callers.submit(call); var b = callers.submit(call);
            a.get(3, TimeUnit.SECONDS); b.get(3, TimeUnit.SECONDS);
            assertTrue(generation.await(2, TimeUnit.SECONDS));
            assertEquals(1, accepted.get()); assertEquals(1, rejected.get());
        } finally { release.countDown(); callers.shutdownNow(); }
        await(fixture.harness, id, state -> state.getStatus() == TaskState.Status.SUCCEEDED);
        verify(fixture.generate, times(1)).generate(anyString(), anyString());
    }

    @Test void rejectNeverCallsGenerationAndRetryCreatesLinkedNewTask() throws Exception {
        Fixture fixture = fixture(true);
        String id = fixture.harness.submit("reject", null, null).getTaskId();
        await(fixture.harness, id, state -> state.getStatus() == TaskState.Status.AWAITING_CONFIRMATION);
        fixture.harness.confirm(id, false, "evidence insufficient");
        assertEquals(TaskState.Status.CANCELLED, fixture.harness.requireTask(id).getStatus());
        assertNull(fixture.harness.requireTask(id).getAnswer());
        verify(fixture.generate, never()).generate(anyString(), anyString());
        TaskState retry = fixture.harness.retry(id);
        assertNotEquals(id, retry.getTaskId()); assertEquals(id, retry.getParentTaskId());
        assertEquals(TaskState.Status.CANCELLED, fixture.harness.requireTask(id).getStatus());
    }

    @Test void cancelledTaskCannotBeOverwrittenByLateModelResult() throws Exception {
        Fixture fixture = fixture(true);
        CountDownLatch running = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        when(fixture.generate.generate(anyString(), anyString())).thenAnswer(invocation -> {
            running.countDown();
            boolean interrupted = false;
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) { Thread.currentThread().interrupt(); }
            returned.countDown();
            return "late result";
        });
        String id = fixture.harness.submit("cancel", null, null).getTaskId();
        await(fixture.harness, id, state -> state.getStatus() == TaskState.Status.AWAITING_CONFIRMATION);
        fixture.harness.confirm(id, true, "ok"); assertTrue(running.await(2, TimeUnit.SECONDS));
        fixture.harness.cancel(id); release.countDown();
        assertTrue(returned.await(2, TimeUnit.SECONDS));
        TaskState state = fixture.harness.requireTask(id);
        assertEquals(TaskState.Status.CANCELLED, state.getStatus()); assertNull(state.getAnswer());
    }

    @Test void restartRetainsHistoryAndPendingApprovalsButMarksInterruptedFailed() {
        Fixture fixture = fixture(false);
        LocalDateTime expiry = LocalDateTime.now().plusMinutes(5);
        TaskState waiting = seed("task-waiting", TaskState.Status.AWAITING_CONFIRMATION);
        waiting.setContext("saved evidence"); waiting.setApprovalExpiresAt(expiry); fixture.repository.save(waiting);
        TaskState running = seed("task-running", TaskState.Status.RUNNING); fixture.repository.save(running);
        TaskState completed = seed("task-completed", TaskState.Status.SUCCEEDED);
        completed.setAnswer("durable answer"); fixture.repository.save(completed);
        fixture.harness.initialize();
        assertEquals(TaskState.Status.AWAITING_CONFIRMATION, fixture.harness.requireTask("task-waiting").getStatus());
        assertEquals(expiry, fixture.harness.requireTask("task-waiting").getApprovalExpiresAt());
        assertEquals("saved evidence", fixture.harness.requireTask("task-waiting").getContext());
        assertEquals(TaskState.Status.FAILED, fixture.harness.requireTask("task-running").getStatus());
        assertTrue(fixture.harness.requireTask("task-running").getErrorMessage().contains("重启"));
        assertEquals("durable answer", fixture.harness.requireTask("task-completed").getAnswer());
        assertEquals(3, fixture.repository.findAll().size());
    }

    @Test void expiredApprovalIsCancelledOnRecovery() {
        Fixture fixture = fixture(false);
        TaskState expired = seed("task-expired", TaskState.Status.AWAITING_CONFIRMATION);
        expired.setApprovalExpiresAt(LocalDateTime.now().minusMinutes(1)); fixture.repository.save(expired);
        fixture.harness.initialize();
        assertEquals(TaskState.Status.CANCELLED, fixture.harness.requireTask("task-expired").getStatus());
        assertThrows(TaskExecuteException.class, () -> fixture.harness.confirm("task-expired", true, "late"));
        verify(fixture.generate, never()).generate(anyString(), anyString());
    }

    @Test void taskAndTraceIdentifiersPropagateIntoAsyncWorkAndPersistedEvents() throws Exception {
        Fixture fixture = fixture(true);
        AtomicReference<String> taskId = new AtomicReference<>(); AtomicReference<String> trace = new AtomicReference<>();
        when(fixture.retrieve.retrieve(anyString(), anyList())).thenAnswer(invocation -> {
            taskId.set(MDC.get("taskId")); trace.set(MDC.get("traceId")); return "evidence";
        });
        MDC.put("traceId", "interview-trace-123");
        String id = fixture.harness.submit("trace", null, null).getTaskId();
        MDC.clear();
        TaskState waiting = await(fixture.harness, id, state -> state.getStatus() == TaskState.Status.AWAITING_CONFIRMATION);
        assertEquals(id, taskId.get()); assertEquals("interview-trace-123", trace.get());
        assertEquals("interview-trace-123", waiting.getTraceId());
        assertTrue(waiting.getEvents().stream().allMatch(event -> event.taskId().equals(id) && event.traceId().equals(waiting.getTraceId())));
        assertEquals(waiting.getEvents().size(), fixture.jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_events WHERE task_id=?", Integer.class, id));
        assertTrue(fixture.logs.list().stream().filter(record -> id.equals(record.taskId())).allMatch(record -> waiting.getTraceId().equals(record.traceId())));
    }

    @Test void boundedQueueRejectsOverloadWithSavedFailure() throws Exception {
        Fixture fixture = fixture(true);
        CountDownLatch started = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        when(fixture.parse.parse(any(), any())).thenAnswer(invocation -> {
            started.countDown(); release.await(2, TimeUnit.SECONDS); return List.of(new Document("policy"));
        });
        fixture.harness.submit("first", null, null); assertTrue(started.await(2, TimeUnit.SECONDS));
        fixture.harness.submit("queued", null, null);
        try {
            TaskExecuteException failure = assertThrows(TaskExecuteException.class, () -> fixture.harness.submit("overload", null, null));
            assertEquals("TASK_CAPACITY_EXCEEDED", failure.getErrorCode());
            TaskState rejected = fixture.harness.listTasks().stream().filter(state -> state.getQuestion().equals("overload")).findFirst().orElseThrow();
            assertEquals(TaskState.Status.FAILED, rejected.getStatus());
            assertTrue(rejected.getErrorMessage().contains("队列已满"));
        } finally { release.countDown(); }
    }

    @Test void transientRetryIsPersistedBeforeRunningAgain() throws Exception {
        Fixture fixture = fixture(true);
        AtomicInteger calls = new AtomicInteger();
        when(fixture.retrieve.retrieve(anyString(), anyList())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) { throw new java.net.SocketTimeoutException("temporary"); }
            return "evidence";
        });
        String id = fixture.harness.submit("retry events", null, null).getTaskId();
        TaskState waiting = await(fixture.harness, id, state -> state.getStatus() == TaskState.Status.AWAITING_CONFIRMATION);
        assertEquals(2, calls.get());
        assertTrue(waiting.getEvents().stream().anyMatch(event -> event.message().contains("后重试")));
        assertTrue(waiting.getEvents().stream().anyMatch(event -> event.message().contains("第 2 次尝试")));
    }

    @Test void failedApprovalPersistenceDoesNotConsumeDecisionOrStrandTask() throws Exception {
        Fixture fixture = fixture(true);
        String id = fixture.harness.submit("durable approval", null, null).getTaskId();
        TaskState waiting = await(fixture.harness, id, state -> state.getStatus() == TaskState.Status.AWAITING_CONFIRMATION);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"))
                .when(fixture.repository).save(argThat(state -> state != null && "GENERATION_QUEUED".equals(state.getPhase())));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> fixture.harness.confirm(id, true, "approved"));
        TaskState unchanged = fixture.harness.requireTask(id);
        assertEquals(TaskState.Status.AWAITING_CONFIRMATION, unchanged.getStatus());
        assertEquals(waiting.getApprovalExpiresAt(), unchanged.getApprovalExpiresAt());
        assertNull(unchanged.getConfirmComment());
        verify(fixture.generate, never()).generate(anyString(), anyString());
    }

    @Test void selectedKnowledgeScopeIsUsedAndPreservedOnRetry() throws Exception {
        Fixture fixture = fixture(true);
        List<String> selected = List.of("doc-policy-123");
        when(fixture.retrieve.retrieveSelected("scoped question", selected)).thenReturn("selected evidence");
        String id = fixture.harness.submit("scoped question", null, null, selected).getTaskId();
        TaskState waiting = await(fixture.harness, id, state -> state.getStatus() == TaskState.Status.AWAITING_CONFIRMATION);
        assertEquals(selected, waiting.getDocumentIds()); assertEquals("selected evidence", waiting.getContext());
        verify(fixture.retrieve).retrieveSelected("scoped question", selected);
        verify(fixture.retrieve, never()).retrieve(anyString(), anyList());
        verify(fixture.parse, never()).parse(any(), any());
        fixture.harness.confirm(id, false, "reject");
        TaskState retry = fixture.harness.retry(id);
        assertEquals(selected, retry.getDocumentIds()); assertEquals(id, retry.getParentTaskId());
        assertEquals(selected, fixture.repository.findAll().stream().filter(state -> state.getTaskId().equals(id)).findFirst().orElseThrow().getDocumentIds());
    }

    @Test void pastedTextUsesTaskLocalScopeWithoutSelectedKnowledgeLookup() throws Exception {
        Fixture fixture = fixture(true);
        String id = fixture.harness.submit("local question", null, "local material", List.of("doc-not-used")).getTaskId();
        await(fixture.harness, id, state -> state.getStatus() == TaskState.Status.AWAITING_CONFIRMATION);
        verify(fixture.parse).parse(null, "local material");
        verify(fixture.retrieve).retrieve(eq("local question"), argThat(documents -> documents.size() == 1));
        verify(fixture.retrieve, never()).retrieveSelected(anyString(), anyList());
    }

    @Test void deletedHistoryStaysHiddenAfterRestartAndCanBeRestored() {
        Fixture original = fixture(false);
        TaskState history = seed("task-history", TaskState.Status.SUCCEEDED);
        history.setAnswer("550 元/晚 [policy]");
        original.repository.save(history);
        original.logs.record(history.getTaskId(), "TEST", "original audit log");
        original.harness.initialize();
        original.harness.delete(history.getTaskId());
        assertTrue(original.harness.listTasks().isEmpty());
        assertThrows(TaskExecuteException.class, () -> original.harness.requireTask(history.getTaskId()));
        assertEquals(1, original.harness.listDeletedTasks().size());
        assertEquals(1, original.logs.list().size());
        assertEquals(1, original.jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_events WHERE task_id=?", Integer.class, history.getTaskId()));
        original.harness.shutdown();
        TaskHarness restarted = new TaskHarness(original.parse, original.retrieve, original.generate,
                mock(VectorStoreService.class), new RetryExecutor(original.logs), original.logs, original.repository);
        harnesses.add(restarted);
        restarted.initialize();
        assertTrue(restarted.listTasks().isEmpty());
        TaskState restored = restarted.restore(history.getTaskId());
        assertEquals(history.getAnswer(), restored.getAnswer());
        assertEquals(history.getTraceId(), restored.getTraceId());
        assertEquals(history.getEvents(), restored.getEvents());
        assertEquals(1, restarted.listTasks().size());
        assertTrue(restarted.listDeletedTasks().isEmpty());
        assertThrows(TaskExecuteException.class, () -> restarted.restore(history.getTaskId()));
        verifyNoInteractions(original.generate);
    }

    @Test void approvalCannotBeDeletedAndFailedDatabaseDeleteKeepsHistoryVisible() throws Exception {
        Fixture fixture = fixture(true);
        String id = fixture.harness.submit("delete guard", null, null).getTaskId();
        await(fixture.harness, id, state -> state.getStatus() == TaskState.Status.AWAITING_CONFIRMATION);
        TaskExecuteException blocked = assertThrows(TaskExecuteException.class, () -> fixture.harness.delete(id));
        assertEquals("DELETE_NOT_ALLOWED", blocked.getErrorCode());
        fixture.harness.confirm(id, false, "cancel for deletion test");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"))
                .when(fixture.repository).markDeleted(id);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> fixture.harness.delete(id));
        assertEquals(TaskState.Status.CANCELLED, fixture.harness.requireTask(id).getStatus());
        assertTrue(fixture.harness.listDeletedTasks().isEmpty());
        verify(fixture.generate, never()).generate(anyString(), anyString());
    }

    @Test void permanentDeletionRequiresTrashAndRemovesRecoverability() {
        Fixture fixture = fixture(false);
        TaskState history = seed("task-permanent", TaskState.Status.SUCCEEDED);
        fixture.repository.save(history);
        fixture.logs.record(history.getTaskId(), "TEST", "original log");
        fixture.harness.initialize();
        assertThrows(TaskExecuteException.class, () -> fixture.harness.permanentlyDelete(history.getTaskId()));
        fixture.harness.delete(history.getTaskId());
        fixture.harness.permanentlyDelete(history.getTaskId());
        assertTrue(fixture.harness.listDeletedTasks().isEmpty());
        assertTrue(fixture.repository.findAll().isEmpty());
        assertTrue(fixture.logs.list().isEmpty());
        assertThrows(TaskExecuteException.class, () -> fixture.harness.restore(history.getTaskId()));
    }

    @Test void cancelledNetworkCallMustFinishBeforePermanentDeletion() throws Exception {
        Fixture fixture = fixture(true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(fixture.generate.generate(anyString(), anyString())).thenAnswer(invocation -> {
            started.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            return "late result";
        });
        String id = fixture.harness.submit("permanent deletion guard", null, null).getTaskId();
        await(fixture.harness, id, state -> state.getStatus() == TaskState.Status.AWAITING_CONFIRMATION);
        fixture.harness.confirm(id, true, "approved");
        assertTrue(started.await(2, TimeUnit.SECONDS));
        try {
            fixture.harness.cancel(id);
            fixture.harness.delete(id);
            TaskExecuteException blocked = assertThrows(TaskExecuteException.class, () -> fixture.harness.permanentlyDelete(id));
            assertEquals("TASK_STILL_STOPPING", blocked.getErrorCode());
            assertEquals(1, fixture.harness.listDeletedTasks().size());
        } finally { release.countDown(); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        boolean purged = false;
        while (System.nanoTime() < deadline) {
            try { fixture.harness.permanentlyDelete(id); purged = true; break; }
            catch (TaskExecuteException busy) { assertEquals("TASK_STILL_STOPPING", busy.getErrorCode()); Thread.sleep(10); }
        }
        assertTrue(purged);
        assertTrue(fixture.harness.listDeletedTasks().isEmpty());
        assertTrue(fixture.repository.findAll().isEmpty());
        assertTrue(fixture.logs.list().stream().noneMatch(record -> id.equals(record.taskId())));
    }

    private TaskState seed(String id, TaskState.Status status) {
        TaskState state = new TaskState(id, "question", null, null);
        state.setStatus(status); state.addEvent("SEED", "durable event"); return state;
    }
    private TaskState await(TaskHarness harness, String id, Predicate<TaskState> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do { TaskState state = harness.requireTask(id); if (condition.test(state)) { return state; } Thread.sleep(10); }
        while (System.nanoTime() < deadline);
        fail("Timed out waiting for task " + id + ": " + harness.requireTask(id).getStatus()); return null;
    }
    private Fixture fixture(boolean initialize) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        TaskStateRepository repository = spy(com.agent.springaitaskagent.persistence.PersistenceTestSupport.repository(jdbc, new ObjectMapper().findAndRegisterModules()));
        TaskLogService logs = com.agent.springaitaskagent.persistence.PersistenceTestSupport.logs(100, jdbc);
        DocumentParseSkill parse = mock(DocumentParseSkill.class);
        InformationRetrieveSkill retrieve = mock(InformationRetrieveSkill.class);
        ResultGenerateSkill generate = mock(ResultGenerateSkill.class);
        when(parse.parse(any(), any())).thenReturn(List.of(new Document("policy")));
        when(retrieve.retrieve(anyString(), anyList())).thenReturn("evidence");
        when(generate.generate(anyString(), anyString())).thenReturn("550 元/晚 [policy]");
        TaskHarness harness = new TaskHarness(parse, retrieve, generate, mock(VectorStoreService.class), new RetryExecutor(logs), logs, repository);
        ReflectionTestUtils.setField(harness, "workers", 1); ReflectionTestUtils.setField(harness, "queueCapacity", 1);
        harnesses.add(harness); if (initialize) { harness.initialize(); }
        return new Fixture(harness, repository, jdbc, logs, parse, retrieve, generate);
    }
    private record Fixture(TaskHarness harness, TaskStateRepository repository, JdbcTemplate jdbc, TaskLogService logs,
                           DocumentParseSkill parse, InformationRetrieveSkill retrieve, ResultGenerateSkill generate) { }
}
