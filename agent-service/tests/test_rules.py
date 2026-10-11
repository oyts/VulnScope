import unittest

from app.main import choose_tool, explain_flow, explain_maven, explain_overview, explain_severity, explain_trend, llm_configured


class OfflineRuleTests(unittest.TestCase):
    def test_default_local_configuration_does_not_claim_model_use(self):
        self.assertFalse(llm_configured())

    def test_question_routes_to_expected_tool(self):
        self.assertEqual(choose_tool("当前 Maven 高风险组件是什么？")[0], "maven_risk")
        self.assertEqual(choose_tool("严重度中 CRITICAL 有多少？")[0], "severity")
        self.assertEqual(choose_tool("漏洞发布时间趋势如何？")[0], "trend")
        self.assertEqual(choose_tool("系统总体情况")[0], "overview")

    def test_overview_uses_backend_values(self):
        text, evidence = explain_overview({
            "vulnerabilities": 7065,
            "mavenPackages": 3264,
            "knownExploited": 53,
            "successfulTasks": 1,
        })
        self.assertIn("7065", text)
        self.assertIn("3264", text)
        self.assertEqual(evidence[0]["knownExploited"], 53)

    def test_severity_and_maven_evidence_are_limited(self):
        severity_text, severity_evidence = explain_severity([
            {"severity": "MEDIUM", "vuln_count": 100},
            {"severity": "CRITICAL", "vuln_count": 20},
        ])
        maven_text, maven_evidence = explain_maven([
            {"package_name": "example:component", "vuln_count": 8, "risk_score": 42.5},
        ])
        self.assertIn("20", severity_text)
        self.assertIn("example:component", maven_text)
        self.assertLessEqual(len(severity_evidence), 5)
        self.assertLessEqual(len(maven_evidence), 5)

    def test_trend_and_flow_use_spark_result_field_names(self):
        trend_text, _ = explain_trend([
            {"stat_month": "2024-01-01", "total_count": 12},
            {"stat_month": "2024-02-01", "total_count": 30},
        ])
        flow_text, _ = explain_flow([
            {"source_node": "org.example", "target_node": "CWE-79", "flow_value": 15},
        ])
        self.assertIn("30", trend_text)
        self.assertIn("org.example", flow_text)
        self.assertIn("CWE-79", flow_text)


if __name__ == "__main__":
    unittest.main()
