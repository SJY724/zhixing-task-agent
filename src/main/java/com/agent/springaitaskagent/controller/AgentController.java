package com.agent.springaitaskagent.controller;

import com.agent.springaitaskagent.common.ApiResponse;
import com.agent.springaitaskagent.common.TaskLogService;
import com.agent.springaitaskagent.controller.dto.AgentTaskRequest;
import com.agent.springaitaskagent.controller.dto.ConfirmRequest;
import com.agent.springaitaskagent.harness.TaskHarness;
import com.agent.springaitaskagent.harness.TaskState;
import com.agent.springaitaskagent.rag.KnowledgeService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 对外 REST API：提交任务、查询状态、人工确认、获取结果、查看任务与日志。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final TaskHarness taskHarness;
    private final TaskLogService taskLogService;
    private final KnowledgeService knowledge;
    @Value("${spring.ai.openai.chat.options.model:qwen-plus}") private String chatModel;
    @Value("${spring.ai.openai.embedding.options.model:text-embedding-v2}") private String embeddingModel;
    @Value("${spring.ai.openai.api-key:}") private String apiKey;

    public AgentController(TaskHarness taskHarness, TaskLogService taskLogService, KnowledgeService knowledge) {
        this.taskHarness = taskHarness;
        this.taskLogService = taskLogService;
        this.knowledge = knowledge;
    }

    /** 提交任务（异步执行，立即返回 taskId）。 */
    @PostMapping("/tasks")
    public ApiResponse<TaskState> submit(@Valid @RequestBody AgentTaskRequest request) {
        if (request.getDocumentPath() != null && !request.getDocumentPath().isBlank())
            throw new IllegalArgumentException("不接受本机文件路径，请通过知识空间上传或粘贴补充文本");
        if (!request.getDocumentIds().isEmpty() && request.getTextContent() != null && !request.getTextContent().isBlank())
            throw new IllegalArgumentException("文档范围与补充文本请选择一种，避免依据混淆");
        knowledge.validateSelection(request.getDocumentIds());
        if ((request.getTextContent() == null || request.getTextContent().isBlank()) && !knowledge.isReady())
            throw new com.agent.springaitaskagent.harness.exception.TaskExecuteException("KNOWLEDGE_NOT_READY", "知识库尚不可用，请先上传有效文档并检查模型配置");
        return ApiResponse.ok(taskHarness.submit(
                request.getQuestion().strip(), null, request.getTextContent(), request.getDocumentIds()));
    }

    /** 查询单个任务状态。 */
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<TaskState> getTask(@PathVariable String taskId) {
        return ApiResponse.ok(taskHarness.requireTask(taskId));
    }

    /** 获取任务最终结果。 */
    @GetMapping("/tasks/deleted")
    public ApiResponse<List<TaskState>> deletedTasks() {
        return ApiResponse.ok(taskHarness.listDeletedTasks());
    }

    @DeleteMapping("/tasks/{taskId}")
    public ApiResponse<Map<String, Boolean>> deleteTask(@PathVariable String taskId) {
        taskHarness.delete(taskId);
        return ApiResponse.ok(Map.of("deleted", true, "recoverable", true));
    }

    @PostMapping("/tasks/{taskId}/restore")
    public ApiResponse<TaskState> restoreTask(@PathVariable String taskId) {
        return ApiResponse.ok(taskHarness.restore(taskId));
    }

    @DeleteMapping("/tasks/{taskId}/permanent")
    public ApiResponse<Map<String, Boolean>> permanentlyDeleteTask(@PathVariable String taskId) {
        taskHarness.permanentlyDelete(taskId);
        return ApiResponse.ok(Map.of("deleted", true, "recoverable", false));
    }

    /** 获取任务最终结果。 */
    @GetMapping("/tasks/{taskId}/result")
    public ApiResponse<String> getResult(@PathVariable String taskId) {
        return ApiResponse.ok(taskHarness.requireTask(taskId).getAnswer());
    }

    /** 人机协同确认：放行（true）或拒绝（false）等待确认的任务。 */
    @PostMapping("/tasks/{taskId}/confirm")
    public ApiResponse<TaskState> confirm(@PathVariable String taskId, @Valid @RequestBody ConfirmRequest request) {
        taskHarness.confirm(taskId, request.isApproved(), request.getComment());
        return ApiResponse.ok(taskHarness.requireTask(taskId));
    }

    /** 任务列表（按创建时间倒序）。 */
    @GetMapping("/tasks")
    public ApiResponse<List<TaskState>> listTasks() {
        return ApiResponse.ok(taskHarness.listTasks());
    }

    /** 任务日志（日志采集）。 */
    @GetMapping("/logs")
    public ApiResponse<List<TaskLogService.LogRecord>> listLogs() {
        return ApiResponse.ok(taskLogService.list());
    }

    @GetMapping("/tasks/{taskId}/logs")
    public ApiResponse<List<TaskLogService.LogRecord>> taskLogs(@PathVariable String taskId) {
        taskHarness.requireTask(taskId);
        return ApiResponse.ok(taskLogService.list().stream().filter(r -> taskId.equals(r.taskId())).toList());
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<TaskState> cancel(@PathVariable String taskId) { return ApiResponse.ok(taskHarness.cancel(taskId)); }

    @PostMapping("/tasks/{taskId}/retry")
    public ApiResponse<TaskState> retry(@PathVariable String taskId) { return ApiResponse.ok(taskHarness.retry(taskId)); }

    @GetMapping("/knowledge")
    public ApiResponse<List<KnowledgeService.KnowledgeDocument>> knowledge() { return ApiResponse.ok(knowledge.list()); }

    @PostMapping("/knowledge/rebuild")
    public ApiResponse<List<KnowledgeService.KnowledgeDocument>> rebuildKnowledge() {
        knowledge.rebuild(); return ApiResponse.ok(knowledge.list());
    }

    @PostMapping("/knowledge/upload")
    public ApiResponse<KnowledgeService.KnowledgeDocument> upload(@RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(knowledge.upload(file));
    }

    @DeleteMapping("/knowledge/{id}")
    public ApiResponse<Map<String,Boolean>> deleteKnowledge(@PathVariable String id) {
        knowledge.delete(id); return ApiResponse.ok(Map.of("deleted", true));
    }

    @GetMapping("/system")
    public ApiResponse<Map<String,Object>> system() {
        return ApiResponse.ok(Map.of("mode", "cloud", "chatModel", chatModel, "embeddingModel", embeddingModel,
                "knowledgeReady", knowledge.isReady(), "documentCount", knowledge.countReady(), "storage", knowledge.storageName(),
                "confirmationRequired", true, "apiKeyConfigured", apiKey != null && !apiKey.isBlank() && !apiKey.contains("placeholder"),
                "knowledgeError", knowledge.getLastError() == null ? "" : knowledge.getLastError()));
    }

    @GetMapping("/metrics")
    public ApiResponse<Map<String,Long>> metrics() {
        List<TaskState> tasks = taskHarness.listTasks();
        return ApiResponse.ok(Map.of("total", (long)tasks.size(), "awaiting", count(tasks, TaskState.Status.AWAITING_CONFIRMATION),
                "succeeded", count(tasks, TaskState.Status.SUCCEEDED), "failed", count(tasks, TaskState.Status.FAILED),
                "cancelled", count(tasks, TaskState.Status.CANCELLED)));
    }
    private long count(List<TaskState> tasks, TaskState.Status status) { return tasks.stream().filter(t -> t.getStatus() == status).count(); }
}
