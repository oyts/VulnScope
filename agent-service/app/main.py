"""Offline, evidence-first Agent for VulnScope.

This service deliberately does not imitate an LLM when no model key is
configured. It selects a bounded analytics tool, reads the real Spring Boot
response, and produces a deterministic Chinese explanation with evidence.
"""

from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from dotenv import load_dotenv
from pydantic import BaseModel, Field


load_dotenv(Path(__file__).resolve().parents[1] / ".env")
BACKEND_URL = os.getenv("VULNSCOPE_BACKEND_URL", "http://127.0.0.1:19081").rstrip("/")
REQUEST_TIMEOUT_SECONDS = 6
LLM_MODE = os.getenv("VULNSCOPE_LLM_MODE", "auto").strip().lower()
LLM_API_STYLE = os.getenv("VULNSCOPE_LLM_API_STYLE", "chat_completions").strip().lower()
LLM_BASE_URL = os.getenv("VULNSCOPE_LLM_BASE_URL", "").strip().rstrip("/")
LLM_API_KEY = os.getenv("VULNSCOPE_LLM_API_KEY", "").strip()
LLM_MODEL = os.getenv("VULNSCOPE_LLM_MODEL", "").strip()
LLM_TIMEOUT_SECONDS = int(os.getenv("VULNSCOPE_LLM_TIMEOUT_SECONDS", "30"))

app = FastAPI(
    title="VulnScope Evidence-Grounded Security Agent",
    version="1.0.0",
    description="通过受控 Spring Boot 统计接口读取真实数据的离线解释 Agent。",
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type"],
)


class AskRequest(BaseModel):
    question: str = Field(min_length=1, max_length=500, description="关于系统总体风险或六维统计的问题")


class AgentAnswer(BaseModel):
    mode: str
    model_used: bool
    model_name: str | None = None
    answer: str
    tool_name: str
    source_endpoint: str
    queried_at: str
    evidence: list[dict[str, Any]]
    notice: str


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def backend_get(path: str) -> Any:
    request = Request(
        f"{BACKEND_URL}{path}",
        headers={"Accept": "application/json", "User-Agent": "VulnScope-Agent/1.0"},
        method="GET",
    )
    try:
        with urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            body = json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        raise HTTPException(status_code=502, detail=f"后端工具接口返回 HTTP {error.code}") from error
    except (URLError, TimeoutError) as error:
        raise HTTPException(status_code=503, detail="后端工具接口不可用，请确认 Spring Boot 已启动") from error
    except json.JSONDecodeError as error:
        raise HTTPException(status_code=502, detail="后端工具接口返回了非 JSON 内容") from error

    if body.get("code") != 0:
        raise HTTPException(status_code=502, detail=f"后端工具接口执行失败：{body.get('message', 'unknown')}")
    return body.get("data")


def llm_configured() -> bool:
    return LLM_MODE != "offline" and bool(LLM_API_KEY and LLM_BASE_URL and LLM_MODEL)


def post_llm(payload: dict[str, Any], endpoint: str) -> dict[str, Any]:
    request = Request(
        f"{LLM_BASE_URL}{endpoint}",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {LLM_API_KEY}",
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": "VulnScope-Agent/1.0",
        },
        method="POST",
    )
    try:
        with urlopen(request, timeout=LLM_TIMEOUT_SECONDS) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        raise HTTPException(status_code=502, detail=f"大模型接口返回 HTTP {error.code}") from error
    except (URLError, TimeoutError) as error:
        raise HTTPException(status_code=503, detail="大模型接口不可用或响应超时") from error
    except json.JSONDecodeError as error:
        raise HTTPException(status_code=502, detail="大模型接口返回了非 JSON 内容") from error


