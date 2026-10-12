#!/usr/bin/env python3
"""ci-test-package 的主机测试夹具：在临时目录里合成与真实摘要自洽的假仓库。

这些字节只用于验证解析与门禁逻辑，不是真实 Android 构建产物，也不安装到设备，
更不包含任何密钥：v2 签名块里只有一张自签测试证书。
"""
import base64
import hashlib
import json
import struct
from pathlib import Path
import zlib

ANDROID_NS = "http://schemas.android.com/apk/res/android"
# 自签测试证书（C=US/O=Android/CN=Android Debug），只用于夹具，不是任何真实密钥。
TEST_CERTIFICATE_DER_BASE64 = (
    "MIIDTzCCAjegAwIBAgIUP0vt0DUDQUUoUFHxKWzQvOc5Y58wDQYJKoZIhvcNAQELBQAwNzELMAkG"
    "A1UEBhMCVVMxEDAOBgNVBAoMB0FuZHJvaWQxFjAUBgNVBAMMDUFuZHJvaWQgRGVidWcwHhcNMjYx"
    "MDExMjAxMDIzWhcNMzYxMDA4MjAxMDIzWjA3MQswCQYDVQQGEwJVUzEQMA4GA1UECgwHQW5kcm9p"
    "ZDEWMBQGA1UEAwwNQW5kcm9pZCBEZWJ1ZzCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEB"
    "AKdUWiurP0wwKGAvBfyrCGFrxM5bFXw070nz2G6oZ0gOm90dhKxwM3iV49zCB5FzNBo4sVTjpUrn"
    "Q+m1mkm7z3cNz0BlaeD0dAZyoBg7Fs71A5PBZu2drdTqWfILAVBjIGPcTz6iaE8fdqasccFQrgi/"
    "a0SOOk+9xS2pEDXx92gR4r5inXToG3ch5Hdxql8FCcx59/mcjwy2ZDvAtmzdN9Dimd8IbZ9ggaQv"
    "Yqpys3SRkqo4MrvCUhM8/28hpy1vmDcoYP3HYOFyqzFLiAKxqcg+84XvB+PwnpvstxPJxp8RfpKY"
    "GnAyKvxFdezcYTPspvhK5PJUYE2mIULZg8NaLZMCAwEAAaNTMFEwHQYDVR0OBBYEFM/B6YpndK9a"
    "AQtIh/LKVOLRHQKAMB8GA1UdIwQYMBaAFM/B6YpndK9aAQtIh/LKVOLRHQKAMA8GA1UdEwEB/wQF"
    "MAMBAf8wDQYJKoZIhvcNAQELBQADggEBADczOr8hYQJabWGKuE4R2CaT34YUmfjhXFmlb6aR2xyt"
    "rth1Ja4rXBBqLl1bCEC6YnisnXUu04+ALV7SY8sCcJzl/i1dWHATnFJwbLJbHDl8AlnVtYbdDMsd"
    "h1bB3mw73aywpUxFTqmQ0Xh1Dx9owR3dwFkFd25lggbP0vB24q/+U/jNCwGA/epXjYlP2m+AhhLe"
    "YEg208cMjBIlf/aY5DwnJ7qZGZrzRZQ7gNOxqZsWnHPh5blBeHDMtEbeaWuEGQxX2IFsVWW6h8Z8"
    "oG/nQBmWUOkJJUQa9miaJawgjGR7M/cMrnOXpq+FjBRmX7+ziz1rPmcY6fc7R3ck/eCzRkk="
)
TEST_CERTIFICATE_SHA256 = "bbec711b35e6ae4dd3c9954692814ae22d0bb09539665d87512c6d1732065e12"
INPUT_ASSETS = (
    "offline-rootfs.bin", "dsh-runtime.bin", "ubuntu-tools.bin", "pnpm-runtime.bin",
    "python-support.bin", "glibc-python.tar.gz", "adb-wheels.tar.gz", "runtime-tools.json",
    "managed-runtime-inputs.json", "runtime-descriptor.json",
)
RECOVERY_ESSENTIALS = (
    "recovery-agent.js", "recovery-asset-locations.json",
)


def certificate_der():
    return base64.b64decode(TEST_CERTIFICATE_DER_BASE64)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def utf16(value):
    # AXML 字符串池的 UTF-16 项必须带 NUL 终止符（aapt2 与 apksig 都要求）。
    return struct.pack("<H", len(value)) + value.encode("utf-16-le") + b"\x00\x00"


def string_pool(strings):
    offsets, data = [], b""
    for value in strings:
        offsets.append(len(data))
        data += utf16(value)
    if len(data) % 4:
        data += b"\x00" * (4 - len(data) % 4)
    start = 28 + 4 * len(strings)
    header = struct.pack("<HHIIIIII", 0x0001, 28, start + len(data), len(strings), 0, 0, start, 0)
    return header + struct.pack("<%dI" % len(strings), *offsets) + data


