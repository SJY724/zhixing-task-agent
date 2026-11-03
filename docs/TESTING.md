# 测试方法与范围

## 后端

```powershell
mvn test
mvn verify
```

默认测试使用隔离 H2 和模型替身，不需要真实模型密钥。覆盖参数校验、文档和检索范围、状态转换、重试分类、人工确认并发、持久化恢复、事务回滚、日志脱敏和删除恢复。

`MybatisApplicationIntegrationTest` 启动真实 Spring Boot 上下文，验证 Mapper 扫描与事务；`TaskHistoryCompatibilityTest` 使用独立准备的数据库记录检查快照、审批截止时间与回收站读取。

## 可选 MySQL 兼容测试

先准备专用空测试库和仅能访问该库的账号，不使用日常演示库或生产库。设置以下变量后执行 `mvn verify`：

```powershell
$env:TASK_AGENT_TEST_MYSQL_URL = 'jdbc:mysql://127.0.0.1:3306/zhixing_test?characterEncoding=UTF-8&connectionTimeZone=Asia/Shanghai'
$env:TASK_AGENT_TEST_MYSQL_USERNAME = '填写测试账号'
$env:TASK_AGENT_TEST_MYSQL_PASSWORD = '填写测试密码'
mvn verify
```

兼容测试会创建、更新和清理测试记录，并检查真实数据库方言、中文长文本、幂等保存、事务、知识目录和删除恢复。H2 测试通过不代表 MySQL 已通过。

## 前端

```powershell
cd frontend
pnpm install --frozen-lockfile
pnpm test
pnpm build
```

前端测试验证受控 Markdown、任务阶段展示、文档范围请求与批量选择删除行为。构建通过不能代替所有浏览器上的人工界面检查。

## 人工检查

上传 `examples/设备借用制度.txt`，等待索引就绪，选择该文档提问，核对来源后批准，检查回答和日志。再检查拒绝、取消、关联重试、删除恢复与重启后的历史记录。

上传、重建索引及批准后的生成会消耗模型用量；健康接口正常不代表模型权限、额度和网络可用。真实模型质量和生产性能未由这些自动测试评估，不声明准确率或并发承载指标。

## 已执行的验证

2026年11月3日，从本目录源码执行：

- 默认 H2 后端测试：63项通过，0失败、0错误、0跳过。
- 加入独立 MySQL 兼容测试后：69项通过，默认打包通过。
- 前端全新安装依赖、11项测试和生产构建通过，构建结果已同步至后端静态资源。
- 已打包应用通过15项流程检查，覆盖上传索引、生成前人工确认、日志、进程重启恢复、删除恢复与关联记录清理。

流程检查使用本机 OpenAI 兼容模型替身，不调用真实通义千问。以上结果验证本次测试环境中的应用行为，不代表真实模型回答质量、生产性能或公开部署能力。
