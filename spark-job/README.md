# Spark分析作业

作业兼容集群现有Spark 2.4.5、Scala 2.11和Java 8。默认一次计算D1—D6，并把每个结果同时写入HDFS。提供JDBC参数时再回写MySQL。

```bash
/export/server/spark/bin/spark-submit \
  --master yarn \
  --deploy-mode client \
  --num-executors 3 \
  --executor-cores 1 \
  --executor-memory 512m \
  --class com.vulnscope.spark.VulnScopeAnalyticsJob \
  /opt/vulnscope/vulnscope-spark-job.jar \
  --input hdfs:///vulnscope/input/vulnerabilities.jsonl \
  --output hdfs:///vulnscope/output \
  --dimension ALL
```

数据库密码只从环境变量`VULNSCOPE_DB_PASSWORD`读取，不出现在提交命令、Git或日志中。