def start_element(name, attributes, namespace=0xFFFFFFFF):
    """按 aapt2 实际字节布局写出 START_ELEMENT。

    布局（相对 chunk 起点）：
      0  ResChunk_header(type=0x0102, headerSize=16, size)
      8  ResXMLTree_node: line(4) comment(4)
      16 ResXMLTree_attrExt: ns(4) name(4) attributeStart(2) attributeSize(2) attributeCount(2)
      38 属性块，每项 20 字节（attributeStart=20 相对 attrExt 起点 16+20=36? 见下）
    aapt2 的 attributeStart 相对 attrExt 起点（chunk+16），属性块因此从 chunk+16+20=chunk+36 开始。
    """
    count = len(attributes)
    assert len(attributes) == count
    payload = b""
    for ns, attribute_name, value in attributes:
        payload += struct.pack("<III", ns, attribute_name, 0) + struct.pack("<HBBI", 8, 0, value[0], value[1])
    size = 36 + len(payload)
    chunk = bytearray(size)
    struct.pack_into("<HHI", chunk, 0, 0x0102, 16, size)
    struct.pack_into("<II", chunk, 8, 0, 0xFFFFFFFF)
    struct.pack_into("<II", chunk, 16, namespace, name)
    struct.pack_into("<HHH", chunk, 24, 20, 20, count)
    chunk[36:36 + len(payload)] = payload
    return bytes(chunk)


def end_element(name, namespace=0xFFFFFFFF):
    body = struct.pack("<IIII", 0, 0xFFFFFFFF, namespace, name)
    return struct.pack("<HHI", 0x0103, 16, 8 + len(body)) + body


def resource_map(attributes):
    """aapt2 会在字符串池后紧跟 RES_XML_RESOURCE_MAP；解析器不得依赖它。"""
    body = b"".join(struct.pack("<I", name) for _ns, name, _value in attributes)
    return struct.pack("<HHI", 0x0180, 8, 8 + len(body)) + body


def android_manifest(package, version_code, version_name, min_sdk, target_sdk, compile_sdk, debuggable):
    strings = [
        ANDROID_NS, "manifest", "package", "versionCode", "versionName", "uses-sdk",
        "minSdkVersion", "targetSdkVersion", "compileSdkVersion", "application", "debuggable",
        package, version_name, str(version_code), str(min_sdk), str(target_sdk), str(compile_sdk),
    ]
    index = {value: position for position, value in enumerate(strings)}
    ns = index[ANDROID_NS]

    def text(key):
        return (0x03, index[key])

    def integer(key):
        # 整数属性的 data 就是数值本身（不是字符串池下标）。
        return (0x10, int(key))

    manifest_attributes = [
        (ns, 0x01010000, text(package)),          # android:package
        (ns, 0x0101021B, integer(str(version_code))),   # android:versionCode
        (ns, 0x0101021C, text(version_name)),     # android:versionName
        (ns, 0x01010572, integer(str(compile_sdk))),    # android:compileSdkVersion
    ]
    uses_sdk_attributes = [
        (ns, 0x0101020C, integer(str(min_sdk))),  # android:minSdkVersion
        (ns, 0x01010270, integer(str(target_sdk))),  # android:targetSdkVersion
    ]
    application_attributes = [(ns, 0x0101000F, (0x12, 0xFFFFFFFF if debuggable else 0))]  # android:debuggable
    body = (start_element(index["manifest"], manifest_attributes)
            + start_element(index["uses-sdk"], uses_sdk_attributes) + end_element(index["uses-sdk"])
            + start_element(index["application"], application_attributes) + end_element(index["application"])
            + end_element(index["manifest"]))
    pool = string_pool(strings)
    resource = resource_map(manifest_attributes + uses_sdk_attributes + application_attributes)
    return struct.pack("<HHI", 0x0003, 8, 8 + len(pool) + len(resource) + len(body)) + pool + resource + body


def length_prefixed(*values):
    return b"".join(struct.pack("<I", len(value)) + value for value in values)


def v2_signing_block(certificate):
    signed_data = length_prefixed(b"\x00" * 4, length_prefixed(certificate), b"")
    signer = length_prefixed(signed_data, b"\x00" * 8, b"\x00" * 8)
    value = length_prefixed(signer)
    pair = struct.pack("<Q", len(value) + 4) + struct.pack("<I", 0x7109871A) + value
    block = struct.pack("<Q", len(pair) + 8) + pair + struct.pack("<Q", len(pair) + 8)
    return block + b"APK Sig Block 42"


