#!/usr/bin/env python3
"""只核对已构建的实验测试包：包名/版本/可调试/证书/ABI/离线与应急资产/摘要回执。

本工具不构建、不签名、不安装、不发布，也不读写任何密钥：
它只读 app/build/outputs 下的 debug APK 与仓库内已锁定的描述文件，
把核对结果写成 JSON 回执供 artifact 与 Release 绑定。

证书核验使用 CI 显式输入（工作流 input 或 ci/experimental-test-identity.json），
绝不以「DN 含 Android Debug」代替固定证书摘要，也不接受正式 E7E3 证书。
"""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))

from verification_require import require  # noqa: E402

import argparse  # noqa: E402
import hashlib  # noqa: E402
import json  # noqa: E402
import os  # noqa: E402
import re  # noqa: E402
import struct  # noqa: E402
import subprocess  # noqa: E402
import zipfile  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
FLAVORS = ("standard", "low")
HEX64 = re.compile(r"[a-f0-9]{64}")
PACKAGE_NAME = "com.dsh.clienu.SunsetRNE"
FLAVOR_MIN_SDK = {"standard": 30, "low": 23}
# 实验包固定签名：包内不得出现正式发布证书。
PUBLISH_CERTIFICATE_SHA256 = "e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5"
# aapt2 --proto / aapt dump badging 都不在纯 Python 环境里；这里直接读二进制清单。
ANDROID_NS = "http://schemas.android.com/apk/res/android"
REQUIRED_ATTRIBUTES = {
    "package": 0x01010000, "versionCode": 0x0101021B, "versionName": 0x0101021C,
    "minSdkVersion": 0x0101020C, "targetSdkVersion": 0x01010270, "debuggable": 0x0101000F,
    "compileSdkVersion": 0x01010572,
}
# 随包离线/应急入口：这只是可读的核对清单，逐项摘要仍由运行时描述文件约束。
# glibc-python.tar.gz / adb-wheels.tar.gz 是构建输入名，打包时按 .bin 落地（aapt 不得解 tar.gz）。
ESSENTIAL_ASSETS = (
    "offline-rootfs.bin", "dsh-runtime.bin", "ubuntu-tools.bin",
    "pnpm-runtime.bin", "python-support.bin", "glibc-python.bin",
    "adb-wheels.bin", "runtime-descriptor.json",
    "managed-runtime-inputs.json", "recovery-runtime.json",
    "recovery-agent.js", "recovery-asset-locations.json",
)
FORBIDDEN_ABIS = ("x86", "x86_64", "armeabi", "armeabi-v7a", "arm64-v8a.legacy")


class Gate:
    """收集全部失败再抛出；CI 一次就能看到所有坏项。"""

    def __init__(self, label):
        self.label = label
        self.failures = []

    def require(self, condition, message):
        if not condition:
            self.failures.append(message)
        return bool(condition)

    def equal(self, actual, expected, message):
        return self.require(actual == expected, f"{message}: {actual!r} != {expected!r}")

    def done(self):
        if self.failures:
            raise ValueError(self.label + ": " + "; ".join(self.failures))


def digest_bytes(data):
    return hashlib.sha256(data).hexdigest()


