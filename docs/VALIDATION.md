# Validation Record

The following results were recorded on 2026-09-07 in the original three-node course lab environment. Private addresses, student identifiers, and submission-only materials are intentionally omitted from the public repository.

## Spark and data pipeline

- YARN application final state: `FINISHED / SUCCEEDED`
- Public vulnerability input rows: 12,873
- MySQL output rows across six result tables: 525

| Dimension | Result table | Rows |
|---|---|---:|
| Monthly trend | `rpt_vuln_trend` | 100 |
| Severity distribution | `rpt_severity_distribution` | 5 |
| CWE Top 10 | `rpt_cwe_top` | 10 |
| Maven component risk | `rpt_maven_package_risk` | 30 |
| Risk scatter | `rpt_risk_scatter` | 300 |
| Risk flow | `rpt_risk_flow` | 80 |

## Application checks

- Spring Boot returned all six analysis groups from `/api/v1/analytics/all`.
- An administrator could submit and inspect Spark tasks; a normal user received HTTP 403 for `/api/v1/admin/**`.
- The Vue agent route and development proxy reached the Agent capability endpoint.
- Offline Agent mode called `/api/v1/analytics/maven-risk` and returned five evidence rows.
- Model-enhanced mode kept the same backend evidence set and reported that a model was used.
- Backend and Agent OpenAPI documents are included under `docs/api/`.

These are environment-specific acceptance results, not a claim that every deployment will produce identical values.