def grounded_llm_answer(question: str, offline_answer: str, evidence: list[dict[str, Any]], source: str) -> str:
    instructions = (
        "你是VulnScope的Java组件安全分析助手。只能使用给定工具证据回答，不能补造漏洞、版本、数量或修复建议。"
        "使用简洁中文，先给结论，再说明证据来源；证据不足时明确说明。"
    )
    content = (
        f"用户问题：{question}\n"
        f"离线规则结论：{offline_answer}\n"
        f"工具来源：{source}\n"
        f"工具证据：{json.dumps(evidence, ensure_ascii=False, default=str)}"
    )
    if LLM_API_STYLE == "responses":
        body = post_llm({"model": LLM_MODEL, "instructions": instructions, "input": content}, "/responses")
        if isinstance(body.get("output_text"), str) and body["output_text"].strip():
            return body["output_text"].strip()
        parts = []
        for item in body.get("output", []):
            for block in item.get("content", []):
                if block.get("type") == "output_text" and block.get("text"):
                    parts.append(block["text"])
        if parts:
            return "\n".join(parts).strip()
    elif LLM_API_STYLE == "chat_completions":
        body = post_llm({
            "model": LLM_MODEL,
            "messages": [
                {"role": "system", "content": instructions},
                {"role": "user", "content": content},
            ],
            "temperature": 0.1,
        }, "/chat/completions")
        choices = body.get("choices") or []
        if choices and isinstance(choices[0].get("message", {}).get("content"), str):
            return choices[0]["message"]["content"].strip()
    else:
        raise HTTPException(status_code=500, detail="VULNSCOPE_LLM_API_STYLE仅支持responses或chat_completions")
    raise HTTPException(status_code=502, detail="大模型接口没有返回可用文本")


def number(value: Any) -> float:
    try:
        return float(value or 0)
    except (TypeError, ValueError):
        return 0.0


def integer(value: Any) -> int:
    return int(round(number(value)))


def choose_tool(question: str) -> tuple[str, str]:
    text = question.lower()
    rules = [
        (("趋势", "月份", "时间", "发布"), "trend", "/api/v1/analytics/trend"),
        (("严重度", "高危", "危急", "critical", "severity"), "severity", "/api/v1/analytics/severity"),
        (("cwe", "弱点", "漏洞类型"), "cwe_top", "/api/v1/analytics/cwe-top"),
        (("maven", "组件", "依赖", "包"), "maven_risk", "/api/v1/analytics/maven-risk"),
        (("散点", "cvss", "版本", "引用"), "risk_scatter", "/api/v1/analytics/risk-scatter"),
        (("桑基", "流向", "生态", "传播"), "risk_flow", "/api/v1/analytics/risk-flow"),
    ]
    for keywords, name, endpoint in rules:
        if any(keyword in text for keyword in keywords):
            return name, endpoint
    return "overview", "/api/v1/analytics/overview"


def explain_overview(data: dict[str, Any]) -> tuple[str, list[dict[str, Any]]]:
    vulnerabilities = integer(data.get("vulnerabilities"))
    packages = integer(data.get("mavenPackages"))
    exploited = integer(data.get("knownExploited"))
    tasks = integer(data.get("successfulTasks"))
    answer = (
        f"当前漏洞目录有 {vulnerabilities} 条唯一漏洞，覆盖 {packages} 个 Maven 组件；"
        f"其中 {exploited} 条被标记为已在野利用。已成功完成 {tasks} 次 Spark on YARN 分析任务。"
        "建议优先结合组件风险榜和严重度分布，排查高危依赖。"
    )
    return answer, [data]


def explain_trend(rows: list[dict[str, Any]]) -> tuple[str, list[dict[str, Any]]]:
    def trend_count(item: dict[str, Any]) -> int:
        return integer(item.get("total_count", item.get("vuln_count")))

    ranked = sorted(rows, key=trend_count, reverse=True)
    peak = ranked[0] if ranked else {}
    latest = rows[-1] if rows else {}
    answer = (
        f"趋势统计覆盖 {len(rows)} 个时间点，峰值出现在 {peak.get('stat_month', '未知月份')}，"
        f"当月记录 {trend_count(peak)} 条漏洞；最新时间点 {latest.get('stat_month', '未知')} "
        f"记录 {trend_count(latest)} 条。趋势数据来自 Spark 回写的 D1 结果表。"
    )
    return answer, ranked[:5]


def explain_severity(rows: list[dict[str, Any]]) -> tuple[str, list[dict[str, Any]]]:
    ranked = sorted(rows, key=lambda item: number(item.get("vuln_count")), reverse=True)
    top = ranked[0] if ranked else {}
    critical = next((item for item in rows if str(item.get("severity", "")).upper() == "CRITICAL"), {})
    answer = (
        f"数量最多的严重度是 {top.get('severity', '未知')}，共有 {integer(top.get('vuln_count'))} 条。"
        f"其中 CRITICAL 级别为 {integer(critical.get('vuln_count'))} 条；"
        "该结论对应 D2 严重度分布，前端使用环形玫瑰图展示。"
    )
    return answer, ranked[:5]


