CREATE DATABASE IF NOT EXISTS vulnscope
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE vulnscope;

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(50) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER',
    status TINYINT NOT NULL DEFAULT 1,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_user_role CHECK (role IN ('ADMIN', 'USER')),
    CONSTRAINT chk_user_status CHECK (status IN (0, 1))
) ENGINE=InnoDB COMMENT='系统用户表';

CREATE TABLE IF NOT EXISTS data_source (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_code VARCHAR(30) NOT NULL UNIQUE,
    source_name VARCHAR(100) NOT NULL,
    source_url VARCHAR(500) NOT NULL,
    hdfs_path VARCHAR(500),
    record_count BIGINT NOT NULL DEFAULT 0,
    checksum_sha256 VARCHAR(64),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    last_sync_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_source_status CHECK (status IN ('PENDING', 'READY', 'FAILED'))
) ENGINE=InnoDB COMMENT='公开漏洞数据源表';

CREATE TABLE IF NOT EXISTS vulnerability (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    vuln_id VARCHAR(60) NOT NULL UNIQUE,
    source_name VARCHAR(30) NOT NULL,
    summary VARCHAR(1000),
    published_at DATETIME,
    modified_at DATETIME,
    severity VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    cvss_score DECIMAL(4,1),
    cwe_id VARCHAR(30),
    cwe_name VARCHAR(255),
    known_exploited TINYINT NOT NULL DEFAULT 0,
    fixed_available TINYINT NOT NULL DEFAULT 0,
    reference_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_vulnerability_severity (severity),
    INDEX idx_vulnerability_cwe (cwe_id),
    INDEX idx_vulnerability_published (published_at)
) ENGINE=InnoDB COMMENT='可检索漏洞主表';

CREATE TABLE IF NOT EXISTS package_vulnerability (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    vuln_id VARCHAR(60) NOT NULL,
    ecosystem VARCHAR(30) NOT NULL,
    group_id VARCHAR(255),
    artifact_id VARCHAR(255),
    package_name VARCHAR(500) NOT NULL,
    affected_versions INT NOT NULL DEFAULT 0,
    fixed_version VARCHAR(255),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_package_vulnerability (vuln_id, ecosystem, package_name(180)),
    INDEX idx_package_coordinate (group_id, artifact_id),
    INDEX idx_package_vuln_id (vuln_id)
) ENGINE=InnoDB COMMENT='组件与漏洞关系表';

CREATE TABLE IF NOT EXISTS analysis_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_no VARCHAR(40) NOT NULL UNIQUE,
    dimension_code VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    stage VARCHAR(40) NOT NULL DEFAULT 'QUEUED',
    progress INT NOT NULL DEFAULT 0,
    hdfs_input_path VARCHAR(500) NOT NULL,
    hdfs_output_path VARCHAR(500),
    application_id VARCHAR(80),
    source_rows BIGINT NOT NULL DEFAULT 0,
    result_rows INT NOT NULL DEFAULT 0,
    error_message TEXT,
    log_excerpt TEXT,
    submitted_by BIGINT,
    started_at DATETIME,
    finished_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_task_dimension CHECK (dimension_code IN ('ALL','D1','D2','D3','D4','D5','D6')),
    CONSTRAINT chk_task_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    CONSTRAINT chk_task_progress CHECK (progress BETWEEN 0 AND 100),
    INDEX idx_task_status (status),
    INDEX idx_task_created (created_at)
) ENGINE=InnoDB COMMENT='Spark分析任务表';

CREATE TABLE IF NOT EXISTS dependency_scan (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    project_name VARCHAR(120) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    total_dependencies INT NOT NULL DEFAULT 0,
    risk_dependencies INT NOT NULL DEFAULT 0,
    highest_severity VARCHAR(20) NOT NULL DEFAULT 'NONE',
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_scan_user_created (user_id, created_at)
) ENGINE=InnoDB COMMENT='用户Maven依赖扫描任务表';

