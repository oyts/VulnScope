USE vulnscope;

ALTER TABLE analysis_task
    ADD COLUMN stage VARCHAR(40) NOT NULL DEFAULT 'QUEUED' AFTER status,
    ADD COLUMN progress INT NOT NULL DEFAULT 0 AFTER stage,
    ADD COLUMN hdfs_output_path VARCHAR(500) NULL AFTER hdfs_input_path,
    ADD COLUMN source_rows BIGINT NOT NULL DEFAULT 0 AFTER application_id,
    ADD COLUMN log_excerpt TEXT NULL AFTER error_message;
