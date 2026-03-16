#!/bin/bash
set -e

echo "=== 开始部署 xhs-data-analysis ==="

# 初始化 tokens.json（如果不存在）
if [ ! -f tokens.json ]; then
    echo "{}" > tokens.json
    echo "已创建 tokens.json"
fi

# 构建并启动
echo "构建镜像并启动容器..."
docker compose up -d --build

echo "=== 部署完成 ==="
echo "查看日志: docker logs -f xhs-data-analysis"