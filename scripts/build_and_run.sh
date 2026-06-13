#!/bin/bash
set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CPP_DIR="$PROJECT_ROOT/cpp"
JAVA_DIR="$PROJECT_ROOT/java"
BUILD_DIR="$CPP_DIR/build"

echo "=== 智能中药配方颗粒调剂机 - 构建脚本 ==="
echo ""

echo "[1/3] 构建 C++ 动态链接库..."
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"
cmake .. -DCMAKE_BUILD_TYPE=Release
make -j$(sysctl -n hw.ncpu 2>/dev/null || echo 4)
echo "C++ 库构建完成: $(ls -la libtcm_dispenser.* 2>/dev/null || echo tcm_dispenser.dll)"
echo ""

echo "[2/3] 构建 Java 应用..."
cd "$JAVA_DIR"
./gradlew jar
echo "Java 应用构建完成"
echo ""

echo "[3/3] 运行应用..."
export JAVA_LIBRARY_PATH="$BUILD_DIR"
./gradlew run
