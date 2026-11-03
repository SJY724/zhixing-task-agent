# 知行 Angular 工作台

Angular 21.2 standalone 应用，使用 TypeScript、signals、HttpClient 与 FormsModule。依赖版本由 `package.json` 和 `pnpm-lock.yaml` 固定。

## 开发与构建

需要 Node.js 24 与 pnpm 11。在项目根目录运行：

```powershell
cd frontend
pnpm install --frozen-lockfile
pnpm start
```

开发地址为 http://127.0.0.1:4200/ 。后端需要另外启动在8080端口，`proxy.conf.json` 将 `/api` 和 `/actuator` 请求转发到后端。

```powershell
pnpm test
pnpm build
pnpm publish:local
```

`publish:local` 只在存在成功构建产物时替换后端 `src/main/resources/static` 资源，并保留第三方许可清单。同步后需要重新打包后端。

## 代码结构

- `src/app/app.component.ts` 与对应模板：任务、人工确认、知识目录及页面交互。
- `src/app/workbench-api.service.ts`：HTTP 客户端、超时与错误处理。
- `src/app/models.ts`：接口数据类型。
- `src/app/safe-markdown.pipe.ts`：转义模型文本，再生成受控 Markdown，不执行任意 HTML。
- `src/app/task-selection.ts`：当前可见记录的批量选择和删除处理。
- `src/styles.css`：页面布局、键盘焦点及响应式样式。

页面不保存模型密钥，不依赖外部字体或 CDN。轮询只查询状态，不生成答案；提交、上传、索引重建和批准后的生成步骤会请求后端相应功能。
