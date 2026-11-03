package com.agent.springaitaskagent.persistence.mapper;

import com.agent.springaitaskagent.persistence.entity.TaskEventEntity;
import org.apache.ibatis.annotations.*;

/** The journal has a composite primary key, so its atomic SQL is explicit. */
public interface TaskEventMapper {
    @InsertProvider(type = AtomicUpsertSql.class, method = "event")
    int upsert(TaskEventEntity event);

    @Delete("DELETE FROM agent_task_events WHERE task_id=#{taskId}")
    int deleteByTaskId(@Param("taskId") String taskId);
}
