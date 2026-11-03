package com.agent.springaitaskagent.persistence;

import com.agent.springaitaskagent.harness.TaskState;
import com.agent.springaitaskagent.persistence.entity.*;
import com.agent.springaitaskagent.persistence.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** MyBatis-Plus mappers share Spring's transaction for snapshots, events and deletion. */
@Repository
public class TaskStateRepository {
    private final ObjectMapper mapper;
    private final TaskSnapshotMapper tasks;
    private final TaskEventMapper events;
    private final DeletedTaskMapper deleted;
    private final TaskLogMapper logs;

    public TaskStateRepository(DatabaseSchema schema, ObjectMapper mapper, TaskSnapshotMapper tasks,
                               TaskEventMapper events, DeletedTaskMapper deleted, TaskLogMapper logs) {
        this.mapper = mapper; this.tasks = tasks; this.events = events; this.deleted = deleted; this.logs = logs;
    }
    @Transactional
    public void save(TaskState state) {
        TaskSnapshotEntity snapshot = new TaskSnapshotEntity();
        snapshot.setTaskId(state.getTaskId()); snapshot.setSnapshot(encode(state));
        tasks.upsert(snapshot);
        for (TaskState.TaskEvent event : state.getEvents()) {
            TaskEventEntity row = new TaskEventEntity();
            row.setTaskId(state.getTaskId()); row.setEventSequence(event.sequence()); row.setTraceId(state.getTraceId());
            row.setStage(event.stage()); row.setOccurredAt(event.timestamp().toString()); row.setMessage(event.message());
            events.upsert(row);
        }
    }
    public List<TaskState> findAll() {
        return tasks.selectList(new QueryWrapper<TaskSnapshotEntity>()
                .notExists("SELECT 1 FROM agent_deleted_tasks d WHERE d.task_id=agent_tasks.task_id"))
                .stream().map(row -> decode(row.getSnapshot())).toList();
    }
    public List<TaskState> findDeleted() {
        return tasks.selectDeleted().stream().map(row -> decode(row.getSnapshot())).toList();
    }
    public TaskState findDeleted(String taskId) {
        TaskSnapshotEntity row = tasks.selectDeletedById(taskId);
        return row == null ? null : decode(row.getSnapshot());
    }
    @Transactional
    public void markDeleted(String taskId) {
        DeletedTaskEntity row = new DeletedTaskEntity();
        row.setTaskId(taskId); row.setDeletedAt(java.time.LocalDateTime.now().toString());
        deleted.upsert(row);
    }
    @Transactional
    public boolean restoreDeleted(String taskId) { return deleted.deleteById(taskId) == 1; }

    @Transactional
    public boolean permanentlyDelete(String taskId) {
        // The row lock prevents a concurrent restore from splitting the purge across two transactions.
        if (deleted.lockByTaskId(taskId) == null) return false;
        events.deleteByTaskId(taskId);
        logs.delete(new QueryWrapper<TaskLogEntity>().eq("task_id", taskId));
        tasks.deleteById(taskId);
        deleted.deleteById(taskId);
        return true;
    }
    public TaskState copy(TaskState state) { return decode(encode(state)); }
    private String encode(TaskState state) {
        try { return mapper.writeValueAsString(state); }
        catch (JsonProcessingException e) { throw new IllegalStateException("任务状态序列化失败", e); }
    }
    private TaskState decode(String json) {
        try { return mapper.readValue(json, TaskState.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("任务历史读取失败，请检查数据库兼容性", e); }
    }
}
