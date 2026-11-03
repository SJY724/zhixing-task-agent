package com.agent.springaitaskagent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agent.springaitaskagent.persistence.entity.DeletedTaskEntity;
import org.apache.ibatis.annotations.*;

public interface DeletedTaskMapper extends BaseMapper<DeletedTaskEntity> {
    @InsertProvider(type = AtomicUpsertSql.class, method = "deletion")
    int upsert(DeletedTaskEntity entity);

    @Select("SELECT task_id,deleted_at FROM agent_deleted_tasks WHERE task_id=#{taskId} FOR UPDATE")
    DeletedTaskEntity lockByTaskId(@Param("taskId") String taskId);
}
