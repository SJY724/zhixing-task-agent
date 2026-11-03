package com.agent.springaitaskagent.config;

import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Properties;

@Configuration(proxyBeanMethods = false)
@MapperScan("com.agent.springaitaskagent.persistence.mapper")
public class MybatisPersistenceConfiguration {
    @Bean
    public DatabaseIdProvider databaseIdProvider() {
        VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
        Properties names = new Properties();
        names.setProperty("H2", "h2");
        names.setProperty("MySQL", "mysql");
        provider.setProperties(names);
        return provider;
    }
}
