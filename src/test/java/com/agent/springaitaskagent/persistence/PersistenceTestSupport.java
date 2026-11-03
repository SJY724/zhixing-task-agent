package com.agent.springaitaskagent.persistence;

import com.agent.springaitaskagent.common.TaskLogService;
import com.agent.springaitaskagent.config.MybatisPersistenceConfiguration;
import com.agent.springaitaskagent.persistence.mapper.*;
import com.agent.springaitaskagent.rag.KnowledgeService;
import com.agent.springaitaskagent.rag.VectorStoreService;
import com.agent.springaitaskagent.skill.DocumentParseSkill;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real mapper sessions over each isolated test DataSource; Spring transaction binding is preserved. */
public final class PersistenceTestSupport {
    private PersistenceTestSupport() { }
    public static SqlSessionTemplate session(JdbcTemplate jdbc) {
        try {
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.setDatabaseId(new MybatisPersistenceConfiguration().databaseIdProvider().getDatabaseId(jdbc.getDataSource()));
            config.addMappers("com.agent.springaitaskagent.persistence.mapper");
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(jdbc.getDataSource());
            factory.setConfiguration(config);
            factory.setDatabaseIdProvider(new MybatisPersistenceConfiguration().databaseIdProvider());
            return new SqlSessionTemplate(factory.getObject());
        } catch (Exception error) { throw new IllegalStateException("Unable to initialize test mappers", error); }
    }
    public static TaskStateRepository repository(JdbcTemplate jdbc, ObjectMapper json) {
        DatabaseSchema schema = new DatabaseSchema(jdbc.getDataSource());
        SqlSessionTemplate sql = session(jdbc);
        return new TaskStateRepository(schema, json, sql.getMapper(TaskSnapshotMapper.class), sql.getMapper(TaskEventMapper.class),
                sql.getMapper(DeletedTaskMapper.class), sql.getMapper(TaskLogMapper.class));
    }
    public static TaskLogService logs(int size, JdbcTemplate jdbc) {
        return new TaskLogService(size, new DatabaseSchema(jdbc.getDataSource()), session(jdbc).getMapper(TaskLogMapper.class));
    }
    public static KnowledgeService knowledge(JdbcTemplate jdbc, ObjectMapper json, DocumentParseSkill parser,
                                             VectorStoreService vectors, String path) {
        DatabaseSchema schema = new DatabaseSchema(jdbc.getDataSource());
        SqlSessionTemplate sql = session(jdbc);
        return new KnowledgeService(schema, sql.getMapper(KnowledgeDocumentMapper.class), sql.getMapper(KnowledgeSettingMapper.class),
                json, parser, vectors, path);
    }
}
