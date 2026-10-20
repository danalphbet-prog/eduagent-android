#!/usr/bin/env bash
set -euo pipefail

# Native runtime used by the MIT-licensed Pocket TTS Android Engine port.
# These binaries are fetched at build time; neither ONNX weights nor external APKs
# are bundled into the EduAgent source repository.
REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
NATIVE_ROOT="$REPO_ROOT/pockettts/src/main"
ONNX_VERSION=1.20.0
ONNX_AAR_SHA256=07a8f71ef890afed8c6087a56220e6d558a492804276ee2dd7cb7f6262242027
ONNX_SOURCE_SHA256=5d0aaced52921b86f3f2b2eaac18cbfc96184d73e83baf2553c94d9d306c4de9
TASK_TEMP=$(mktemp -d)
trap 'rm -rf "$TASK_TEMP"' EXIT

mkdir -p "$NATIVE_ROOT/jniLibs/arm64-v8a" "$NATIVE_ROOT/third_party/onnxruntime"
curl -fL --retry 3 "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${ONNX_VERSION}/onnxruntime-android-${ONNX_VERSION}.aar" -o "$TASK_TEMP/runtime.aar"
echo "$ONNX_AAR_SHA256  $TASK_TEMP/runtime.aar" | sha256sum --check --status
unzip -p "$TASK_TEMP/runtime.aar" jni/arm64-v8a/libonnxruntime.so > "$NATIVE_ROOT/jniLibs/arm64-v8a/libonnxruntime.so"
curl -fL --retry 3 "https://github.com/microsoft/onnxruntime/archive/refs/tags/v${ONNX_VERSION}.tar.gz" -o "$TASK_TEMP/onnx-source.tar.gz"
echo "$ONNX_SOURCE_SHA256  $TASK_TEMP/onnx-source.tar.gz" | sha256sum --check --status
tar -xzf "$TASK_TEMP/onnx-source.tar.gz" -C "$TASK_TEMP"
cp -R "$TASK_TEMP/onnxruntime-${ONNX_VERSION}/include" "$NATIVE_ROOT/third_party/onnxruntime/include"
echo "Pocket TTS: ONNX Runtime ${ONNX_VERSION} pronto para o build ARM64"
