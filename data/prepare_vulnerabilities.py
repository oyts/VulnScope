#!/usr/bin/env python3
"""Normalize public vulnerability records; all aggregations remain in Spark."""

import argparse
import csv
import gzip
import json
import zipfile
from datetime import datetime
from pathlib import Path


def args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--osv", type=Path, required=True)
    parser.add_argument("--nvd", type=Path, action="append", default=[])
    parser.add_argument("--cisa", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def date(value):
    if not value:
        return None
    value = str(value).replace("Z", "+00:00")
    try:
        return datetime.fromisoformat(value).strftime("%Y-%m-%d %H:%M:%S")
    except ValueError:
        return value[:19].replace("T", " ")


def first_english(items):
    for item in items or []:
        if item.get("lang") == "en" and item.get("value"):
            return item["value"]
    return (items or [{}])[0].get("value")


def metric(cve):
    metrics = cve.get("metrics") or {}
    for key in ("cvssMetricV31", "cvssMetricV30", "cvssMetricV2"):
        if metrics.get(key):
            value = metrics[key][0].get("cvssData") or {}
            score = value.get("baseScore")
            return (float(score) if score is not None else None,
                    str(value.get("baseSeverity") or "UNKNOWN").upper())
    return None, "UNKNOWN"


def load_nvd(paths):
    result = {}
    for path in paths:
        with gzip.open(path, "rt", encoding="utf-8") as handle:
            payload = json.load(handle)
        for wrapper in payload.get("vulnerabilities", []):
            cve = wrapper.get("cve") or {}
            cve_id = cve.get("id")
            if not cve_id:
                continue
            score, severity = metric(cve)
            weaknesses = cve.get("weaknesses") or []
            cwe = first_english(weaknesses[0].get("description")) if weaknesses else None
            result[cve_id] = {
                "published_at": date(cve.get("published")),
                "modified_at": date(cve.get("lastModified")),
                "severity": severity,
                "cvss_score": score,
                "cwe_id": cwe,
                "cwe_name": cwe,
                "reference_count": len(cve.get("references") or []),
                "summary": first_english(cve.get("descriptions")),
            }
    return result


def load_kev(path):
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return {row["cveID"].strip() for row in csv.DictReader(handle) if row.get("cveID")}


def package_parts(name):
    return name.split(":", 1) if ":" in name else (None, name)


def affected_info(affected):
    versions = affected.get("versions") or []
    events = [event for item in affected.get("ranges") or [] for event in item.get("events") or []]
    fixes = [str(event["fixed"]) for event in events if event.get("fixed")]
    return max(len(versions), len(events)), (fixes[-1] if fixes else None), bool(fixes)


def rows(osv, nvd, kev):
    aliases = [str(value) for value in osv.get("aliases") or []]
    cve_id = next((value for value in aliases if value.startswith("CVE-")), None)
    info = nvd.get(cve_id, {})
    specific = osv.get("database_specific") or {}
    base_severity = str(specific.get("severity") or "UNKNOWN").upper()
    if base_severity == "MODERATE":
        base_severity = "MEDIUM"
    base_cwe = next(iter(specific.get("cwe_ids") or []), None)
    for affected in osv.get("affected") or []:
        package = affected.get("package") or {}
        if package.get("ecosystem") != "Maven" or not package.get("name"):
            continue
        name = str(package["name"])
        group_id, artifact_id = package_parts(name)
        version_count, fixed_version, fixed = affected_info(affected)
        yield {
            "vuln_id": str(osv.get("id") or cve_id),
            "cve_id": cve_id,
            "source_name": "OSV",
            "summary": info.get("summary") or osv.get("summary") or "",
            "published_at": info.get("published_at") or date(osv.get("published")),
            "modified_at": info.get("modified_at") or date(osv.get("modified")),
            "severity": info.get("severity") or base_severity,
            "cvss_score": info.get("cvss_score"),
            "cwe_id": info.get("cwe_id") or base_cwe,
            "cwe_name": info.get("cwe_name") or base_cwe,
            "ecosystem": "Maven",
            "group_id": group_id,
            "artifact_id": artifact_id,
            "package_name": name,
            "affected_version_count": version_count,
            "fixed_version": fixed_version,
            "fixed_available": fixed,
            "known_exploited": bool(cve_id and cve_id in kev),
            "reference_count": info.get("reference_count", len(osv.get("references") or [])),
        }


def main():
    options = args()
    nvd = load_nvd(options.nvd)
    kev = load_kev(options.cisa)
    options.output.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    unique = set()
    with zipfile.ZipFile(options.osv) as archive, options.output.open("w", encoding="utf-8", newline="\n") as output:
        for name in archive.namelist():
            if not name.endswith(".json"):
                continue
            with archive.open(name) as handle:
                osv = json.load(handle)
            for row in rows(osv, nvd, kev):
                output.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
                count += 1
                unique.add(row["vuln_id"])
    print(json.dumps({"rows": count, "unique_vulnerabilities": len(unique), "output": str(options.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
