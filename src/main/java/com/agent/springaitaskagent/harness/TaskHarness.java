package com.agent.springaitaskagent.harness;

import com.agent.springaitaskagent.common.RetryExecutor;
import com.agent.springaitaskagent.common.TaskLogService;
import com.agent.springaitaskagent.harness.exception.TaskExecuteException;
import com.agent.springaitaskagent.persistence.TaskStateRepository;
import com.agent.springaitaskagent.rag.VectorStoreService;
import com.agent.springaitaskagent.skill.DocumentParseSkill;
import com.agent.springaitaskagent.skill.InformationRetrieveSkill;
import com.agent.springaitaskagent.skill.ResultGenerateSkill;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Durable state machine. Approval is persisted and releases its worker; confirmation schedules a new job. */
@Service
public class TaskHarness {
    private static final Logger log = LoggerFactory.getLogger(TaskHarness.class);
    private final DocumentParseSkill documentParseSkill;
    private final InformationRetrieveSkill informationRetrieveSkill;
    private final ResultGenerateSkill resultGenerateSkill;
    private final VectorStoreService vectorStoreService;
    private final RetryExecutor retryExecutor;
    private final TaskLogService taskLogService;
    private final TaskStateRepository repository;
    private final Map<String, TaskState> taskRegistry = new ConcurrentHashMap<>();
    private final Map<String, FutureTask<Void>> jobs = new ConcurrentHashMap<>();
    private final java.util.Set<String> executingTasks = ConcurrentHashMap.newKeySet();
    private final Object taskLifecycleLock = new Object();
    private ThreadPoolExecutor executor;
    private ScheduledExecutorService timer;
    @Value("${task-agent.confirmation.enabled:true}") private boolean confirmationEnabled = true;
    @Value("${task-agent.confirmation.timeout-minutes:10}") private long confirmationTimeoutMinutes = 10;
    @Value("${task-agent.executor.workers:2}") private int workers = 2;
    @Value("${task-agent.executor.queue-capacity:32}") private int queueCapacity = 32;

    public TaskHarness(DocumentParseSkill documentParseSkill, InformationRetrieveSkill informationRetrieveSkill,
                       ResultGenerateSkill resultGenerateSkill, VectorStoreService vectorStoreService,
                       RetryExecutor retryExecutor, TaskLogService taskLogService, TaskStateRepository repository) {
        this.documentParseSkill = documentParseSkill;
        this.informationRetrieveSkill = informationRetrieveSkill;
        this.resultGenerateSkill = resultGenerateSkill;
        this.vectorStoreService = vectorStoreService;
        this.retryExecutor = retryExecutor;
        this.taskLogService = taskLogService;
        this.repository = repository;
    }

