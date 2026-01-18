#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROTO_FILE="$ROOT_DIR/docs/protocols/sts_v1.proto"
PROTO_DIR="$ROOT_DIR/docs/protocols"

if [ -n "${PROTOC:-}" ]; then
  echo "检测到环境变量 PROTOC=${PROTOC}"
else
  echo "未检测到环境变量 PROTOC，将尝试使用系统路径中的 protoc"
fi

if command -v protoc >/dev/null 2>&1; then
  COMPILER="protoc"
else
  echo "未找到 protoc，请安装 Protobuf 编译器或设置 PROTOC 环境变量"
  exit 1
fi

JAVA_OUT="$ROOT_DIR/sts-bridge-mod/src/main/java"
PY_OUT="$ROOT_DIR/training-engine/envs"
CPP_OUT="$ROOT_DIR/qt-decision-client/include"

mkdir -p "$JAVA_OUT" "$PY_OUT" "$CPP_OUT"

"$COMPILER" --proto_path="$PROTO_DIR" --java_out="$JAVA_OUT" "$PROTO_FILE"
echo "Java 代码已生成: $JAVA_OUT"

"$COMPILER" --proto_path="$PROTO_DIR" --python_out="$PY_OUT" "$PROTO_FILE"
if [ -f "$PY_OUT/sts_v1_pb2.py" ]; then
  echo "Python 代码已生成: $PY_OUT/sts_v1_pb2.py"
else
  echo "Python 代码已生成: $PY_OUT"
fi

"$COMPILER" --proto_path="$PROTO_DIR" --cpp_out="$CPP_OUT" "$PROTO_FILE"
if [ -f "$CPP_OUT/sts_v1.pb.h" ]; then
  echo "C++ 代码已生成: $CPP_OUT/sts_v1.pb.h"
else
  echo "C++ 代码已生成: $CPP_OUT"
fi

echo "所有 Protobuf 代码生成步骤已完成"
