# 架构与任务流程

应用由 Angular 工作台与 Spring Boot 后端组成。前端生产资源由后端同一端口提供，HTTP API 使用 `/api/agent` 前缀。后端代码位于 `com.agent.springaitaskagent`。

## 任务处理

1. `AgentController` 校验问题、文档范围和补充文本，交给 `TaskHarness` 创建任务及 traceId。
2. 有界线程池执行文档解析与知识检索，保存任务事件和检索依据。
3. 任务进入 `AWAITING_CONFIRMATION`，此时尚未生成回答，等待期间不占用工作线程。
4. 批准后调度结果生成；拒绝、取消或超时终止任务。
5. 最终回答、状态、事件与日志由查询接口返回，前端展示来源与进度。

选择文档与本次补充文本互斥。补充文本只用于当前任务，不加入共享知识目录。公共接口不接受任意本机文件路径，文档通过上传接口导入。

## 主要代码

- `controller/`：请求校验与接口返回；`controller/dto/` 定义请求字段。
- `harness/TaskHarness`：任务锁、线程池、状态转换、确认、取消、重试与启动恢复。
- `harness/TaskState`：任务快照、审批截止时间和有序事件。
- `skill/DocumentParseSkill`：Tika/UTF-8 解析与重叠切块。
- `rag/KnowledgeService`：知识目录、索引状态、范围校验和快照一致性。
- `rag/VectorStoreService`：向量生成、检索、选定文档范围与本地索引快照。
- `skill/InformationRetrieveSkill`、`ResultGenerateSkill`：组织检索上下文并生成回答。
- `persistence/entity/`、`persistence/mapper/`：实体映射、通用 CRUD 和明确的自定义 SQL。
- `common/RetryExecutor`、`TraceIdFilter`、`TaskLogService` 与 `aspect/TaskLogAspect`：重试、追踪、日志与耗时。

## 状态与并发

任务状态包括 `PENDING`、`RUNNING`、`RETRYING`、`AWAITING_CONFIRMATION`、`SUCCEEDED`、`FAILED` 和 `CANCELLED`。状态表示任务处境，阶段与事件表示具体执行步骤。

确认时在任务锁内认领决定，再调度生成，防止重复批准产生重复执行。线程池与队列均有容量上限。取消后不接受迟到的回答，但无法撤销已经发送给第三方的请求。

默认最多四次调用尝试，即首次调用加三次指数退避重试；仅处理分类为暂时性的错误。页面的手动重试会创建带 `parentTaskId` 的新任务，并保留原记录。

## 持久化与恢复

业务数据通过 MyBatis-Plus/MyBatis Mapper 读写 MySQL 或明确选择的 H2。任务快照与事件使用同一事务；日志独立追加。向量快照保存在本地文件，关系数据库不承担向量搜索。

启动时恢复历史任务。等待确认的任务继续使用保存的截止时间；执行中断的任务明确失败，不自动重发付费模型请求。向量快照同时核对知识目录、文件摘要、模型身份与分块配置；不一致时恢复索引或提示显式重建。

## 边界

人工确认展示流程控制和人工核对，不代表已执行真实企业审批。来源引用不能保证模型回答正确。应用是本机单用户实现，没有分布式任务认领、跨实例锁、登录或多租户权限。
