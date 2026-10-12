#!/usr/bin/env python3
"""发布前核对下载到的实验包回执：绑定提交、状态与两个 APK 的摘要。

只读本地文件；不联网、不签名、不发布。用于 publish job 在创建 prerelease 前的最后一道核对。
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from verification_require import require  # noqa: E402

RECEIPT = "experimental-package-receipt.json"


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    root = args.artifacts.resolve()
    require(root.is_dir(), "RECEIPT_ARTIFACTS_MISSING")
    receipts = [row for row in root.rglob(RECEIPT) if row.is_file()]
    require(len(receipts) == 1, "RECEIPT_COUNT")
    receipt = json.loads(receipts[0].read_text(encoding="utf-8"))
    require(receipt.get("status") == "PASS_EXPERIMENTAL_DEBUG_PACKAGE", "RECEIPT_STATUS")
    require(receipt.get("package") == "com.dsh.clienu.SunsetRNE", "RECEIPT_PACKAGE")
    require(receipt.get("source", {}).get("commit") == args.commit, "RECEIPT_COMMIT")
    rows = receipt.get("apks")
    require(isinstance(rows, list) and len(rows) == 2, "RECEIPT_APKS")
    require({row.get("flavor") for row in rows} == {"standard", "low"}, "RECEIPT_FLAVORS")
    selected = []
    for row in rows:
        apk = root / ("SunsetRNE-%s-experimental.apk" % row["flavor"])
        require(apk.is_file(), "RECEIPT_APK_MISSING: " + row["flavor"])
        require(digest(apk) == row.get("sha256"), "RECEIPT_APK_CHANGED: " + row["flavor"])
        require(row.get("debuggable") is True, "RECEIPT_NOT_DEBUGGABLE: " + row["flavor"])
        require(row.get("certificateSha256") == receipt["certificate"]["sha256"],
                "RECEIPT_CERTIFICATE: " + row["flavor"])
        selected.append(str(apk))
    report = dict(status="PASS", commit=args.commit, receipt=str(receipts[0]),
                  apks=[row["flavor"] for row in rows],
                  certificate=receipt["certificate"]["sha256"])
    if args.out:
        Path(args.out).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    print("PASS 回执绑定提交，两个实验 APK 摘要一致")


if __name__ == "__main__":
    main()