CREATE TABLE IF NOT EXISTS scan_dependency (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    scan_id BIGINT NOT NULL,
    group_id VARCHAR(255) NOT NULL,
    artifact_id VARCHAR(255) NOT NULL,
    version VARCHAR(100),
    vulnerability_count INT NOT NULL DEFAULT 0,
    max_cvss_score DECIMAL(4,1),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_scan_dependency_scan (scan_id),
    INDEX idx_scan_dependency_coordinate (group_id, artifact_id)
) ENGINE=InnoDB COMMENT='扫描出的直接依赖表';

CREATE TABLE IF NOT EXISTS agent_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    mode VARCHAR(20) NOT NULL DEFAULT 'OFFLINE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_session_user (user_id, updated_at)
) ENGINE=InnoDB COMMENT='安全问答会话表';

CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT,
    action_type VARCHAR(60) NOT NULL,
    resource_type VARCHAR(60) NOT NULL,
    resource_id VARCHAR(80),
    detail VARCHAR(1000),
    ip_address VARCHAR(64),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_created (created_at),
    INDEX idx_audit_user (user_id)
) ENGINE=InnoDB COMMENT='后台审计日志表';

CREATE TABLE IF NOT EXISTS rpt_vuln_trend (
    stat_month DATE PRIMARY KEY,
    total_count BIGINT NOT NULL,
    high_count BIGINT NOT NULL,
    cumulative_count BIGINT NOT NULL,
    stat_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='D1漏洞趋势结果表';

CREATE TABLE IF NOT EXISTS rpt_severity_distribution (
    severity VARCHAR(20) PRIMARY KEY,
    vuln_count BIGINT NOT NULL,
    vuln_ratio DECIMAL(10,6) NOT NULL,
    stat_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='D2严重度分布结果表';

CREATE TABLE IF NOT EXISTS rpt_cwe_top (
    rank_no INT PRIMARY KEY,
    cwe_id VARCHAR(30) NOT NULL,
    cwe_name VARCHAR(255),
    vuln_count BIGINT NOT NULL,
    avg_cvss DECIMAL(4,2),
    stat_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='D3 CWE Top10结果表';

CREATE TABLE IF NOT EXISTS rpt_maven_package_risk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_id VARCHAR(255),
    artifact_id VARCHAR(255),
    package_name VARCHAR(500) NOT NULL,
    vuln_count BIGINT NOT NULL,
    critical_count BIGINT NOT NULL,
    avg_cvss DECIMAL(4,2),
    risk_score DECIMAL(10,2) NOT NULL,
    rank_no INT NOT NULL,
    stat_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_maven_package_rank (rank_no),
    INDEX idx_maven_risk_score (risk_score)
) ENGINE=InnoDB COMMENT='D4 Maven组件风险结果表';

CREATE TABLE IF NOT EXISTS rpt_risk_scatter (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    vuln_id VARCHAR(60) NOT NULL,
    package_name VARCHAR(500) NOT NULL,
    cvss_score DECIMAL(4,1) NOT NULL,
    affected_version_count INT NOT NULL,
    reference_count INT NOT NULL,
    severity VARCHAR(20) NOT NULL,
    stat_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_scatter_cvss (cvss_score)
) ENGINE=InnoDB COMMENT='D5漏洞风险散点结果表';

CREATE TABLE IF NOT EXISTS rpt_risk_flow (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_node VARCHAR(255) NOT NULL,
    target_node VARCHAR(255) NOT NULL,
    flow_value BIGINT NOT NULL,
    source_level INT NOT NULL,
    target_level INT NOT NULL,
    stat_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_flow_levels (source_level, target_level)
) ENGINE=InnoDB COMMENT='D6风险流向结果表';

INSERT INTO data_source (source_code, source_name, source_url, status)
VALUES
    ('OSV_MAVEN', 'OSV Maven Vulnerabilities', 'https://osv-vulnerabilities.storage.googleapis.com/Maven/all.zip', 'PENDING'),
    ('NVD_CVE', 'National Vulnerability Database', 'https://nvd.nist.gov/vuln/data-feeds', 'PENDING'),
    ('CISA_KEV', 'CISA Known Exploited Vulnerabilities', 'https://www.cisa.gov/known-exploited-vulnerabilities-catalog', 'PENDING')
ON DUPLICATE KEY UPDATE source_name = VALUES(source_name), source_url = VALUES(source_url);
