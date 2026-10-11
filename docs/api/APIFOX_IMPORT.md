# VulnScope 接口文档与 Apifox 导入说明

## 1. 导入方式

项目已生成 OpenAPI 3.0.1 静态文件：`docs/api/openapi.json`。

在 Apifox 中选择“导入数据 → OpenAPI/Swagger → 文件”，选择该 JSON 文件即可导入。也可以在后端启动后导入地址：`http://127.0.0.1:19081/v3/api-docs`。

开发环境前端地址为 `http://127.0.0.1:5173`，后端固定使用 `19081`；前端代理目标由 `frontend/.env.local` 的 `VITE_API_TARGET` 配置。Agent 是独立 FastAPI 服务，默认使用 `19082`。

## 2. 鉴权约定

登录成功后，从响应 `data.token` 取得 JWT，在需要登录的请求中添加：

```text
Authorization: Bearer <token>
```

角色权限：`USER` 可查询漏洞、提交 Maven 依赖扫描并查看个人历史；`ADMIN` 额外拥有用户、数据源、分析任务和审计日志管理权限。

## 3. 接口清单

### 认证

- `POST /api/v1/auth/register`：注册普通用户。
- `POST /api/v1/auth/login`：用户名密码登录并返回 JWT。
- `GET /api/v1/auth/me`：读取当前登录用户信息。

### 漏洞与分析

- `GET /api/v1/vulnerabilities`：按关键词、严重度、CWE 分页查询漏洞。
- `GET /api/v1/vulnerabilities/{vulnId}`：查询漏洞详情及受影响组件。
- `GET /api/v1/analytics/overview`：读取总漏洞数、组件数和成功任务数。
- `GET /api/v1/analytics/all`：一次获取 D1–D6 六维结果。
- `GET /api/v1/analytics/trend`：D1 月度趋势。
- `GET /api/v1/analytics/severity`：D2 严重度分布。
- `GET /api/v1/analytics/cwe-top`：D3 CWE Top10。
- `GET /api/v1/analytics/maven-risk`：D4 Maven 组件风险。
- `GET /api/v1/analytics/risk-scatter`：D5 风险散点。
- `GET /api/v1/analytics/risk-flow`：D6 风险流向。

### 依赖扫描

- `POST /api/v1/scans/pom`：上传 `pom.xml`，扫描直接依赖风险。
- `GET /api/v1/scans/mine`：读取当前用户的扫描历史。
- `GET /api/v1/scans/{id}`：读取一次扫描详情。

### 管理员

以下接口要求 `ADMIN` 角色：

- `GET /api/v1/admin/users`：用户列表。
- `PATCH /api/v1/admin/users/{id}/status`：启用或停用普通用户。
- `GET /api/v1/admin/data-sources`：数据源状态。
- `GET /api/v1/admin/analysis-tasks`：Spark 分析任务及 YARN 应用号。
- `POST /api/v1/admin/analysis-tasks`：提交真实 Spark on YARN 复算任务；请求体 `{"dimensionCode":"ALL"}`，可选 `ALL` 或 `D1`～`D6`。
- `GET /api/v1/admin/analysis-tasks/{id}`：轮询任务阶段、进度、HDFS 路径、YARN 应用号与 JDBC 回写行数。
- `GET /api/v1/admin/audit-logs`：审计日志。

### Agent（独立服务）

- `GET http://127.0.0.1:19082/api/v1/agent/capabilities`：读取离线/大模型模式及可查询维度。
- `POST http://127.0.0.1:19082/api/v1/agent/ask`：请求体 `{"question":"当前 Maven 高风险组件是什么？"}`，返回工具来源与证据。

Agent 的 OpenAPI 已保存为 `docs/api/agent-openapi.json`；也可在服务启动后从 `http://127.0.0.1:19082/openapi.json` 单独导入 Apifox。

## 4. Apifox 推荐环境变量

```text
baseUrl = http://127.0.0.1:19081
jwtToken = 登录接口返回的 data.token
```

在 Apifox 项目中将鉴权配置为 Bearer Token，并将 Token 值绑定到 `{{jwtToken}}`。

## 5. 最小验收顺序

1. 注册一个普通用户并登录。
2. 使用普通用户调用 `/auth/me`、`/analytics/all` 和 `/scans/mine`。
3. 使用普通用户调用 `/admin/analysis-tasks`，应返回 403。
4. 使用管理员登录，调用 `POST /admin/analysis-tasks` 提交 `ALL`，轮询详情直至拿到真实 `application_*` 且状态为 `SUCCEEDED`。
5. 在 `/analytics/all` 中确认六组结果分别为 D1=100、D2=5、D3=10、D4=30、D5=300、D6=80，并确认页面图表已刷新。
