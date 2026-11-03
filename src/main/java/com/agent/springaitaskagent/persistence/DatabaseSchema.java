package com.agent.springaitaskagent.persistence;

import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/** Runs idempotent SQL initialization before application repositories are used. */
@Component
public class DatabaseSchema {
    private final DatabaseDialect dialect;

    public DatabaseSchema(DataSource source) {
        dialect = DatabaseDialect.detect(source);
        ResourceDatabasePopulator scripts = new ResourceDatabasePopulator(new ClassPathResource(dialect.schemaScript()));
        scripts.setSqlScriptEncoding("UTF-8");
        scripts.setContinueOnError(false);
        scripts.execute(source);
    }

    public DatabaseDialect dialect() { return dialect; }
}
