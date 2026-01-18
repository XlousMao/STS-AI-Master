import sys


def main():
    try:
        from google.protobuf import __version__ as pb_version
    except Exception:
        print("[CHECK-ENV] google.protobuf 未安装，请先执行：pip install \"protobuf<=3.20.x\"")
        sys.exit(1)
    parts = pb_version.split(".")
    try:
        major = int(parts[0])
        minor = int(parts[1]) if len(parts) > 1 else 0
    except ValueError:
        print(f"[CHECK-ENV] 无法解析 protobuf 版本号：{pb_version}")
        sys.exit(1)
    if major > 3 or (major == 3 and minor > 20):
        print(f"[CHECK-ENV] 当前 protobuf 版本为 {pb_version}，建议降级至 <= 3.20.x")
        sys.exit(1)
    print(f"[CHECK-ENV] 当前 protobuf 版本为 {pb_version}，符合 <= 3.20.x 要求")


if __name__ == "__main__":
    main()

