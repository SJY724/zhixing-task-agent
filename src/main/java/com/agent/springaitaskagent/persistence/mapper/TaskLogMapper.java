package com.agent.springaitaskagent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agent.springaitaskagent.persistence.entity.TaskLogEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface TaskLogMapper extends BaseMapper<TaskLogEntity> {
    @Select("SELECT id,occurred_at,task_id,trace_id,stage,message FROM agent_logs ORDER BY id DESC LIMIT #{limit}")
    List<TaskLogEntity> selectRecent(@Param("limit") int limit);
}
