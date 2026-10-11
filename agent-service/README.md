# VulnScope Offline Security Agent

这是一个可选加分服务：Python FastAPI 通过受控的 Spring Boot 公共统计接口读取真实 Spark 回写结果。未配置密钥时生成可追溯的离线解释；配置兼容接口后，大模型只负责解释已经取得的工具证据。

## 设计原则

- 未配置模型密钥时，响应固定标记为 `offline_rules` 和 `model_used=false`，不把模板文本伪装成大模型回答。
- Agent 只能读取六维统计接口，不能直接连接 MySQL、HDFS 或 YARN。
- 每个回答返回来源接口、查询时间和最多八条证据行，便于答辩时核验。
- 漏洞详情与用户扫描历史需要用户 JWT，因此不被此公共离线 Agent 代查。
- API Key 只写入 `agent-service/.env`，不能写入 Vue 环境变量、源码或 Git。

## 可选大模型配置

复制 `.env.example` 为 `.env`，然后填写：

```properties
VULNSCOPE_LLM_MODE=auto
VULNSCOPE_LLM_API_STYLE=chat_completions
VULNSCOPE_LLM_BASE_URL=https://你的兼容服务地址/v1
VULNSCOPE_LLM_API_KEY=你的密钥
VULNSCOPE_LLM_MODEL=你的模型名称
```

`chat_completions` 适用于常见兼容服务；使用 OpenAI Responses API 时将 `VULNSCOPE_LLM_API_STYLE` 设为 `responses`。`auto` 模式在接口失败时回退到离线规则；`required` 模式会直接报告模型接口错误；`offline` 强制禁用模型。

## 启动

先启动 Spring Boot 后端（默认 `http://127.0.0.1:19081`），然后在本目录执行：

```powershell
python -m pip install -r requirements.txt
$env:VULNSCOPE_BACKEND_URL = "http://127.0.0.1:19081"
python -m uvicorn app.main:app --host 127.0.0.1 --port 19082
```

接口文档：`http://127.0.0.1:19082/docs`。

## 验证示例

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:19082/api/v1/agent/ask `
  -ContentType 'application/json' -Body '{"question":"当前 Maven 高风险组件是什么？"}'
```

预期结果中应出现 `mode: offline_rules`、`model_used: false`、`source_endpoint: /api/v1/analytics/maven-risk`，且证据来自后端实际返回的数据。
