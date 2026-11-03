package com.agent.springaitaskagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("agent_task_events")
public class TaskEventEntity {
    private String taskId;
    private Integer eventSequence;
    private String traceId;
    private String stage;
    private String occurredAt;
    private String message;
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public Integer getEventSequence() { return eventSequence; }
    public void setEventSequence(Integer eventSequence) { this.eventSequence = eventSequence; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getOccurredAt() { return occurredAt; }
    public void setOccurredAt(String occurredAt) { this.occurredAt = occurredAt; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
