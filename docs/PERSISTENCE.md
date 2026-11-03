# MyBatis Plus 持久化设计

业务读写使用 MyBatis-Plus 3.5.18 与 MyBatis Mapper，支持 MySQL 和 H2 两种明确选择的配置。

## 表与实体

- `agent_tasks`：任务 JSON 快照。
- `agent_task_events`：按任务编号和事件序号保存的事件，使用复合主键。
- `agent_deleted_tasks`：删除标记与时间，支持最近删除和恢复。
- `agent_logs`：追加的任务日志及 traceId。
- `knowledge_documents`：名称、提取文本、索引状态和向量编号。
- `knowledge_settings`：目录生命周期和索引快照校验设置。

5个 Mapper 继承 `BaseMapper`，使用实体和 `QueryWrapper`、`UpdateWrapper` 完成通用 CRUD；事件 Mapper 显式处理复合主键。原子 upsert、回收站关联查询和行锁使用参数绑定的自定义 Mapper SQL。

## 事务

`TaskStateRepository.save` 将任务快照与结构化事件放在同一 Spring 事务中。事件写入失败时回滚快照，避免只保存一半。日志及第三方模型调用不属于这个原子事务，数据库回滚不能撤销外部模型请求。

永久删除先锁定回收站标记，再在同一事务清理任务、事件、关联日志与删除标记，防止与恢复操作交叉。普通列表中的任务不能直接永久删除；其他任务和知识文档不会随之删除。

知识文档索引会显式写入状态、分块数量、向量编号和错误信息；重新索引成功时显式清空错误字段。知识目录与向量快照使用脏标记和摘要检测中断或不一致。

## 初始化与数据库差异

表结构定义在 `src/main/resources/db/schema-h2.sql` 和 `schema-mysql.sql`。`DatabaseSchema` 根据数据源实际类型选择脚本，并通过 Spring 的 SQL 脚本初始化工具执行。脚本只使用 `CREATE TABLE IF NOT EXISTS`，不清空用户数据。业务读写统一由实体与 Mapper 执行。

测试通过独立 SQL 准备记录并核对数据库结果，避免只用同一套 Mapper 同时写入和验证而遗漏映射问题。

`MybatisPersistenceConfiguration` 配置数据库 ID。MySQL 的幂等写入使用 `ON DUPLICATE KEY UPDATE`，H2 使用 `MERGE`；表类型和自增列由数据库方言选择。参数值通过绑定传入，不把用户输入拼接进 SQL。

## 运行和备份

默认 H2 文件与向量快照生成在应用工作目录的 `data/`。MySQL 连接由环境变量配置，连接失败不会回退 H2。不要让两个应用实例同时使用相同的数据文件目录。

备份时先停止应用；H2 文件应在连接关闭后复制。MySQL 使用该数据库支持的备份方式，并同时保留应用的向量快照；不要在线复制零散 InnoDB 文件。凭据、数据库文件和上传的私人资料不得提交到 Git。

任务主要字段保存在 JSON 快照中，目前没有实现全部字段的数据库级分页与索引查询，也没有多实例任务调度或高可用保证。