def explain_cwe(rows: list[dict[str, Any]]) -> tuple[str, list[dict[str, Any]]]:
    top = rows[0] if rows else {}
    answer = (
        f"高频弱点首位是 {top.get('cwe_id', '未知')}，涉及 {integer(top.get('vuln_count'))} 条漏洞，"
        f"平均 CVSS 为 {number(top.get('avg_cvss')):.2f}。"
        "这是 Spark D3 Top10 聚合结果，可用于确定依赖审查的优先弱点类型。"
    )
    return answer, rows[:5]


def explain_maven(rows: list[dict[str, Any]]) -> tuple[str, list[dict[str, Any]]]:
    top = rows[0] if rows else {}
    package = top.get("package_name") or top.get("artifact_id") or "未知组件"
    answer = (
        f"组件风险榜首是 {package}，关联 {integer(top.get('vuln_count'))} 条漏洞，"
        f"风险分为 {number(top.get('risk_score')):.2f}。"
        "该结果来自 D4 Maven 高风险组件聚合，适合与 pom.xml 扫描结果交叉核验。"
    )
    return answer, rows[:5]


def explain_scatter(rows: list[dict[str, Any]]) -> tuple[str, list[dict[str, Any]]]:
    top = rows[0] if rows else {}
    answer = (
        f"散点分析返回 {len(rows)} 条风险样本，最高 CVSS 样本为 {top.get('vuln_id', '未知')}，"
        f"CVSS 为 {number(top.get('cvss_score')):.2f}。"
        "D5 同时编码 CVSS、受影响版本数和引用数量，不能单独用一个值代替风险判断。"
    )
    return answer, rows[:5]


def explain_flow(rows: list[dict[str, Any]]) -> tuple[str, list[dict[str, Any]]]:
    top = max(rows, key=lambda item: number(item.get("flow_value")), default={})
    answer = (
        f"最大风险流为 {top.get('source_node', top.get('source_name', top.get('source', '未知')))} → "
        f"{top.get('target_node', top.get('target_name', top.get('target', '未知')))}，"
        f"流量值为 {integer(top.get('flow_value'))}。"
        "该结论来自 D6 生态、CWE 与严重度的多层聚合，前端使用桑基图展示。"
    )
    return answer, rows[:8]


EXPLAINERS = {
    "overview": explain_overview,
    "trend": explain_trend,
    "severity": explain_severity,
    "cwe_top": explain_cwe,
    "maven_risk": explain_maven,
    "risk_scatter": explain_scatter,
    "risk_flow": explain_flow,
}


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "UP",
        "mode": "llm_grounded" if llm_configured() else "offline_rules",
        "backend": BACKEND_URL,
        "modelConfigured": llm_configured(),
        "model": LLM_MODEL or None,
    }


@app.get("/api/v1/agent/capabilities")
def capabilities() -> dict[str, Any]:
    return {
        "mode": "llm_grounded" if llm_configured() else "offline_rules",
        "modelUsed": llm_configured(),
        "modelConfigured": llm_configured(),
        "model": LLM_MODEL or None,
        "apiStyle": LLM_API_STYLE,
        "supported": ["总体概览", "漏洞趋势", "严重度", "CWE", "Maven 组件", "CVSS 散点", "风险流向"],
        "notice": "未配置模型密钥时，本服务只使用确定性规则解释真实后端统计结果。",
    }


@app.post("/api/v1/agent/ask", response_model=AgentAnswer)
def ask(payload: AskRequest) -> AgentAnswer:
    tool_name, endpoint = choose_tool(payload.question)
    data = backend_get(endpoint)
    offline_answer, evidence = EXPLAINERS[tool_name](data)
    answer = offline_answer
    mode = "offline_rules"
    model_used = False
    notice = "此回答由离线规则基于实时 Spring Boot 统计接口生成，不是大语言模型推理结果。"
    if llm_configured():
        try:
            answer = grounded_llm_answer(payload.question, offline_answer, evidence, endpoint)
            mode = "llm_grounded"
            model_used = True
            notice = "大模型仅解释下方工具证据；统计数值仍来自Spark回写后的Spring Boot接口。"
        except HTTPException:
            if LLM_MODE == "required":
                raise
            mode = "offline_fallback"
            notice = "大模型接口不可用，本次已自动回退为离线规则；证据仍来自真实后端接口。"
    return AgentAnswer(
        mode=mode,
        model_used=model_used,
        model_name=LLM_MODEL if model_used else None,
        answer=answer,
        tool_name=tool_name,
        source_endpoint=endpoint,
        queried_at=now_iso(),
        evidence=evidence,
        notice=notice,
    )