def build_zip(entries, signing_block):
    """按真实 APK 的字节顺序组装：本地条目 → 签名块 → 中央目录 → EOCD。"""
    local, central, offset = b"", b"", 0
    for name, payload in entries:
        raw = name.encode("utf-8")
        crc = zlib.crc32(payload) & 0xFFFFFFFF
        header = struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, 0x0800, 0, 0, 0,
                             crc, len(payload), len(payload), len(raw), 0)
        local += header + raw + payload
        central += struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 20, 0x0800, 0, 0, 0,
                               crc, len(payload), len(payload), len(raw), 0, 0, 0, 0, 0, offset) + raw
        offset += len(header) + len(raw) + len(payload)
    directory = offset + len(signing_block)
    archive = local + signing_block + central
    archive += struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(entries), len(entries),
                           len(central), directory, 0)
    return archive


def build_apk(path, package, version_code, version_name, min_sdk, target_sdk, compile_sdk,
              debuggable, assets, certificate=None, legacy_launcher=False):
    certificate = certificate if certificate is not None else certificate_der()
    entries = [("AndroidManifest.xml",
                android_manifest(package, version_code, version_name, min_sdk, target_sdk,
                                 compile_sdk, debuggable))]
    entries += sorted(assets.items())
    entries.append(("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\nCreated-By: dsha-ci-fixture\r\n\r\n"))
    entries.append(("classes.dex", b"dex\n035\x00" + b"\x00" * 32))
    entries.append(("lib/arm64-v8a/libproot.so", b"\x7fELF" + b"\x00" * 60))
    entries.append(("lib/arm64-v8a/libprootloader.so", b"\x7fELF" + b"\x00" * 60))
    if legacy_launcher:
        entries.append(("lib/arm64-v8a/libproot_legacy.so", b"\x7fELF" + b"\x00" * 60))
    path = Path(path)
    path.write_bytes(build_zip(entries, v2_signing_block(certificate)))
    return path


def recovery_payload(lock, archives, files, agent, overlays, overlay_inputs, launchers):
    payload = {
        "schema": 1,
        "dshVersion": lock["dshVersion"],
        "archives": [{key: row[key] for key in ("asset", "sha256")} for row in lock["archives"]],
        "agentSha256": digest(agent),
        "files": files,
        "profileHashes": {},
        "overlays": overlays,
        "overlayInputs": overlay_inputs,
        "launcherHashes": launchers,
        "expandedBytes": sum(row["bytes"] for row in files),
    }
    payload["id"] = digest(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode())
    return payload


