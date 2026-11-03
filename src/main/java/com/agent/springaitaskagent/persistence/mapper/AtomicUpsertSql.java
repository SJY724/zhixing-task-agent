package com.agent.springaitaskagent.persistence.mapper;

import org.apache.ibatis.builder.annotation.ProviderContext;
import java.util.List;
import java.util.stream.Collectors;

public final class AtomicUpsertSql {
    private AtomicUpsertSql() { }

    public static String snapshot(ProviderContext context) {
        return sql(context, "agent_tasks", List.of("task_id", "snapshot"), List.of("taskId", "snapshot"), List.of("task_id"));
    }
    public static String event(ProviderContext context) {
        return sql(context, "agent_task_events", List.of("task_id", "event_sequence", "trace_id", "stage", "occurred_at", "message"),
                List.of("taskId", "eventSequence", "traceId", "stage", "occurredAt", "message"), List.of("task_id", "event_sequence"));
    }
    public static String deletion(ProviderContext context) {
        return sql(context, "agent_deleted_tasks", List.of("task_id", "deleted_at"), List.of("taskId", "deletedAt"), List.of("task_id"));
    }
    public static String setting(ProviderContext context) {
        return sql(context, "knowledge_settings", List.of("setting_key", "setting_value"), List.of("settingKey", "settingValue"), List.of("setting_key"));
    }
    private static String sql(ProviderContext context, String table, List<String> columns, List<String> properties, List<String> keys) {
        String names = String.join(",", columns);
        String parameters = properties.stream().map(name -> "#{" + name + "}").collect(Collectors.joining(","));
        if ("h2".equals(context.getDatabaseId()))
            return "MERGE INTO " + table + " (" + names + ") KEY(" + String.join(",", keys) + ") VALUES (" + parameters + ")";
        if ("mysql".equals(context.getDatabaseId())) {
            String changes = columns.stream().filter(column -> !keys.contains(column))
                    .map(column -> column + "=incoming." + column).collect(Collectors.joining(","));
            return "INSERT INTO " + table + " (" + names + ") VALUES (" + parameters + ") AS incoming ON DUPLICATE KEY UPDATE " + changes;
        }
        throw new IllegalStateException("Unsupported database: " + context.getDatabaseId());
    }
}
