# 数据目录

`raw/`保存OSV、NVD和CISA原始文件，`processed/`保存仅做字段规范化的JSON Lines。实际数据被Git忽略。

Python工具只解压和关联字段，不生成统计结果。六个维度的聚合必须由Spark完成。
