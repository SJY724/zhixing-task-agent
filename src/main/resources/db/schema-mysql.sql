-- Application tables. Initialization never removes existing rows.
CREATE TABLE IF NOT EXISTS agent_tasks (
    task_id VARCHAR(64) PRIMARY KEY,
    snapshot LONGTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS agent_task_events (
    task_id VARCHAR(64) NOT NULL,
    event_sequence INT NOT NULL,
    trace_id VARCHAR(64),
    stage VARCHAR(64),
    occurred_at VARCHAR(64),
    message LONGTEXT,
    PRIMARY KEY(task_id, event_sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS agent_deleted_tasks (
    task_id VARCHAR(64) PRIMARY KEY,
    deleted_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS agent_logs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    occurred_at VARCHAR(64),
    task_id VARCHAR(64),
    trace_id VARCHAR(64),
    stage VARCHAR(64),
    message LONGTEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS knowledge_documents (
    id VARCHAR(80) PRIMARY KEY,
    name VARCHAR(240),
    content LONGTEXT,
    created_at VARCHAR(40),
    chunk_count INT,
    status VARCHAR(24),
    error_message VARCHAR(1000),
    vector_ids LONGTEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS knowledge_settings (
    setting_key VARCHAR(80) PRIMARY KEY,
    setting_value LONGTEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
