# 知行 企业知识工作台

一个 Java 与 Angular 的学习实践项目：上传制度文档，检索相关依据，经人工确认后生成带来源的回答。项目重点是把知识检索、异步任务、人工确认和持久化组织成可查看、可测试的完整流程。

## 技术栈

- 后端：Java 17、Spring Boot 3.2.5、MyBatis-Plus 3.5.18、MySQL、H2。
- AI 应用：Spring AI 1.0.0-M4、通义千问 OpenAI 兼容接口、Apache Tika、内存向量检索与本地向量快照。
- 前端：Angular 21.2、TypeScript、signals、HttpClient。
- 开发工具：Maven、pnpm、Git。Redis 未接入。

## 主要功能

- 上传 TXT、Markdown、PDF、DOCX，单文件上限10MB，查看文档索引状态并重建索引。
- 按指定文档检索知识；也可只使用本次任务的补充文本。
- 解析与检索后暂停，核对依据并批准后才生成最终回答；支持拒绝、取消和关联重试。
- 保存任务、事件、日志、知识目录与审批截止时间，重启后恢复历史记录。
- 支持任务筛选、批量移入最近删除、恢复及确认后永久删除。
- 使用 traceId、任务事件与 AOP 耗时日志追踪任务；暂时性错误按指数退避策略重试。

## 快速启动

需要 JDK 17 和 Maven 3.9。项目包含配套前端静态资源，运行后端即可打开工作台；只有修改前端时才需要 Node.js 24 与 pnpm 11。

在项目根目录运行：

```powershell
mvn test
mvn package -DskipTests
java -jar target/task-agent-1.0.0-SNAPSHOT.jar
```

浏览器打开 http://127.0.0.1:8080/ 。默认开发配置使用 H2，运行数据生成在 `data/`，该目录不提交到仓库。没有模型密钥时可以检查页面和服务状态，实际索引及问答需要有效模型配置。

## 模型配置

在启动应用的终端设置环境变量。以下是 PowerShell 示例：

```powershell
$env:DASHSCOPE_API_KEY = '填写自己的模型密钥'
$env:SPRING_AI_OPENAI_BASE_URL = 'https://dashscope.aliyuncs.com/compatible-mode'
java -jar target/task-agent-1.0.0-SNAPSHOT.jar
```

接入地址需要匹配账号的业务空间，base URL 不带末尾 `/v1`。首次索引、上传、重建及生成会调用相应模型并消耗额度。密钥不放入源码、前端或截图。`.env.example` 仅展示变量名称，应用不会自动读取 `.env`。

## 使用 MySQL

创建专用数据库和应用账号，不使用生产数据库运行演示或测试。然后设置：

```powershell
$env:TASK_AGENT_DB_URL = 'jdbc:mysql://127.0.0.1:3306/zhixing?characterEncoding=UTF-8&connectionTimeZone=Asia/Shanghai'
$env:TASK_AGENT_DB_USERNAME = '填写自己的数据库账号'
$env:TASK_AGENT_DB_PASSWORD = '填写自己的数据库密码'
java -jar target/task-agent-1.0.0-SNAPSHOT.jar --spring.profiles.active=agent-mysql
```

应用按独立 SQL 脚本初始化6张业务表，业务数据由 MyBatis-Plus/MyBatis Mapper 读写。MySQL 连接失败时不会自动回退 H2。H2 和 MySQL 使用各自明确选择的数据库；切换数据库不等于自动复制数据。数据库及向量快照的备份方法见 [持久化设计](docs/PERSISTENCE.md)。

## 前端开发

```powershell
cd frontend
pnpm install --frozen-lockfile
pnpm test
pnpm build
pnpm publish:local
```

`publish:local` 将成功构建的前端同步到后端静态资源目录。随后回到项目根目录重新打包后端。开发服务器、目录说明见 [前端说明](frontend/README.md)。

## 测试与代码阅读

- [架构与任务流程](docs/ARCHITECTURE.md)
- [MyBatis-Plus 持久化设计](docs/PERSISTENCE.md)
- [测试方法与范围](docs/TESTING.md)
- `examples/设备借用制度.txt`：用于上传演示的虚构制度资料。

后端测试中的模型使用替身，不依赖个人云端密钥；MySQL 兼容测试需显式配置独立空测试库。真实模型回答质量与稳定性需要另行验收，不能由自动测试通过推导。

## 适用边界

这是本机单用户学习实践应用，默认只监听本机地址。当前没有登录、多人权限、多租户或生产高可用能力，不能直接暴露到公网。人工确认表示核对检索依据并允许继续生成，不是连接了真实 OA、报销或采购系统。模型与上传文档内容均可能有误，应核对引用来源。
