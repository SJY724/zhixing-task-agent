package com.agent.springaitaskagent.common;

import com.agent.springaitaskagent.persistence.DatabaseSchema;
import com.agent.springaitaskagent.persistence.entity.TaskLogEntity;
import com.agent.springaitaskagent.persistence.mapper.TaskLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persist task and Skill audit logs; API queries return the most recent configured window.
 */
@Service
public class TaskLogService {

    private final int bufferSize;
    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
    private final TaskLogMapper logs;

    @Autowired
    public TaskLogService(@Value("${task-agent.log.buffer-size:2000}") int bufferSize, DatabaseSchema schema, TaskLogMapper logs) {
        this.bufferSize = Math.max(100, bufferSize);
        this.logs = logs;
    }

    /** In-memory variant for focused unit tests. */
    public TaskLogService(int bufferSize) {
        this.bufferSize = Math.max(100, bufferSize);
        this.logs = null;
    }

    public void record(String taskId, String stage, String message) {
        String safeMessage = message == null ? "" : message
                .replaceAll("(?<![A-Za-z0-9])sk-[A-Za-z0-9._-]+", "[密钥已隐藏]")
                .replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._-]+", "$1[已隐藏]");
        synchronized (records) {
            LogRecord record = new LogRecord(LocalDateTime.now(), taskId, MDC.get("traceId"), stage, safeMessage);
            if (logs != null) {
                TaskLogEntity row = new TaskLogEntity();
                row.setOccurredAt(record.timestamp().toString()); row.setTaskId(taskId);
                row.setTraceId(record.traceId()); row.setStage(stage); row.setMessage(safeMessage);
                logs.insert(row);
            }
            records.add(record);
            while (records.size() > bufferSize) {
                records.remove(0);
            }
        }
    }

    public List<LogRecord> list() {
        if (logs != null) {
            List<LogRecord> persisted = new ArrayList<>(logs.selectRecent(bufferSize).stream()
                    .map(row -> new LogRecord(LocalDateTime.parse(row.getOccurredAt()), row.getTaskId(),
                            row.getTraceId(), row.getStage(), row.getMessage())).toList());
            Collections.reverse(persisted);
            return persisted;
        }
        synchronized (records) {
            return new ArrayList<>(records);
        }
    }

    /** Called only after the database purge has committed. */
    public void forgetTask(String taskId) {
        synchronized (records) { records.removeIf(record -> taskId.equals(record.taskId())); }
    }

    public void clear() {
        if (logs != null) { logs.delete(new QueryWrapper<TaskLogEntity>()); }
        synchronized (records) {
            records.clear();
        }
    }

    public record LogRecord(LocalDateTime timestamp, String taskId, String traceId, String stage, String message) {
    }
}
