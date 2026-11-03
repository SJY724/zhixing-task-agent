package com.agent.springaitaskagent.harness;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable task snapshot. Harness serializes each transition while holding its task lock. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskState {
    public enum Status { PENDING, RUNNING, AWAITING_CONFIRMATION, RETRYING, SUCCEEDED, FAILED, CANCELLED }
    private final String taskId;
    private final String question;
    private final String documentPath;
    private final String textContent;
    private final LocalDateTime createdAt;
    private final String traceId;
    private final String parentTaskId;
    private Status status = Status.PENDING;
    private String phase = "QUEUED";
    private String context;
    private String answer;
    private String errorMessage;
    private String confirmComment;
    private LocalDateTime updatedAt;
    private LocalDateTime approvalExpiresAt;
    private List<String> documentIds = new ArrayList<>();
    private List<String> steps = new ArrayList<>();
    private List<TaskEvent> events = new ArrayList<>();

    public TaskState(String taskId, String question, String documentPath, String textContent) {
        this(taskId, question, documentPath, textContent, LocalDateTime.now(), UUID.randomUUID().toString().replace("-", ""), null);
    }

    @JsonCreator
    public TaskState(@JsonProperty("taskId") String taskId, @JsonProperty("question") String question,
                     @JsonProperty("documentPath") String documentPath, @JsonProperty("textContent") String textContent,
                     @JsonProperty("createdAt") LocalDateTime createdAt, @JsonProperty("traceId") String traceId,
                     @JsonProperty("parentTaskId") String parentTaskId) {
        this.taskId = taskId;
        this.question = question;
        this.documentPath = documentPath;
        this.textContent = textContent;
        this.createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
        this.updatedAt = this.createdAt;
        this.traceId = traceId == null ? UUID.randomUUID().toString().replace("-", "") : traceId;
        this.parentTaskId = parentTaskId;
    }

    public void addStep(String message) { addEvent(phase, message); }
    public void addEvent(String stage, String message) {
        LocalDateTime now = LocalDateTime.now();
        steps.add(now + " | " + message);
        events.add(new TaskEvent(events.size() + 1, now, stage, message, taskId, traceId, status));
        updatedAt = now;
    }
    @JsonIgnore
    public boolean isTerminal() { return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELLED; }
    public record TaskEvent(int sequence, LocalDateTime timestamp, String stage, String message, String taskId, String traceId, Status status) { }
    public String getTaskId() { return taskId; }
    public String getQuestion() { return question; }
    public String getDocumentPath() { return documentPath; }
    public String getTextContent() { return textContent; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getTraceId() { return traceId; }
    public String getParentTaskId() { return parentTaskId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; this.updatedAt = LocalDateTime.now(); }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getConfirmComment() { return confirmComment; }
    public void setConfirmComment(String confirmComment) { this.confirmComment = confirmComment; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getApprovalExpiresAt() { return approvalExpiresAt; }
    public void setApprovalExpiresAt(LocalDateTime approvalExpiresAt) { this.approvalExpiresAt = approvalExpiresAt; }
    public List<String> getDocumentIds() { return List.copyOf(documentIds); }
    public void setDocumentIds(List<String> documentIds) { this.documentIds = documentIds == null ? new ArrayList<>() : new ArrayList<>(documentIds); }
    public List<String> getSteps() { return List.copyOf(steps); }
    public void setSteps(List<String> steps) { this.steps = new ArrayList<>(steps); }
    public List<TaskEvent> getEvents() { return List.copyOf(events); }
    public void setEvents(List<TaskEvent> events) { this.events = new ArrayList<>(events); }

    /** Preserve the lock identity while publishing an already committed snapshot. */
    void applySnapshot(TaskState committed) {
        this.status = committed.status;
        this.phase = committed.phase;
        this.context = committed.context;
        this.answer = committed.answer;
        this.errorMessage = committed.errorMessage;
        this.confirmComment = committed.confirmComment;
        this.updatedAt = committed.updatedAt;
        this.approvalExpiresAt = committed.approvalExpiresAt;
        this.documentIds = new ArrayList<>(committed.documentIds);
        this.steps = new ArrayList<>(committed.steps);
        this.events = new ArrayList<>(committed.events);
    }
}
