@echo off
setlocal

set "ROOT_DIR=%~dp0.."
pushd "%ROOT_DIR%" || (
  echo Root directory not found
  exit /b 1
)

where protoc >nul 2>&1
if errorlevel 1 (
  echo protoc not found in PATH
  popd
  exit /b 1
)

set "JAVA_OUT=sts-bridge-mod\src\main\java"
set "PY_OUT=training-engine\envs"
set "CPP_OUT=qt-decision-client\include"

if not exist "%JAVA_OUT%" mkdir "%JAVA_OUT%"
if not exist "%PY_OUT%" mkdir "%PY_OUT%"
if not exist "%CPP_OUT%" mkdir "%CPP_OUT%"

echo Generating Java code...
protoc --proto_path="docs\protocols" --java_out="%JAVA_OUT%" "docs\protocols\sts_v1.proto"
if errorlevel 1 (
  echo Java generation failed
  popd
  exit /b 1
) else (
  echo Java generated: %CD%\%JAVA_OUT%
)

echo Generating Python code...
protoc --proto_path="docs\protocols" --python_out="%PY_OUT%" "docs\protocols\sts_v1.proto"
if errorlevel 1 (
  echo Python generation failed
  popd
  exit /b 1
) else (
  if exist "%PY_OUT%\sts_v1_pb2.py" (
    echo Python generated: %CD%\%PY_OUT%\sts_v1_pb2.py
  ) else (
    echo Python generated: %CD%\%PY_OUT%
  )
)

echo Generating C++ code...
protoc --proto_path="docs\protocols" --cpp_out="%CPP_OUT%" "docs\protocols\sts_v1.proto"
if errorlevel 1 (
  echo C++ generation failed
  popd
  exit /b 1
) else (
  if exist "%CPP_OUT%\sts_v1.pb.h" (
    echo C++ generated: %CD%\%CPP_OUT%\sts_v1.pb.h
  ) else (
    echo C++ generated: %CD%\%CPP_OUT%
  )
)

echo Protobuf generation finished

popd
endlocal