def digest_file(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def digest_member(archive, name):
    value = hashlib.sha256()
    with archive.open(name) as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


# ---------------------------------------------------------------------------
# 二进制 AndroidManifest.xml（resources.arsc 字符串池）最小解析器
# ---------------------------------------------------------------------------

def parse_string_pool(data, offset):
    # ResStringPool_header（本仓库 aapt2 实际布局）：
    #   8B chunk 头 + stringCount + styleCount + flags + stringsStart + stylesStart
    _type, header = struct.unpack_from("<HH", data, offset)
    count = struct.unpack_from("<I", data, offset + 8)[0]
    # aapt2 布局：+8 stringCount, +12 styleCount, +16 flags, +20 stringsStart, +24 stylesStart
    flags = struct.unpack_from("<I", data, offset + 16)[0]
    start = struct.unpack_from("<I", data, offset + 20)[0]
    utf8 = flags & (1 << 8) != 0
    offsets = struct.unpack_from("<%dI" % count, data, offset + header)
    base = offset + start
    values = []
    for index in range(count):
        position = base + offsets[index]
        if utf8:
            _u16, length = struct.unpack_from("<BB", data, position)
            values.append(data[position + 2:position + 2 + length].decode("utf-8", "replace"))
        else:
            length = struct.unpack_from("<H", data, position)[0]
            values.append(data[position + 2:position + 2 + length * 2].decode("utf-16-le", "replace"))
    return values


def parse_axml(data):
    # 头部 8 字节：ResChunk_header{type=RES_XML_TYPE, headerSize=8}
    # 之后紧接 totalSize 属性和字符串池；totalSize 覆盖整个文件，不是池的起点。
    require((data[:4] == b"\x03\x00\x08\x00" and len(data) >= 8), "AXML_HEADER")
    total_size = struct.unpack_from("<I", data, 4)[0]
    require((8 <= total_size <= len(data)), "AXML_ROOT_SIZE")
    cursor = 8
    strings, elements = [], []
    while cursor + 8 <= len(data):
        kind, header, size = struct.unpack_from("<HHI", data, cursor)
        require((header >= 8 and cursor + size <= len(data)), "AXML_CHUNK")
        if kind == 0x0001 and header >= 28:
            strings = parse_string_pool(data, cursor)
        elif kind == 0x0102 and header >= 16 and size >= 36:
            _line, _comment, ns, name = struct.unpack_from("<IIII", data, cursor + 8)
            # aapt2 的 headerSize=16，但 attrExt 把 attributeCount 写在 +28 处（+24 起是
            # attributeStart/attributeSize/attributeCount，属性块紧随其后）。
            attribute_start, attribute_size = struct.unpack_from("<HH", data, cursor + 24)
            count = struct.unpack_from("<H", data, cursor + 28)[0]
            require((attribute_size >= 16), "AXML_ATTRIBUTE_LAYOUT")
            # attributeStart 相对 chunk 起点；aapt2 的 headerSize=16，属性块实际起于 chunk+36。
            first = cursor + header + attribute_start
            raw_attributes = [data[first + index * attribute_size:first + (index + 1) * attribute_size]
                              for index in range(count)]
            elements.append(dict(ns=ns, name=name, attributes=raw_attributes))
        cursor += max(size, 8)
    return strings, elements


def manifest_attributes(apk):
    """返回 {属性名: 值}；优先按 aapt 的 framework 资源 ID 匹配，退回属性名字符串。"""
    names = apk.namelist()
    require(("AndroidManifest.xml" in names), "APK_MANIFEST_MISSING")
    strings, elements = parse_axml(apk.read("AndroidManifest.xml"))
    found = {}
    for element in elements:
        element_name = strings[element["name"]] if element["name"] < len(strings) else ""
        for raw in element["attributes"]:
            _ns, name, _raw_value = struct.unpack_from("<III", raw, 0)
            # 属性记录 20B：[ns][name][rawValue][Res_value: size(2) res0(1) type(1) data(4)]
            # 因此 type 在偏移 15，data 在偏移 16。
            kind = raw[15]
            data_value = struct.unpack_from("<I", raw, 16)[0]
            if kind == 0x03:
                value = strings[data_value] if data_value < len(strings) else None
            elif kind == 0x12:
                value = bool(data_value)
            elif kind == 0x10:
                value = struct.unpack_from("<i", raw, 16)[0]
            elif kind == 0x11:
                value = data_value
            else:
                value = data_value
            attribute_name = strings[name] if name < len(strings) else ""
            key = None
            for candidate, resource in REQUIRED_ATTRIBUTES.items():
                if resource == name:
                    key = candidate
                    break
            if key is None and attribute_name in REQUIRED_ATTRIBUTES:
                key = attribute_name
            if key is not None and key not in found:
                found[key] = value
    require(("package" in found), "APK_MANIFEST_PACKAGE")
    return found


def find_apksigner(sdk=None, java_home=None):
    roots = []
    if sdk:
        roots.append(Path(sdk) / "build-tools")
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(name):
            roots.append(Path(os.environ[name]) / "build-tools")
    roots.append(Path("/root/Android/build-tools"))
    for root in roots:
        if not root.is_dir():
            continue
        for candidate in sorted(root.glob("*/apksigner"), reverse=True):
            if candidate.is_file():
                return candidate
    return None


def signature_report(path, min_sdk, sdk=None, java_home=None):
    """调用仓库已有的 apksigner 读取证书摘要与已验证签名方案（只读，不签名）。

    与 tools/sign-release-apks.py 使用同一个校验器，避免自写 ASN.1/签名块解析。
    """
    signer = find_apksigner(sdk, java_home)
    require((signer is not None), "APK_APKSIGNER_MISSING")
    # 用该 flavor 真实 minSdk 校验：standard(30) 不要求 v1，low(23) 明确要求 v1+v2+v3。
    command = [str(signer), "verify", "--min-sdk-version", str(min_sdk), "--verbose", "--print-certs", str(path)]
    environment = None
    if java_home:
        binary = Path(java_home) / ("bin/java.exe" if os.name == "nt" else "bin/java")
        if binary.is_file():
            environment = dict(os.environ, JAVA_HOME=str(java_home),
                               PATH=str(Path(java_home) / "bin") + os.pathsep + os.environ.get("PATH", ""))
    completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               env=environment, check=False)
    text = completed.stdout.decode("utf-8", "replace")
    require((completed.returncode == 0), "APK_APKSIGNER_FAILED: " + text.strip().splitlines()[-1:][0]
            if text.strip() else "APK_APKSIGNER_FAILED")
    digests = sorted(set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", text)))
    schemes = sorted(set(re.findall(r"Verified using (v[0-9.]+) scheme[^\n]*?true", text)))
    return digests, schemes, text


# ---------------------------------------------------------------------------
# 输入包 / 描述文件
# ---------------------------------------------------------------------------

def expected_certificate(args, root=None):
    if args.certificate_sha256:
        value = args.certificate_sha256.strip().lower()
    else:
        value = load_json((root or ROOT) / "ci/experimental-test-identity.json")["certificateSha256"]
    require(bool(HEX64.fullmatch(value)), "EXPECTED_CERTIFICATE_FORMAT: " + repr(value))
    require(value != PUBLISH_CERTIFICATE_SHA256, "EXPECTED_CERTIFICATE_IS_PUBLISH_KEY")
    return value


def descriptor(root, minimum_inputs=50):
    data = load_json(root / "app/src/main/assets/runtime-descriptor.json")
    require((data.get("version") == 1), "RUNTIME_DESCRIPTOR_SCHEMA")
    require(bool(HEX64.fullmatch(str(data.get("runtimeId")))), "RUNTIME_DESCRIPTOR_ID")
    require((isinstance(data.get("inputs"), dict) and len(data["inputs"]) >= minimum_inputs),
            "RUNTIME_DESCRIPTOR_INPUTS")
    for name, value in data["inputs"].items():
        require(bool(HEX64.fullmatch(str(value))), "RUNTIME_DESCRIPTOR_DIGEST: " + str(name))
    return data


def recovery_lock(root):
    lock = load_json(root / "tools/recovery-runtime/lock.json")
    require((lock.get("schema") == 1 and len(lock.get("archives", [])) == 2), "RECOVERY_LOCK_SHAPE")
    for row in lock.get("archives", []):
        require(bool(HEX64.fullmatch(str(row.get("sha256")))), "RECOVERY_LOCK_DIGEST")
    return lock


# ---------------------------------------------------------------------------
# 核对
# ---------------------------------------------------------------------------

def verify_manifest(gate, apk, flavor, expected):
    attributes = manifest_attributes(apk)
    gate.equal(attributes.get("package"), PACKAGE_NAME, "APK_PACKAGE_NAME")
    gate.equal(attributes.get("versionCode"), expected["versionCode"], "APK_VERSION_CODE")
    # low flavor 在 app/build.gradle 里声明 versionNameSuffix 'low'。
    version_name = expected["versionName"] + ("low" if flavor == "low" else "")
    gate.equal(attributes.get("versionName"), version_name, "APK_VERSION_NAME")
    gate.equal(attributes.get("minSdkVersion"), FLAVOR_MIN_SDK[flavor], "APK_MIN_SDK")
    gate.equal(attributes.get("targetSdkVersion"), expected["targetSdk"], "APK_TARGET_SDK")
    gate.equal(bool(attributes.get("debuggable")), True, "APK_DEBUGGABLE")
    return attributes


def verify_abis(gate, apk):
    abis = set()
    for name in apk.namelist():
        parts = name.split("/")
        if len(parts) > 2 and parts[0] == "lib":
            abis.add(parts[1])
    gate.require(bool(abis), "APK_ABI_MISSING")
    for abi in sorted(abis):
        gate.require(abi not in FORBIDDEN_ABIS, "APK_ABI_FORBIDDEN: " + abi)
    gate.equal(abis, {"arm64-v8a"}, "APK_ABI_SET")
    return sorted(abis)


def verify_signature(gate, apk_path, expected_certificate, min_sdk, sdk=None, java_home=None):
    try:
        digests, schemes, _output = signature_report(apk_path, min_sdk, sdk, java_home)
    except RuntimeError as error:
        gate.failures.append(str(error))
        return [], []
    gate.require(bool(digests), "APK_CERTIFICATE_MISSING")
    gate.require(bool(schemes), "APK_SIGNATURE_SCHEME_MISSING")
    gate.require(expected_certificate in digests, "APK_CERTIFICATE_MISMATCH")
    gate.require(PUBLISH_CERTIFICATE_SHA256 not in digests, "APK_RELEASE_CERTIFICATE_PRESENT")
    return digests, schemes


def verify_assets(gate, apk, descriptor_data, lock):
    present = set(apk.namelist())
    required = set(descriptor_data["inputs"]) | set(ESSENTIAL_ASSETS)
    for name in sorted(descriptor_data["inputs"]):
        if name.endswith(".inputs.json"):
            required.add(name)
    for name in sorted(required):
        member = "assets/" + name
        expected = descriptor_data["inputs"].get(name)
        if member not in present and name.endswith(".tar.gz"):
            # 打包约定：aapt 不能把 .tar.gz 当归档解开，源资产以 .bin 落地。
            member = "assets/" + name.replace(".tar.gz", ".bin")
            expected = descriptor_data["inputs"].get(name + ".bin", expected)
        if not gate.require(member in present, "APK_ASSET_MISSING: " + name):
            continue
        if expected is not None:
            gate.equal(digest_member(apk, member), expected, "APK_ASSET_DIGEST: " + name)
    # 应急归档可与正式归档按精确 SHA 共用物理字节；落点由 verify_recovery 的
    # archive_locations 核验，不能再强制要求独立 recovery-*.bin。
    if "assets/runtime-descriptor.json" in present:
        gate.equal(json.loads(apk.read("assets/runtime-descriptor.json")),
                   descriptor_data, "APK_RUNTIME_DESCRIPTOR_SOURCE")


def verify_recovery(gate, apk, flavor, lock):
    from recovery_apk_assets import archive_locations
    require(("assets/recovery-runtime.json" in apk.namelist()), "APK_RECOVERY_DESCRIPTOR_MISSING")
    data = json.loads(apk.read("assets/recovery-runtime.json"))
    payload = {key: value for key, value in data.items() if key != "id"}
    gate.equal(data.get("id"),
               digest_bytes(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()),
               "RECOVERY_DESCRIPTOR_ID")
    gate.equal(data.get("schema"), 1, "RECOVERY_DESCRIPTOR_SCHEMA")
    gate.equal(data.get("dshVersion"), lock["dshVersion"], "RECOVERY_DESCRIPTOR_DSH")
    gate.equal(data.get("archives"), [{k: row[k] for k in ("asset", "sha256")} for row in lock["archives"]],
               "RECOVERY_DESCRIPTOR_ARCHIVES")
    sources = archive_locations(apk, data, require_locations=True)
    gate.require((len(sources) == 2), "RECOVERY_ARCHIVE_SOURCES")
    gate.equal(digest_member(apk, "assets/recovery-agent.js"), data.get("agentSha256"), "RECOVERY_AGENT")
    rows = data.get("files")
    gate.require((isinstance(rows, list) and len(rows) > 100), "RECOVERY_FILE_COUNT")
    expanded = 0
    proof = {}
    # files 是已由 archive_locations 校验整包 SHA 的归档展开清单，并非 APK 资产列表。
    # 与 verify-recovery-apk.py 一致：核对清单形状/总大小，覆盖层另按真实 APK 字节核验。
    for row in rows or []:
        name = row.get("path", "")
        valid = (isinstance(name, str) and name and not name.startswith("/")
                 and ".." not in name.split("/") and name not in proof
                 and type(row.get("bytes")) is int and row["bytes"] >= 0
                 and bool(HEX64.fullmatch(str(row.get("sha256")))))
        if not gate.require(valid, "RECOVERY_FILE_PROOF: " + str(name)):
            continue
        proof[name] = row
        expanded += row["bytes"]
    gate.equal(data.get("expandedBytes"), expanded, "RECOVERY_EXPANDED_BYTES")
    for name, value in (data.get("profileHashes") or {}).items():
        gate.equal(digest_member(apk, "assets/" + name), value, "RECOVERY_PROFILE: " + name)
    # 启动器按 flavor 精确核对包内字节，而不是只确认文件存在。
    launchers = (data.get("launcherHashes") or {}).get(flavor)
    gate.require((isinstance(launchers, dict) and launchers), "RECOVERY_LAUNCHER_HASHES: " + flavor)
    for name, value in (launchers or {}).items():
        member = "lib/arm64-v8a/" + name
        if not gate.require(member in apk.namelist(), "RECOVERY_LAUNCHER_MISSING: " + name):
            continue
        gate.equal(digest_member(apk, member), value, "RECOVERY_LAUNCHER_DIGEST: " + name)
    overlays = data.get("overlays")
    gate.require((isinstance(overlays, list) and overlays), "RECOVERY_OVERLAYS")
    for row in overlays or []:
        member = "assets/" + row["asset"]
        if not gate.require(member in apk.namelist(), "RECOVERY_OVERLAY_MISSING: " + row["asset"]):
            continue
        gate.equal(digest_member(apk, member), row["sha256"], "RECOVERY_OVERLAY_DIGEST: " + row["asset"])
        gate.equal(apk.getinfo(member).file_size, row["bytes"], "RECOVERY_OVERLAY_SIZE: " + row["asset"])
        gate.require((proof.get(row["path"], {}).get("sha256") == row["sha256"]
                      and proof.get(row["path"], {}).get("bytes") == row["bytes"]), "RECOVERY_OVERLAY_PROOF")
        gate.require(bool(HEX64.fullmatch(str(row.get("originalSha256"))) ), "RECOVERY_OVERLAY_ORIGIN")
    inputs = data.get("overlayInputs")
    gate.require((isinstance(inputs, dict) and inputs), "RECOVERY_OVERLAY_INPUTS")
    for name, value in (inputs or {}).items():
        member = "assets/" + name
        if not gate.require(member in apk.namelist(), "RECOVERY_OVERLAY_INPUT_MISSING: " + name):
            continue
        gate.equal(digest_member(apk, member), value, "RECOVERY_OVERLAY_INPUT_DIGEST: " + name)


def verify_flavor(gate, apk_path, flavor, expected, descriptor_data, lock, sdk=None, java_home=None):
    apk_path = Path(apk_path)
    gate.require(apk_path.is_file(), "APK_MISSING: " + flavor)
    with zipfile.ZipFile(apk_path) as apk:
        gate.require((apk.testzip() is None), "APK_ARCHIVE_CORRUPT: " + flavor)
        attributes = verify_manifest(gate, apk, flavor, expected)
        abis = verify_abis(gate, apk)
        certificates, schemes = verify_signature(gate, apk_path, expected["certificateSha256"],
                                                    FLAVOR_MIN_SDK[flavor], sdk, java_home)
        verify_assets(gate, apk, descriptor_data, lock)
        try:
            verify_recovery(gate, apk, flavor, lock)
        except RuntimeError as error:
            gate.failures.append(str(error))
        legacy = "lib/arm64-v8a/libproot_legacy.so" in apk.namelist()
        gate.equal(legacy, flavor == "low", "APK_FLAVOR_LAUNCHER: " + flavor)
        for name in ("lib/arm64-v8a/libproot.so", "lib/arm64-v8a/libprootloader.so"):
            gate.require((name in apk.namelist()), "APK_LAUNCHER_MISSING: " + name)
        return {
            "flavor": flavor,
            "path": str(apk_path),
            "bytes": apk_path.stat().st_size,
            "sha256": digest_file(apk_path),
            "package": attributes.get("package"),
            "versionCode": attributes.get("versionCode"),
            "versionName": attributes.get("versionName"),
            "minSdk": attributes.get("minSdkVersion"),
            "targetSdk": attributes.get("targetSdkVersion"),
            "debuggable": bool(attributes.get("debuggable")),
            "certificateSha256": expected["certificateSha256"],
            "certificateDigests": certificates,
            "signatureSchemes": schemes,
            "nativeAbi": abis,
            "runtimeId": descriptor_data["runtimeId"],
            "recoveryId": json.loads(apk.read("assets/recovery-runtime.json"))["id"],
            "launcher": "low-legacy" if legacy else "standard",
        }


def source_facts(root):
    build = (root / "app/build.gradle").read_text(encoding="utf-8")
    version_code = re.search(r"versionCode\s+(\d+)", build)
    version_name = re.search(r'versionName\s+"([^"]+)"', build)
    target_sdk = re.search(r"targetSdk\s+(\d+)", build)
    require((version_code and version_name and target_sdk), "SOURCE_BUILD_GRADLE")
    return {
        "versionCode": int(version_code[1]),
        "versionName": version_name[1],
        "targetSdk": int(target_sdk[1]),
    }


def run(root, apks, certificate_sha256, bundle, out, environ, minimum_descriptor_inputs=50,
        sdk=None, java_home=None, quiet=False):
    # 实验包固定测试证书：既不是正式 E7E3，也不能拿它当期望值。
    require(bool(HEX64.fullmatch(str(certificate_sha256))), "EXPECTED_CERTIFICATE_FORMAT")
    require(certificate_sha256 != PUBLISH_CERTIFICATE_SHA256, "EXPECTED_CERTIFICATE_IS_PUBLISH_KEY")
    gate = Gate("CI_TEST_PACKAGE")
    expected = source_facts(root)
    expected["certificateSha256"] = certificate_sha256
    descriptor_data = descriptor(root, minimum_descriptor_inputs)
    lock = recovery_lock(root)
    rows = []
    for flavor in FLAVORS:
        require((flavor in apks), "APK_ARGUMENT_MISSING: " + flavor)
        rows.append(verify_flavor(gate, apks[flavor], flavor, expected, descriptor_data, lock, sdk, java_home))
    if len({row["sha256"] for row in rows}) != len(rows):
        gate.failures.append("APK_DUPLICATE_BYTES")
    if len({row["versionCode"] for row in rows}) != 1:
        gate.failures.append("APK_VERSION_CODE_SPLIT")
    if len({row["certificateSha256"] for row in rows}) != 1:
        gate.failures.append("APK_CERTIFICATE_SPLIT")
    receipt = {
        "schema": 1,
        "status": "PASS_EXPERIMENTAL_DEBUG_PACKAGE",
        "purpose": "CI-built experimental debug/test package; not the historical E7E3 release identity",
        "package": PACKAGE_NAME,
        "source": {
            "commit": environ.get("GITHUB_SHA") or "",
            "repository": environ.get("GITHUB_REPOSITORY") or "",
            "ref": environ.get("GITHUB_REF") or "",
            "workflowRef": environ.get("GITHUB_WORKFLOW_REF") or "",
            "runId": environ.get("GITHUB_RUN_ID") or "",
            "runAttempt": environ.get("GITHUB_RUN_ATTEMPT") or "",
            "dirty": True,
            "controlledOverride": {"file": "app/build.gradle", "field": "applicationId",
                                   "from": "com.dsh.client", "to": PACKAGE_NAME},
        },
        "assetBundle": bundle,
        "certificate": {
            "sha256": certificate_sha256,
            "schemes": sorted({scheme for row in rows for scheme in row["signatureSchemes"]}),
            "identity": "experimental-debug-test-key",
            "notReleaseIdentity": "e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5",
        },
        "runtimeId": descriptor_data["runtimeId"],
        "dshVersion": descriptor_data["dshVersion"],
        "build": expected,
        "apks": rows,
        "limitations": [
            "CI 只证明字节、清单与签名身份；不证明真机安装、升级或数据保留。",
            "实验测试证书不是正式发布证书，产物不得作为正式包或覆盖安装正式包。",
        ],
    }
    gate.done()
    if out:
        Path(out).parent.mkdir(parents=True, exist_ok=True)
        Path(out).write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if not quiet:
        print(json.dumps(receipt, ensure_ascii=False, indent=2))
        print("PASS 实验测试包包名/版本/可调试/固定证书/ABI/离线+应急资产/摘要回执")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--standard", type=Path, required=True)
    parser.add_argument("--low", type=Path, required=True)
    parser.add_argument("--certificate-sha256", default="")
    parser.add_argument("--bundle-url", default="")
    parser.add_argument("--bundle-sha256", default="")
    parser.add_argument("--out", type=Path)
    parser.add_argument("--expected-commit", default="")
    parser.add_argument("--root", type=Path, default=ROOT, help="校验所用的源码根；主机测试指向夹具目录")
    parser.add_argument("--minimum-descriptor-inputs", type=int, default=50)
    parser.add_argument("--sdk", type=Path, default=None, help="Android SDK 根；缺省用 ANDROID_HOME 或本地位置")
    parser.add_argument("--java-home", type=Path, default=None, help="JDK 根；用于 apksigner 的 java")
    args = parser.parse_args()
    root = args.root.resolve()
    if args.expected_commit:
        actual = os.environ.get("GITHUB_SHA", "")
        if actual and actual != args.expected_commit:
            parser.error("GITHUB_SHA 与 dispatch 选择提交不一致")
    certificate = expected_certificate(args, root)
    bundle = {"url": args.bundle_url, "sha256": args.bundle_sha256}
    if bundle["sha256"]:
        require(bool(HEX64.fullmatch(bundle["sha256"])), "BUNDLE_SHA_FORMAT")
    run(root, {"standard": args.standard, "low": args.low}, certificate, bundle, args.out, os.environ,
        args.minimum_descriptor_inputs, args.sdk, args.java_home)


if __name__ == "__main__":
    main()
