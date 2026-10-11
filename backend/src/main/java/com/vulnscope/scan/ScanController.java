package com.vulnscope.scan;

import com.vulnscope.common.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/scans")
public class ScanController {
    private final JdbcTemplate jdbc;

    public ScanController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostMapping(value="/pom", consumes="multipart/form-data")
    public ApiResponse<Map<String,Object>> scan(@RequestPart("file") MultipartFile file, Authentication authentication) throws Exception {
        if (file.isEmpty() || file.getSize() > 2 * 1024 * 1024) throw new IllegalArgumentException("请选择不超过2MB的pom.xml");
        Document document = parseSecurely(file);
        Map<String,String> properties = properties(document);
        String projectName = firstText(document, "/*[local-name()='project']/*[local-name()='name']");
        if (projectName == null) projectName = firstText(document, "/*[local-name()='project']/*[local-name()='artifactId']");
        if (projectName == null) projectName = "未命名Maven项目";

        List<Dependency> dependencies = dependencies(document, properties);
        List<Map<String,Object>> findings = new ArrayList<>();
        int riskDependencies = 0;
        String highest = "NONE";
        for (Dependency dependency : dependencies) {
            List<Map<String,Object>> rows = jdbc.queryForList(
                    "SELECT COUNT(DISTINCT pv.vuln_id) vulnerability_count,MAX(v.cvss_score) max_cvss," +
                    "MAX(CASE v.severity WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE 0 END) severity_rank " +
                    "FROM package_vulnerability pv LEFT JOIN vulnerability v ON v.vuln_id=pv.vuln_id WHERE pv.group_id=? AND pv.artifact_id=?",
                    dependency.groupId(), dependency.artifactId());
            Map<String,Object> metric = rows.getFirst();
            long count = ((Number) metric.get("vulnerability_count")).longValue();
            int rank = metric.get("severity_rank") == null ? 0 : ((Number) metric.get("severity_rank")).intValue();
            if (count > 0) riskDependencies++;
            highest = higher(highest, severity(rank));
            Map<String,Object> finding = new LinkedHashMap<>();
            finding.put("groupId", dependency.groupId());
            finding.put("artifactId", dependency.artifactId());
            finding.put("version", dependency.version());
            finding.put("vulnerabilityCount", count);
            finding.put("maxCvss", metric.get("max_cvss"));
            finding.put("severity", severity(rank));
            findings.add(finding);
        }

        long userId = currentUserId(authentication);
        long scanId = insertScan(userId, projectName, file.getOriginalFilename(), dependencies.size(), riskDependencies, highest);
        for (Map<String,Object> finding : findings) {
            jdbc.update("INSERT INTO scan_dependency(scan_id,group_id,artifact_id,version,vulnerability_count,max_cvss_score) VALUES(?,?,?,?,?,?)",
                    scanId, finding.get("groupId"), finding.get("artifactId"), finding.get("version"),
                    finding.get("vulnerabilityCount"), finding.get("maxCvss"));
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("scanId", scanId); result.put("projectName", projectName);
        result.put("totalDependencies", dependencies.size()); result.put("riskDependencies", riskDependencies);
        result.put("highestSeverity", highest); result.put("dependencies", findings);
        return ApiResponse.ok(result);
    }

    @GetMapping("/mine")
    public ApiResponse<List<Map<String,Object>>> mine(Authentication authentication) {
        return ApiResponse.ok(jdbc.queryForList("SELECT * FROM dependency_scan WHERE user_id=? ORDER BY created_at DESC", currentUserId(authentication)));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String,Object>> detail(@PathVariable long id, Authentication authentication) {
        long userId = currentUserId(authentication);
        boolean admin = authentication.getAuthorities().stream().anyMatch(item -> item.getAuthority().equals("ROLE_ADMIN"));
        List<Map<String,Object>> rows = admin
                ? jdbc.queryForList("SELECT * FROM dependency_scan WHERE id=?", id)
                : jdbc.queryForList("SELECT * FROM dependency_scan WHERE id=? AND user_id=?", id, userId);
        if (rows.isEmpty()) throw new IllegalArgumentException("扫描记录不存在");
        Map<String,Object> result = new LinkedHashMap<>(rows.getFirst());
        result.put("dependencies", jdbc.queryForList("SELECT * FROM scan_dependency WHERE scan_id=? ORDER BY vulnerability_count DESC", id));
        return ApiResponse.ok(result);
    }

    private Document parseSecurely(MultipartFile file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(file.getInputStream());
    }

    private Map<String,String> properties(Document document) throws Exception {
        Map<String,String> values = new LinkedHashMap<>();
        NodeList nodes = (NodeList) XPathFactory.newInstance().newXPath().evaluate(
                "/*[local-name()='project']/*[local-name()='properties']/*", document, XPathConstants.NODESET);
        for (int i=0; i<nodes.getLength(); i++) values.put(nodes.item(i).getLocalName(), nodes.item(i).getTextContent().trim());
        return values;
    }

    private List<Dependency> dependencies(Document document, Map<String,String> properties) throws Exception {
        NodeList nodes = (NodeList) XPathFactory.newInstance().newXPath().evaluate(
                "/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency']", document, XPathConstants.NODESET);
        List<Dependency> result = new ArrayList<>();
        for (int i=0; i<nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String groupId = child(element, "groupId");
            String artifactId = child(element, "artifactId");
            String version = resolve(child(element, "version"), properties);
            if (groupId != null && artifactId != null) result.add(new Dependency(groupId, artifactId, version));
        }
        return result;
    }

    private String child(Element element, String name) {
        NodeList children = element.getChildNodes();
        for (int i=0; i<children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getLocalName())) return node.getTextContent().trim();
        }
        return null;
    }

    private String firstText(Document document, String expression) throws Exception {
        String value = XPathFactory.newInstance().newXPath().evaluate(expression, document).trim();
        return value.isEmpty() ? null : value;
    }

    private String resolve(String value, Map<String,String> properties) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) return properties.getOrDefault(value.substring(2, value.length()-1), value);
        return value;
    }

    private long insertScan(long userId, String projectName, String fileName, int total, int risk, String highest) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO dependency_scan(user_id,project_name,file_name,total_dependencies,risk_dependencies,highest_severity) VALUES(?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId); statement.setString(2, projectName); statement.setString(3, fileName);
            statement.setInt(4, total); statement.setInt(5, risk); statement.setString(6, highest);
            return statement;
        }, holder);
        return holder.getKey().longValue();
    }

    @SuppressWarnings("unchecked")
    private long currentUserId(Authentication authentication) {
        return ((Number) ((Map<String,Object>) authentication.getDetails()).get("id")).longValue();
    }

    private String severity(int rank) { return switch(rank) { case 4 -> "CRITICAL"; case 3 -> "HIGH"; case 2 -> "MEDIUM"; case 1 -> "LOW"; default -> "NONE"; }; }
    private String higher(String left, String right) { return rank(right) > rank(left) ? right : left; }
    private int rank(String value) { return switch(value) { case "CRITICAL" -> 4; case "HIGH" -> 3; case "MEDIUM" -> 2; case "LOW" -> 1; default -> 0; }; }
    private record Dependency(String groupId, String artifactId, String version) {}
}
