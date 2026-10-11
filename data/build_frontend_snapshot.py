#!/usr/bin/env python3
"""Build a tracked frontend snapshot from real HDFS result JSONL files."""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "data" / "processed" / "hdfs-results"
TARGET = ROOT / "frontend" / "public" / "data" / "analytics.json"
MAPPING = {
    "trend": "d1.jsonl",
    "severity": "d2.jsonl",
    "cweTop": "d3.jsonl",
    "mavenRisk": "d4.jsonl",
    "riskScatter": "d5.jsonl",
    "riskFlow": "d6.jsonl",
}

payload = {}
for key, filename in MAPPING.items():
    with (SOURCE / filename).open("r", encoding="utf-8") as handle:
        payload[key] = [json.loads(line) for line in handle if line.strip()]

TARGET.parent.mkdir(parents=True, exist_ok=True)
TARGET.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
print(f"snapshot={TARGET} rows={sum(map(len, payload.values()))}")