    @PostConstruct
    public void initialize() {
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(Math.max(1, workers), Math.max(1, workers), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)), runnable -> {
                    Thread thread = new Thread(runnable, "agent-worker-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        for (TaskState state : repository.findAll()) {
            taskRegistry.put(state.getTaskId(), state);
            synchronized (state) {
                if (!state.isTerminal() && state.getStatus() != TaskState.Status.AWAITING_CONFIRMATION) {
                    transition(state, TaskState.Status.FAILED, "RECOVERY", "检测到上次执行中断，已保留记录并标记失败",
                            next -> next.setErrorMessage("服务重启中断了执行。为避免重复调用，未自动续跑；可点击重试创建新任务。"));
                } else if (state.getStatus() == TaskState.Status.AWAITING_CONFIRMATION) {
                    if (state.getApprovalExpiresAt() == null) {
                        event(state, "RECOVERY", "恢复待审批任务，审批倒计时已重新建立",
                                next -> next.setApprovalExpiresAt(LocalDateTime.now().plusMinutes(Math.max(1, confirmationTimeoutMinutes))));
                    }
                    expireIfNecessary(state);
                }
            }
        }
        timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-approval-deadline"); thread.setDaemon(true); return thread;
        });
        timer.scheduleWithFixedDelay(this::expireApprovals, 5, 5, TimeUnit.SECONDS);
    }

    public TaskState submit(String question, String documentPath, String textContent) {
        return submit(question, documentPath, textContent, List.of());
    }

    public TaskState submit(String question, String documentPath, String textContent, List<String> documentIds) {
        return create(question, documentPath, textContent, documentIds, null);
    }

    private TaskState create(String question, String documentPath, String textContent, List<String> documentIds, String parentTaskId) {
        if (!StringUtils.hasText(question)) { throw new IllegalArgumentException("question 不能为空"); }
        if (question.length() > 4000) { throw new IllegalArgumentException("问题不能超过 4000 字符"); }
        if (textContent != null && textContent.length() > 100000) { throw new IllegalArgumentException("补充文本不能超过 100000 字符"); }
        List<String> selectedIds = documentIds == null ? List.of() : documentIds;
        if (selectedIds.size() > 50 || selectedIds.stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_-]{1,100}"))) {
            throw new IllegalArgumentException("所选知识文档编号无效，最多选择 50 份文档");
        }
        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String traceId = StringUtils.hasText(MDC.get("traceId")) ? MDC.get("traceId") : UUID.randomUUID().toString().replace("-", "");
        TaskState state = new TaskState(taskId, question.strip(), documentPath, textContent, LocalDateTime.now(), traceId, parentTaskId);
        state.setDocumentIds(selectedIds.stream().distinct().toList());
        synchronized (state) {
            taskRegistry.put(taskId, state);
            try { event(state, "SUBMIT", parentTaskId == null ? "任务创建，等待编排执行" : "从 " + parentTaskId + " 创建重试任务"); }
            catch (RuntimeException persistenceFailure) { taskRegistry.remove(taskId, state); throw persistenceFailure; }
            enqueue(state, () -> retrieve(state));
            return repository.copy(state);
        }
    }

    private void retrieve(TaskState state) {
        try {
            boolean provided = StringUtils.hasText(state.getDocumentPath()) || StringUtils.hasText(state.getTextContent());
            beginPhase(state, "PARSE", "开始文档解析");
            List<Document> docs = !provided && !state.getDocumentIds().isEmpty() ? List.of()
                    : execute(state, () -> documentParseSkill.parse(state.getDocumentPath(), state.getTextContent()), "DocumentParseSkill.parse");
            synchronized (state) {
                ensureActive(state);
                event(state, "PARSE", !provided && !state.getDocumentIds().isEmpty()
                        ? "使用已入库的 " + state.getDocumentIds().size() + " 份知识文档，复用现有索引"
                        : "文档解析完成，分片数=" + docs.size());
            }
            beginPhase(state, "RETRIEVE", "开始知识库检索");
            // Extra documents are scoped to this task; they must never contaminate another task's knowledge.
            String context = execute(state, () -> {
                if (provided) { return informationRetrieveSkill.retrieve(state.getQuestion(), docs); }
                if (!state.getDocumentIds().isEmpty()) { return informationRetrieveSkill.retrieveSelected(state.getQuestion(), state.getDocumentIds()); }
                return informationRetrieveSkill.retrieve(state.getQuestion(), List.of());
            }, "InformationRetrieveSkill.retrieve");
            synchronized (state) {
                ensureActive(state);
                event(state, "RETRIEVE", StringUtils.hasText(context) ? "知识库检索完成，已找到参考依据" : "知识库检索完成，未找到匹配依据",
                        next -> next.setContext(context));
                if (confirmationEnabled) {
                    transition(state, TaskState.Status.AWAITING_CONFIRMATION, "APPROVAL", "等待人工核对依据并批准；等待期间不占用任务工作线程",
                            next -> next.setApprovalExpiresAt(LocalDateTime.now().plusMinutes(Math.max(1, confirmationTimeoutMinutes))));
                    return;
                }
            }
            generate(state);
        } catch (Exception failure) { fail(state, failure); }
    }

    private void generate(TaskState state) {
        try {
            beginPhase(state, "GENERATE", "开始生成最终回答");
            String answer = execute(state, () -> resultGenerateSkill.generate(state.getQuestion(), state.getContext()), "ResultGenerateSkill.generate");
            synchronized (state) {
                ensureActive(state);
                transition(state, TaskState.Status.SUCCEEDED, "COMPLETED", "结果生成完成", next -> next.setAnswer(answer));
            }
        } catch (Exception failure) { fail(state, failure); }
    }

    public void confirm(String taskId, boolean approved, String comment) {
        TaskState state = internalTask(taskId);
        synchronized (state) {
            expireIfNecessary(state);
            if (state.getStatus() != TaskState.Status.AWAITING_CONFIRMATION) {
                throw new TaskExecuteException("CONFIRM_NOT_ALLOWED", "当前状态不允许审批：" + state.getStatus());
            }
            if (comment != null && comment.length() > 1000) { throw new IllegalArgumentException("审批备注不能超过 1000 字符"); }
            Consumer<TaskState> decision = next -> { next.setConfirmComment(comment); next.setApprovalExpiresAt(null); };
            if (!approved) {
                transition(state, TaskState.Status.CANCELLED, "REJECTED", "人工拒绝，任务已取消，未生成回答", decision);
                return;
            }
            // Claim the decision under the same lock before enqueuing: concurrent confirmations cannot both win.
            transition(state, TaskState.Status.RUNNING, "GENERATION_QUEUED", "人工确认通过，回答生成已排队", decision);
            enqueue(state, () -> generate(state));
        }
    }

    public TaskState cancel(String taskId) {
        TaskState state = internalTask(taskId);
        synchronized (state) {
            if (state.isTerminal()) { throw new TaskExecuteException("CANCEL_NOT_ALLOWED", "任务已结束，不能取消：" + state.getStatus()); }
            transition(state, TaskState.Status.CANCELLED, "CANCELLED", "用户取消任务；已发出的模型请求可能仍产生用量，迟到结果不再写入",
                    next -> next.setApprovalExpiresAt(null));
            FutureTask<Void> future = jobs.get(taskId);
            if (future != null) { future.cancel(true); executor.remove(future); }
            return repository.copy(state);
        }
    }

    public TaskState retry(String taskId) {
        TaskState state = internalTask(taskId);
        synchronized (state) {
            if (state.getStatus() != TaskState.Status.FAILED && state.getStatus() != TaskState.Status.CANCELLED) {
                throw new TaskExecuteException("RETRY_NOT_ALLOWED", "仅失败或取消的任务允许重试");
            }
            return create(state.getQuestion(), state.getDocumentPath(), state.getTextContent(), state.getDocumentIds(), state.getTaskId());
        }
    }

    private <T> T execute(TaskState state, RetryExecutor.RetryableSupplier<T> operation, String name) {
        return retryExecutor.executeWithRetry(state.getTaskId(), () -> {
            synchronized (state) { ensureActive(state); }
            T result = operation.get();
            synchronized (state) { ensureActive(state); }
            return result;
        }, name, new RetryExecutor.RetryListener() {
            public void beforeAttempt(int attempt) {
                synchronized (state) {
                    ensureActive(state);
                    if (attempt > 1) { transition(state, TaskState.Status.RUNNING, state.getPhase(), "开始第 " + attempt + " 次尝试：" + name); }
                }
            }
            public void retrying(int attempt, Exception failure, long delayMillis) {
                synchronized (state) {
                    ensureActive(state);
                    transition(state, TaskState.Status.RETRYING, state.getPhase(), "暂时性错误，第 " + attempt + " 次失败，" + delayMillis + "ms 后重试");
                }
            }
        });
    }

    private void enqueue(TaskState state, Runnable operation) {
        FutureTask<Void> future = new FutureTask<>(() -> {
            executingTasks.add(state.getTaskId());
            Map<String, String> previous = MDC.getCopyOfContextMap();
            MDC.put("taskId", state.getTaskId());
            MDC.put("traceId", state.getTraceId());
            try { operation.run(); return null; }
            finally {
                if (previous == null) { MDC.clear(); } else { MDC.setContextMap(previous); }
                executingTasks.remove(state.getTaskId());
            }
        }) {
            protected void done() { jobs.remove(state.getTaskId(), this); }
        };
        jobs.put(state.getTaskId(), future);
        try { executor.execute(future); }
        catch (RejectedExecutionException rejected) {
            jobs.remove(state.getTaskId(), future);
            try {
                transition(state, TaskState.Status.FAILED, "CAPACITY", "任务队列已满，未开始执行",
                        next -> next.setErrorMessage("任务队列已满，请稍后重试。任务记录已保存。"));
            } catch (RuntimeException persistenceFailure) { markUnpersistedFailure(state, persistenceFailure); }
            throw new TaskExecuteException("TASK_CAPACITY_EXCEEDED", "任务队列已满，请稍后重试");
        }
    }
    private void beginPhase(TaskState state, String phase, String message) {
        synchronized (state) { ensureActive(state); transition(state, TaskState.Status.RUNNING, phase, message); }
    }
    private void ensureActive(TaskState state) {
        if (state.isTerminal() || Thread.currentThread().isInterrupted()) { throw new CancellationException("任务已结束或中断"); }
    }
    private void fail(TaskState state, Exception failure) {
        synchronized (state) {
            if (state.isTerminal()) { return; }
            String message = safeError(failure);
            try { transition(state, TaskState.Status.FAILED, "FAILED", "任务失败：" + message, next -> next.setErrorMessage(message)); }
            catch (RuntimeException persistenceFailure) { markUnpersistedFailure(state, persistenceFailure); }
            log.warn("Task {} failed: {}", state.getTaskId(), failure.getClass().getSimpleName());
        }
    }
    private void markUnpersistedFailure(TaskState state, RuntimeException persistenceFailure) {
        // The worker has stopped even if the disk/database is unavailable. Never present a phantom running job.
        state.setStatus(TaskState.Status.FAILED);
        state.setPhase("PERSISTENCE_FAILURE");
        state.setErrorMessage("执行已停止，但数据库暂时无法保存状态。请检查磁盘与日志后重试；重启时会检测未完成任务。");
        state.addEvent("PERSISTENCE_FAILURE", state.getErrorMessage());
        log.error("Task {} failure state could not be persisted", state.getTaskId(), persistenceFailure);
    }
    private String safeError(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) { return "执行失败，请凭 traceId 查看日志"; }
        message = message.replaceAll("(?<![A-Za-z0-9])sk-[A-Za-z0-9._-]+", "[密钥已隐藏]")
                .replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._-]+", "$1[已隐藏]");
        return message.length() > 600 ? message.substring(0, 600) + "…" : message;
    }
    private void transition(TaskState state, TaskState.Status status, String phase, String message) {
        transition(state, status, phase, message, next -> { });
    }
    private void transition(TaskState state, TaskState.Status status, String phase, String message, Consumer<TaskState> changes) {
        event(state, phase, message, next -> { changes.accept(next); next.setStatus(status); next.setPhase(phase); });
    }
    private void event(TaskState state, String phase, String message) {
        event(state, phase, message, next -> { });
    }
    private void event(TaskState state, String phase, String message, Consumer<TaskState> changes) {
        TaskState committed = repository.copy(state);
        changes.accept(committed);
        committed.addEvent(phase, message);
        repository.save(committed);
        state.applySnapshot(committed);
        String previousTrace = MDC.get("traceId");
        try { MDC.put("traceId", state.getTraceId()); taskLogService.record(state.getTaskId(), phase, message); }
        catch (RuntimeException logFailure) { log.error("Task {} event was committed, but extra log indexing failed", state.getTaskId(), logFailure); }
        finally { if (previousTrace == null) { MDC.remove("traceId"); } else { MDC.put("traceId", previousTrace); } }
    }
    private void expireIfNecessary(TaskState state) {
        if (state.getStatus() == TaskState.Status.AWAITING_CONFIRMATION && state.getApprovalExpiresAt() != null
                && !state.getApprovalExpiresAt().isAfter(LocalDateTime.now())) {
            transition(state, TaskState.Status.CANCELLED, "APPROVAL_TIMEOUT", "人工审批超时，任务已取消；可重试创建新任务",
                    next -> next.setApprovalExpiresAt(null));
        }
    }
    void expireApprovals() {
        try {
            for (TaskState state : taskRegistry.values()) { synchronized (state) { expireIfNecessary(state); } }
        } catch (Exception e) { log.error("Approval deadline sweep failed", e); }
    }
    public void delete(String taskId) {
        synchronized (taskLifecycleLock) {
            TaskState state = internalTask(taskId);
            synchronized (state) {
                if (!state.isTerminal()) {
                    throw new TaskExecuteException("DELETE_NOT_ALLOWED", "仅已完成、失败或取消的任务允许删除，请先处理或取消当前任务");
                }
                // Publish the removal only after the durable deletion marker has committed.
                // Snapshots, events and logs remain intact for recovery, including cancelled jobs' late callbacks.
                repository.markDeleted(taskId);
                taskRegistry.remove(taskId, state);
            }
        }
    }
    public List<TaskState> listDeletedTasks() { return repository.findDeleted(); }
    public void permanentlyDelete(String taskId) {
        synchronized (taskLifecycleLock) {
            TaskState state = repository.findDeleted(taskId);
            if (state == null || !state.isTerminal()) {
                throw new TaskExecuteException("PURGE_NOT_ALLOWED", "只能永久删除最近删除中的已结束任务");
            }
            if (executingTasks.contains(taskId)) {
                throw new TaskExecuteException("TASK_STILL_STOPPING", "该任务的后台请求尚未完全退出，请稍后再永久删除");
            }
            if (!repository.permanentlyDelete(taskId)) {
                throw new TaskExecuteException("TASK_NOT_FOUND", "最近删除中不存在该任务，可能已经恢复或删除");
            }
            taskLogService.forgetTask(taskId);
        }
    }
    public TaskState restore(String taskId) {
        synchronized (taskLifecycleLock) {
            TaskState state = repository.findDeleted(taskId);
            if (state == null) throw new TaskExecuteException("TASK_NOT_FOUND", "最近删除中不存在该任务");
            if (!state.isTerminal()) throw new TaskExecuteException("RESTORE_NOT_ALLOWED", "只能恢复已经结束的历史任务");
            if (!repository.restoreDeleted(taskId)) throw new TaskExecuteException("TASK_NOT_FOUND", "该任务已被恢复");
            taskRegistry.put(taskId, state);
            return repository.copy(state);
        }
    }
    private TaskState internalTask(String taskId) {
        TaskState state = taskRegistry.get(taskId);
        if (state == null) { throw new TaskExecuteException("TASK_NOT_FOUND", "任务不存在：" + taskId); }
        return state;
    }
    public TaskState requireTask(String taskId) {
        TaskState state = internalTask(taskId);
        synchronized (state) { expireIfNecessary(state); return repository.copy(state); }
    }
    public List<TaskState> listTasks() {
        List<TaskState> result = new ArrayList<>();
        for (TaskState state : taskRegistry.values()) { synchronized (state) { expireIfNecessary(state); result.add(repository.copy(state)); } }
        result.sort(Comparator.comparing(TaskState::getCreatedAt).reversed());
        return result;
    }
    @PreDestroy
    public void shutdown() {
        if (timer != null) { timer.shutdownNow(); }
        if (executor != null) {
            executor.shutdownNow();
            try { executor.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
