package com.agent.springaitaskagent.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/** Selects the initialization script from the connected database product. */
public enum DatabaseDialect {
    H2("H2", "db/schema-h2.sql"),
    MYSQL("MySQL", "db/schema-mysql.sql");

    private final String displayName;
    private final String schemaScript;

    DatabaseDialect(String displayName, String schemaScript) {
        this.displayName = displayName;
        this.schemaScript = schemaScript;
    }

    public static DatabaseDialect detect(DataSource source) {
        if (source == null) throw new IllegalArgumentException("数据库连接池不能为空");
        try (Connection connection = source.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null) throw new IllegalStateException("无法识别数据库类型");
            return switch (product.toLowerCase(Locale.ROOT)) {
                case "h2" -> H2;
                case "mysql" -> MYSQL;
                default -> throw new IllegalStateException("不支持的数据库类型：" + product + "，请使用 H2 或 MySQL 8.4");
            };
        } catch (SQLException error) {
            throw new IllegalStateException("无法读取数据库连接信息", error);
        }
    }

    public String displayName() { return displayName; }
    public String schemaScript() { return schemaScript; }
}