class FixtureRepo:
    """一个临时 DSHA 根：build.gradle 与实验身份文件来自真实仓库，其余按夹具字节重算摘要。"""

    def __init__(self, root, real_root, version_code=190, version_name="0.2.38-test-dsh0.2.0-rc.2",
                 target_sdk=37, package="com.dsh.clienu.SunsetRNE"):
        self.root = Path(root)
        self.real_root = Path(real_root)
        self.version_code = version_code
        self.version_name = version_name
        self.target_sdk = target_sdk
        self.package = package
        self.asset_bytes = {}
        self.apks = {}
        self.descriptor = {}
        self.lock = {}

    def write(self, name, payload):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(payload if isinstance(payload, bytes) else payload.encode("utf-8"))
        return path

    def build(self):
        build_gradle = (self.real_root / "app/build.gradle").read_text(encoding="utf-8")
        # 夹具版本号与真实 build.gradle 解耦：只替换版本字段，构造自己的源事实。
        import re
        build_gradle = re.sub(r"versionCode\s+\d+", "versionCode %d" % self.version_code, build_gradle, count=1)
        build_gradle = re.sub(r'versionName\s+"[^"]+"', 'versionName "%s"' % self.version_name,
                              build_gradle, count=1)
        self.write("app/build.gradle", build_gradle)
        self.write("tools/recovery-runtime/lock.json", json.dumps(
            {"schema": 1, "dshVersion": "0.2.0-rc.2", "baseVersion": "10", "archives": [
                {"asset": "recovery-rootfs.bin", "sha256": digest(b"recovery rootfs seed")},
                {"asset": "recovery-dsh-runtime.bin", "sha256": digest(b"recovery dsh seed")},
            ]}, ensure_ascii=False, indent=1) + "\n")

        inputs = {}
        for name in INPUT_ASSETS:
            payload = ("fixture input " + name).encode()
            self.asset_bytes["assets/" + name] = payload
            inputs[name] = digest(payload)
        # 打包约定：aapt 不能把 .tar.gz 当归档解开，源资产以 .bin 落地。
        for name in ("glibc-python.tar.gz", "adb-wheels.tar.gz"):
            payload = ("fixture packaged " + name).encode()
            self.asset_bytes["assets/" + name.replace(".tar.gz", ".bin")] = payload
            inputs[name.replace(".tar.gz", ".bin")] = digest(payload)
        for name in ("offline-rootfs.bin", "dsh-runtime.bin"):
            self.asset_bytes["assets/" + name] = inputs[name].encode()
            inputs[name] = digest(self.asset_bytes["assets/" + name])
        for name in ("ubuntu-tools.bin", "pnpm-runtime.bin", "python-support.bin"):
            self.asset_bytes["assets/" + name] = (name + " fixture").encode()
            inputs[name] = digest(self.asset_bytes["assets/" + name])
        for name in ("managed-runtime-inputs.json",):
            self.asset_bytes["assets/" + name] = json.dumps(
                {"fixture": name, "digest": inputs[name]}, sort_keys=True).encode()
            inputs[name] = digest(self.asset_bytes["assets/" + name])
        inputs.pop("runtime-descriptor.json", None)
        inputs["runtime-tools.json"] = digest(self.asset_bytes["assets/runtime-tools.json"])
        self.write("app/src/main/assets/runtime-descriptor.json", json.dumps(
            {"version": 1, "runtimeId": digest(b"fixture-runtime"), "baseVersion": "10",
             "dshVersion": "0.2.0-rc.2", "inputs": inputs}, ensure_ascii=False, indent=1) + "\n")
        self.descriptor = json.loads((self.root / "app/src/main/assets/runtime-descriptor.json").read_text())
        self.asset_bytes["assets/runtime-descriptor.json"] = (self.root / "app/src/main/assets/runtime-descriptor.json").read_bytes()
        self.lock = json.loads((self.root / "tools/recovery-runtime/lock.json").read_text())

        files = [{"path": "dsh/index.js", "sha256": digest(b"dsh index"), "bytes": 9}]
        for index in range(120):
            payload = ("recovery file %d" % index).encode()
            row = {"path": "dsh/module-%03d.js" % index, "sha256": digest(payload), "bytes": len(payload)}
            files.append(row)
            self.asset_bytes["assets/" + row["path"]] = payload
        agent = b"fixture recovery agent"
        archives = {
            "assets/recovery-rootfs.bin": b"recovery rootfs seed",
            "assets/recovery-dsh-runtime.bin": b"recovery dsh seed",
        }
        overlay_payload = b"fixture recovery overlay"
        overlay_file = "assets/recovery-overlay/fixture.js"
        files.append({"path": overlay_file[len("assets/"):], "sha256": digest(overlay_payload),
                      "bytes": len(overlay_payload)})
        overlay_input = "web-integration/fixture-input.js"
        overlay_input_payload = b"fixture overlay input"
        overlays = [{"path": overlay_file[len("assets/"):], "asset": "recovery-overlay/fixture.js",
                     "sha256": digest(overlay_payload), "bytes": len(overlay_payload),
                     "originalSha256": digest(b"upstream overlay")}]
        overlay_inputs = {overlay_input: digest(overlay_input_payload)}
        launchers = {"standard": {"libproot.so": digest(b"\x7fELF" + b"\x00" * 60),
                                   "libprootloader.so": digest(b"\x7fELF" + b"\x00" * 60)},
                     "low": {"libproot_legacy.so": digest(b"\x7fELF" + b"\x00" * 60),
                             "libprootloader.so": digest(b"\x7fELF" + b"\x00" * 60)}}
        descriptor = recovery_payload(self.lock, archives, files, agent, overlays, overlay_inputs, launchers)
        assets = dict(self.asset_bytes)
        assets.update(archives)
        assets["assets/recovery-runtime.json"] = json.dumps(descriptor, sort_keys=True).encode()
        assets["assets/recovery-agent.js"] = agent
        assets["assets/recovery-overlay/fixture.js"] = overlay_payload
        assets["assets/" + overlay_input] = overlay_input_payload
        assets["assets/recovery-asset-locations.json"] = json.dumps(
            {"schema": 1, "runtimeId": descriptor["id"], "archives": [
                {"asset": row["asset"], "sha256": row["sha256"], "source": row["asset"]}
                for row in self.lock["archives"]]}, sort_keys=True).encode()
        assets.update({name: payload for name, payload in self.asset_bytes.items()})
        assets["assets/dsh/index.js"] = b"dsh index"
        self.write("ci/experimental-test-identity.json", (self.real_root / "ci/experimental-test-identity.json")
                   .read_text(encoding="utf-8"))

        for flavor, min_sdk in (("standard", 30), ("low", 23)):
            path = self.root / ("app/build/outputs/apk/%s/debug/%s.apk" % (flavor, flavor))
            path.parent.mkdir(parents=True, exist_ok=True)
            build_apk(path, self.package, self.version_code, self.version_name + ("low" if flavor == "low" else ""),
                      min_sdk, self.target_sdk, self.target_sdk, True, assets, legacy_launcher=(flavor == "low"))
            self.apks[flavor] = path
        return self


if __name__ == "__main__":
    raise SystemExit("fixture module: import from tools/test-ci-test-package.py")
