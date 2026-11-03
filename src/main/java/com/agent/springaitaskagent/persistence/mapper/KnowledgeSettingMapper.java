package com.agent.springaitaskagent.persistence.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.agent.springaitaskagent.persistence.entity.KnowledgeSettingEntity;
import org.apache.ibatis.annotations.InsertProvider;

public interface KnowledgeSettingMapper extends BaseMapper<KnowledgeSettingEntity> {
    @InsertProvider(type = AtomicUpsertSql.class, method = "setting")
    int upsert(KnowledgeSettingEntity entity);
}
