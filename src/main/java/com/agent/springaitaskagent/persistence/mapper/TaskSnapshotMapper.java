package com.agent.springaitaskagent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agent.springaitaskagent.persistence.entity.TaskSnapshotEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface TaskSnapshotMapper extends BaseMapper<TaskSnapshotEntity> {
    @InsertProvider(type = AtomicUpsertSql.class, method = "snapshot")
    int upsert(TaskSnapshotEntity entity);

    @Select("SELECT t.task_id,t.snapshot FROM agent_tasks t JOIN agent_deleted_tasks d ON d.task_id=t.task_id ORDER BY d.deleted_at DESC")
    List<TaskSnapshotEntity> selectDeleted();

    @Select("SELECT t.task_id,t.snapshot FROM agent_tasks t JOIN agent_deleted_tasks d ON d.task_id=t.task_id WHERE t.task_id=#{taskId}")
    TaskSnapshotEntity selectDeletedById(@Param("taskId") String taskId);
}
