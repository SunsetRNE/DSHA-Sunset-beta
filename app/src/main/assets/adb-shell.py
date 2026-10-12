#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# DSHA_ADB_SCRIPT_VERSION=21
"""设备 shell：原生白名单判定、有限时连接、发送后不重放、真实远端退出码。

用法：adb-shell.py [--host 本机IP] [--port 端口] [--timeout 秒] [--su] 命令
自举验证：adb-shell.py --bootstrap-verify（仅 127.0.0.1:5555 的 id，不接受附加参数或回退）
失败码：124=连接失败/超时，125=已发送但执行结果未知，126=策略拦截。
命令正常结束时，本地退出码与 [EXIT=n] 都使用远端退出码。
"""
import ipaddress
import json
import os
import re
import shlex
import socket
import stat
import sys
import threading
import time
import uuid
import importlib.util

_policy_spec = importlib.util.spec_from_file_location('dsha_device_policy', os.path.join(os.path.dirname(__file__), 'device-shell-policy.py'))
policy = importlib.util.module_from_spec(_policy_spec)
_policy_spec.loader.exec_module(policy)

KEYDIR = '/root/.dsh/adbkeys'
KEY = KEYDIR + '/adbkey'
KEYPUB = KEY + '.pub'
TRANSPORT_TIMEOUT = 4.0
CONNECT_TIMEOUT = 25.0
COMMAND_TIMEOUT = 90.0

# 桥端点发现：端口文件与 .bridge_token 同数据根，容器里由启动器 export DSH_HOME。
BRIDGE_HOST = '127.0.0.1'
BRIDGE_PORT_DEFAULT = 3090
BRIDGE_PORT_NAME = '.bridge_port'
BRIDGE_TOKEN_NAME = '.bridge_token'
# 契约文件上限，与原生 BridgeEndpointFiles.READ_LIMIT 一致（端口最多 5 位 + 换行）。
BRIDGE_PORT_LIMIT = 16


class BridgeUnavailable(Exception):
    """端口发现文件存在但不可用：只允许 fail-closed，不回退旧默认，也不改试别的桥。"""


class ConnectFail(Exception):
    """命令尚未发送，允许重新发现地址。"""


class ExecutionUnknown(Exception):
    """命令可能已执行，禁止自动重放。"""



class ShellResult:
    def __init__(self, output, exit_code):
        self.output = output
        self.exit_code = exit_code


def atomic_text(path, text):
    tmp = path + '.' + uuid.uuid4().hex + '.tmp'
    try:
        with open(tmp, 'w', encoding='utf-8') as f:
            f.write(text)
        os.replace(tmp, path)
    finally:
        try:
            os.unlink(tmp)
        except OSError:
            pass


def local_ips():
    """只枚举本机接口，不向公网地址探路。"""
    found = []
    try:
        import subprocess
        rows = json.loads(subprocess.check_output(
            ['ip', '-j', '-4', 'address', 'show'], timeout=3,
            stderr=subprocess.DEVNULL).decode())
        found = [a['local'] for r in rows for a in r.get('addr_info', [])
                 if a.get('family') == 'inet']
    except Exception:
        try:
            import fcntl
            import struct
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                for _, name in socket.if_nameindex():
                    try:
                        data = fcntl.ioctl(sock.fileno(), 0x8915,
                            struct.pack('256s', name.encode()[:15]))
                        found.append(socket.inet_ntoa(data[20:24]))
                    except OSError:
                        pass
        except Exception:
            pass
    return list(dict.fromkeys(a for a in found if a and not a.startswith('127.')))


def candidate_hosts(preferred=''):
    hosts = local_ips()
    if preferred:
        ipaddress.ip_address(preferred)
        hosts.insert(0, preferred)
    hosts.append('127.0.0.1')
    return list(dict.fromkeys(hosts))


def discover_endpoints(service_type, timeout_s=4, allowed_hosts=None):
    """保留主机/端口关联，只接受本机；所有退出路径都停止 mDNS。"""
    allowed = set(candidate_hosts() if allowed_hosts is None else allowed_hosts)
    found = {}
    lock = threading.Lock()
    zc = browser = None
    until = time.monotonic() + timeout_s
    try:
        from zeroconf import Zeroconf, ServiceBrowser, ServiceListener
        class Listener(ServiceListener):
            def add_service(self, client, type_, name):
                remaining = until - time.monotonic()
                if remaining <= 0:
                    return
                info = client.get_service_info(type_, name, timeout=max(1, int(min(remaining, 1) * 1000)))
                if info is None or not 1 <= info.port <= 65535:
                    return
                addresses = [a for a in info.parsed_addresses() if a in allowed]
                if addresses:
                    with lock:
                        found[name] = [(a, info.port) for a in addresses]
            def update_service(self, client, type_, name):
                self.remove_service(client, type_, name)
                self.add_service(client, type_, name)
            def remove_service(self, client, type_, name):
                with lock:
                    found.pop(name, None)
        zc = Zeroconf()
        browser = ServiceBrowser(zc, service_type, Listener())
        time.sleep(max(0, until - time.monotonic()))
        with lock:
            return list(dict.fromkeys(ep for name in sorted(found) for ep in found[name]))
    except Exception:
        return []
    finally:
        if browser is not None:
            try:
                browser.cancel()
            except Exception:
                pass
        if zc is not None:
            try:
                zc.close()
            except Exception:
                pass


def load_port_history():
    try:
        with open(KEYDIR + '/connect_port_history') as f:
            values = f.read(1024).split()
        return list(dict.fromkeys(int(v) for v in values if v.isdigit() and 1 <= int(v) <= 65535))[:5]
    except Exception:
        return []


def remember_endpoint(host, port):
    # 只有握手/命令已验证的地址才写入，发现失败不能污染可用端口。
    try:
        atomic_text(KEYDIR + '/connect_host', host)
        atomic_text(KEYDIR + '/connect_port', str(port))
        history = [port] + [p for p in load_port_history() if p != port]
        atomic_text(KEYDIR + '/connect_port_history', '\n'.join(map(str, history[:5])) + '\n')
    except OSError:
        pass


def frame_command(cmd, marker):
    # 子 shell 隔离 exit/exec；随机尾部标记避免普通输出碰撞。
    return '/system/bin/sh -c ' + shlex.quote(cmd) + "; __dsha_rc=$?; printf '\\n" + marker + "%s\\n' \"$__dsha_rc\""


def parse_shell_result(raw, marker):
    out = raw.decode('utf-8', 'replace') if isinstance(raw, bytes) else str(raw)
    match = re.search(r'\r?\n' + re.escape(marker) + r'([0-9]{1,3})\r?\n?\Z', out)
    if match is None or int(match.group(1)) > 255:
        raise ExecutionUnknown('未收到完整退出码；命令可能已执行，不会自动重试')
    return ShellResult(out[:match.start()], int(match.group(1)))


def bridge_root():
    """凭据与端口发现文件必须同源；容器里由启动器 export DSH_HOME。"""
    try:
        home = os.environ.get('DSH_HOME')
    except Exception:
        home = None
    if isinstance(home, str) and home.strip() not in ('', '/'):
        return home.strip().rstrip('/')
    return '/root/.dsh'


def read_bridge_port(path=None):
    """读取本实例桥端口（每次请求重读，跟上桥重启后的端口变化）。

    契约（同原生 BridgeEndpointFiles）：≤16 字节的普通文件，内容只有 ASCII 十进制端口
    加一个可选换行。只有真正「目录里没有这一项」才回退旧默认 3090（老 App 不写这个
    文件）；文件存在但为 0、坏格式、越界、非 ASCII、带垃圾尾巴、是目录/符号链接（含
    悬空链接）或读不动都算「端点未知」，必须 fail-closed —— 同机可能有第二个 DSHA
    实例，猜一个端口就等于把 token 交给别人的桥。
    这里用低层 os.open/lstat/fstat 以便把「缺目录项」与「打开失败、类型不符、超长」
    分开判定（也被 main() 的隔离测试的 open 补丁绕开）。
    """
    target = path or os.path.join(bridge_root(), BRIDGE_PORT_NAME)
    try:
        entry = os.lstat(target)
    except FileNotFoundError:
        # 只有 lstat 都看不到这一项才是「文件不存在」；悬空符号链接的 ENOENT 有目录项。
        return BRIDGE_PORT_DEFAULT
    except OSError as error:
        raise BridgeUnavailable('桥端口文件不可读（%s）' % type(error).__name__) from error
    if not stat.S_ISREG(entry.st_mode):
        raise BridgeUnavailable('桥端口文件不是普通文件')
    try:
        flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0)
        descriptor = os.open(target, flags)
    except FileNotFoundError as error:
        # 目录项在但打开拿不到（悬空链接、竞态删除）：不是「文件不存在」，不能回退 3090。
        raise BridgeUnavailable('桥端口文件不可读（%s）' % type(error).__name__) from error
    except OSError as error:
        raise BridgeUnavailable('桥端口文件不可读（%s）' % type(error).__name__) from error
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_size < 1 or info.st_size > BRIDGE_PORT_LIMIT:
            raise BridgeUnavailable('桥端口文件大小或类型不符合契约')
        raw = b''
        while len(raw) < info.st_size:
            chunk = os.read(descriptor, info.st_size - len(raw))
            if not chunk:
                break
            raw += chunk
        if len(raw) != info.st_size:
            raise BridgeUnavailable('桥端口文件读取不完整')
    except OSError as error:
        raise BridgeUnavailable('桥端口文件不可读（%s）' % type(error).__name__) from error
    finally:
        try:
            os.close(descriptor)
        except OSError:
            pass
    try:
        text = raw.decode('ascii')
    except UnicodeDecodeError as error:
        raise BridgeUnavailable('桥端口文件不是 ASCII 十进制端口') from error
    # 整份内容必须就是端口：'3091\n' 之后再有空白/垃圾都不是契约文件；不做 Unicode strip。
    if re.fullmatch(r'[0-9]{1,5}\n?', text) is None:
        raise BridgeUnavailable('桥端口文件格式无效')
    port = int(text.rstrip('\n'))
    if port == 0:
        # 原生在启动中 / 绑定失败 / 停止后都会发布 0；那是「明确不可用」，不是坏文件。
        raise BridgeUnavailable('桥已发布为不可用（0），可能正在启动或已停止')
    if not 1 <= port <= 65535:
        raise BridgeUnavailable('桥端口文件不在 1—65535')
    return port


def bridge_credentials():
    """返回本实例桥的 (X-Token, 端口)：任一步失败都不得回退旧默认或改试别的端口。"""
    try:
        port = read_bridge_port()
    except BridgeUnavailable as error:
        raise policy.Blocked('桥端点不可用（%s），命令未发送' % error) from error
    try:
        with open(os.path.join(bridge_root(), BRIDGE_TOKEN_NAME)) as source:
            token = source.read().strip()
    except OSError as error:
        raise policy.Blocked('设备桥未准备好，请打开 DSHA 后重试') from error
    if not token:
        raise policy.Blocked('设备桥未准备好，请打开 DSHA 后重试')
    return token, port


def bridge_url(path, port):
    """端口只来自已校验的整数，绝不拼接调用方输入。"""
    if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
        raise BridgeUnavailable('桥端口不在 1—65535')
    return 'http://' + BRIDGE_HOST + ':' + str(port) + path


def request_native_vscreen_commit(ticket):
    """Consume the one-use managed launch authority immediately before the ADB shell send."""
    import urllib.request
    import urllib.parse
    try:
        token, port = bridge_credentials()
        query = urllib.parse.urlencode({'ticket': ticket})
        request = urllib.request.Request(bridge_url('/device/vscreen/commit', port) + '?' + query,
            headers={'X-Token': token})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=10) as response:
            value = json.loads(response.read(65536)).get('result')
        if value != 'VSCREEN_START_COMMITTED':
            raise policy.Blocked('虚拟屏启动许可已撤销或已使用')
    except policy.Blocked:
        raise
    except Exception as error:
        # A lost commit response may mean the one-use ticket was consumed. Never send or retry.
        raise ExecutionUnknown('虚拟屏启动许可结果未知，命令未重放（' + type(error).__name__ + '）') from error


def run_on_endpoint(device_cls, signer_cls, cmd, host, port, deadline, command_timeout=COMMAND_TIMEOUT):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise ConnectFail('连接总时限已到，命令尚未发送')
    dev = None
    try:
        try:
            with open(KEYPUB, 'rb') as f:
                public = f.read().strip()
            with open(KEY, 'rb') as f:
                private = f.read()
            signer = signer_cls(public, private)
            dev = device_cls(host, port, default_transport_timeout_s=min(TRANSPORT_TIMEOUT, remaining))
            connected = dev.connect(rsa_keys=[signer], transport_timeout_s=min(TRANSPORT_TIMEOUT, remaining),
                auth_timeout_s=min(8, remaining), read_timeout_s=min(8, remaining), tls_priv_pem=private)
            if not connected:
                raise ConnectionError('ADB 握手未完成')
            if time.monotonic() >= deadline:
                raise TimeoutError('连接总时限已到')
        except Exception as e:
            raise ConnectFail('%s:%d %s: %s' % (host, port, type(e).__name__, str(e)[:200])) from e
        marker = '__DSHA_EXIT_' + uuid.uuid4().hex + '__='
        # 从这里开始即使异常也禁止换地址重放，包括不兼容库抛出的 TypeError。
        try:
            def shell(command):
                marker = '__DSHA_EXIT_' + uuid.uuid4().hex + '__='
                if isinstance(cmd, dict) and cmd.get('capability') == 'sms.read':
                    # A connection may outlive the user's native permission. Recheck
                    # the same canonical query at the last pre-send boundary; a new
                    # plan must not silently change its user or requested fields.
                    current = request_device_plan(command, bool(cmd.get('su')))
                    if (current.get('authorization') != 'remembered'
                            or any(current.get(key) != cmd.get(key)
                                   for key in ('version', 'kind', 'argv', 'capability', 'authorization'))):
                        raise policy.Blocked('敏感查询授权或内容已变化，命令未发送')
                if isinstance(cmd, dict) and cmd.get('kind') == 'VIRTUAL_SCREEN':
                    if cmd.get('_dsha_sent'):
                        raise policy.Blocked('受管虚拟屏启动只允许发送一次')
                    ticket = cmd.get('nativeTicket', '')
                    if not re.fullmatch(r'[a-f0-9]{48}', ticket):
                        raise policy.Blocked('缺少受管虚拟屏启动许可')
                    request_native_vscreen_commit(ticket)
                    cmd['_dsha_sent'] = True
                if isinstance(cmd, dict) and cmd.get('su'):
                    command = 'su -c ' + shlex.quote(command)
                raw = dev.shell(frame_command(command, marker),
                    transport_timeout_s=min(TRANSPORT_TIMEOUT, command_timeout),
                    read_timeout_s=min(8, command_timeout), timeout_s=command_timeout)
                return parse_shell_result(raw, marker)
            result = policy.execute(cmd, shell, ShellResult) if isinstance(cmd, dict) else shell(cmd)
        except policy.Blocked:
            raise
        except Exception as e:
            raise ExecutionUnknown('%s:%d 返回中断（%s）；命令可能已执行，请先检查设备，未自动重试'
                                   % (host, port, type(e).__name__)) from e
        remember_endpoint(host, port)
        return result
    finally:
        if dev is not None:
            try:
                dev.close()
            except Exception:
                pass


def connect_with_retry(device_cls, signer_cls, cmd, port, host='',
                       connect_timeout=CONNECT_TIMEOUT, command_timeout=COMMAND_TIMEOUT):
    deadline = time.monotonic() + connect_timeout
    hosts = candidate_hosts(host)
    tried, errors = set(), []
    def attempt(endpoints):
        for addr, p in endpoints:
            if (addr, p) in tried or not 1 <= p <= 65535 or time.monotonic() >= deadline:
                continue
            tried.add((addr, p))
            try:
                return run_on_endpoint(device_cls, signer_cls, cmd, addr, p, deadline, command_timeout)
            except ConnectFail as e:
                errors.append(str(e))
        return None
    result = attempt([(h, port) for h in hosts] if port else [])
    if result is not None:
        return result
    remaining = deadline - time.monotonic()
    if remaining > 0:
        result = attempt(discover_endpoints('_adb-tls-connect._tcp.local.', min(4, remaining), hosts))
        if result is not None:
            return result
    result = attempt([(h, p) for p in load_port_history() + [5555] for h in hosts])
    if result is not None:
        return result
    raise ConnectFail('命令尚未发送；请检查无线调试与配对授权。尝试记录：\n' + '\n'.join(errors[-8:]))


def request_device_plan(cmd, use_su=False):
    """所有设备命令均经过同一原生白名单；不接受 DSH_INTERNAL 或关闭确认来绕过。"""
    import urllib.request
    import urllib.parse
    try:
        token, port = bridge_credentials()
        query = urllib.parse.urlencode({'cmd': cmd, 'su': '1' if use_su else '0'})
        request = urllib.request.Request(bridge_url('/device/plan', port) + '?' + query, headers={'X-Token': token})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=75) as response:
            value = json.loads(response.read(1024 * 1024)).get('result')
        if not isinstance(value, str) or not value.startswith('{'):
            raise policy.Blocked(str(value or '原生策略未就绪'))
        plan = json.loads(value)
        if plan.get('version') != 1 or plan.get('kind') not in ('READ', 'FILE', 'STOP'):
            raise policy.Blocked(plan.get('reason') or '命令未获策略允许')
        plan['su'] = use_su
        return plan
    except policy.Blocked:
        raise
    except Exception as error:
        raise policy.Blocked('设备策略桥不可用，命令未发送；请打开或更新 DSHA（' + type(error).__name__ + '）') from error


def request_native_vscreen_start(cmd, ticket):
    """Get a typed plan only while the native manager holds the matching launch lease."""
    import urllib.request
    import urllib.parse
    try:
        token, port = bridge_credentials()
        query = urllib.parse.urlencode({'cmd': cmd, 'ticket': ticket})
        request = urllib.request.Request(bridge_url('/device/vscreen/start', port) + '?' + query,
            headers={'X-Token': token})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=15) as response:
            envelope = json.loads(response.read(1024 * 1024))
        value = json.loads(envelope['result'])
        plan = value.get('plan')
        if (value.get('state') != 'adb' or not isinstance(plan, dict)
                or plan.get('kind') != 'VIRTUAL_SCREEN' or plan.get('nativeTicket') != ticket
                or plan.get('nativeAuthorization') != 'managed-vscreen-start'):
            raise policy.Blocked('原生虚拟屏启动许可没有授权此操作')
        return plan
    except policy.Blocked:
        raise
    except Exception as error:
        raise policy.Blocked('无法取得原生虚拟屏启动许可，命令未发送（' + type(error).__name__ + '）') from error


def parse_args(args):
    port, host, timeout, connect_timeout, use_su, vscreen_launch = 0, '', COMMAND_TIMEOUT, CONNECT_TIMEOUT, False, False
    explicit_endpoint = False
    while args:
        option = args[0]
        if option == '--su':
            use_su = True
            args = args[1:]
        elif option == '--vscreen-launch':
            if vscreen_launch:
                raise ValueError('虚拟屏启动标记重复')
            vscreen_launch = True
            args = args[1:]
        elif option in ('--port', '--host', '--timeout', '--connect-timeout'):
            if len(args) < 2:
                raise ValueError(option + ' 缺少参数')
            value, args = args[1], args[2:]
            if option == '--host':
                host = str(ipaddress.ip_address(value))
                explicit_endpoint = True
            elif option == '--port':
                port = int(value)
                explicit_endpoint = True
                if not 1 <= port <= 65535:
                    raise ValueError('端口应在 1—65535')
            else:
                number = int(value)
                if not 1 <= number <= 180:
                    raise ValueError('超时应在 1—180 秒')
                if option == '--timeout': timeout = number
                else: connect_timeout = number
        elif option == '--':
            args = args[1:]
            break
        else:
            break
    if vscreen_launch and (use_su or explicit_endpoint):
        raise ValueError('受管虚拟屏启动不能覆盖设备通道或 Root 身份')
    return port, host, timeout, connect_timeout, use_su, vscreen_launch, (args[0] if len(args) == 1 else shlex.join(args)) if args else 'id'


def read_vscreen_ticket(stream=None):
    """Read exactly one 48-byte lease from the private parent pipe, then require EOF."""
    source = sys.stdin.buffer if stream is None else stream
    raw = source.read(49)
    if len(raw) != 48 or not re.fullmatch(rb'[a-f0-9]{48}', raw):
        raise ValueError('无效的受管虚拟屏启动许可')
    return raw.decode('ascii')


def request_native_execution(cmd, use_su=False, force_adb=False):
    """原生层先选 root/Shizuku；仅收到明确的 ADB 计划才连接，不重放未知结果。"""
    import urllib.request
    import urllib.parse
    token, port = bridge_credentials()
    query = urllib.parse.urlencode({'cmd': cmd, 'su': '1' if use_su else '0', 'adb': '1' if force_adb else '0'})
    request = urllib.request.Request(bridge_url('/device/execute', port) + '?' + query, headers={'X-Token': token})
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=155) as response:
            envelope = json.loads(response.read(1024 * 1024))
        raw = envelope.get('result') if isinstance(envelope, dict) else None
        if isinstance(raw, str) and raw.strip() == '[UNAUTHORIZED]':
            # 桥只属于启动它的那个实例：.bridge_port 指向的是本实例的桥，若它仍拒了本容器的
            # token，通常是对端进程已换成另一个实例或凭据已轮换，而报错过去只剩一个
            # JSONDecodeError，看不出真正原因。
            raise policy.Blocked(
                '本实例桥（.bridge_port 指定的端口）拒绝了这个 token：请确认桥属于当前实例且凭据未轮换')
        value = json.loads(raw)
        if value.get('state') == 'completed':
            code = value.get('exit')
            if not isinstance(code, int) or not 0 <= code <= 255 or not isinstance(value.get('output'), str):
                raise ValueError('invalid execution result')
            return ShellResult(value['output'], code)
        plan = value.get('plan')
        if value.get('state') != 'adb' or not isinstance(plan, dict) or plan.get('version') != 1:
            raise ValueError('missing explicit ADB plan')
        if plan.get('kind') not in ('READ', 'FILE', 'STOP'):
            raise ValueError('invalid ADB plan')
        return plan
    except policy.Blocked:
        raise
    except Exception as error:
        raise ExecutionUnknown('设备桥响应不完整，命令可能已执行，不会切换通道重试（' + type(error).__name__ + '）') from error


def main():
    bootstrap_verify = sys.argv[1:2] == ['--bootstrap-verify']
    try:
        if bootstrap_verify:
            if len(sys.argv) != 2:
                raise ValueError('--bootstrap-verify 不接受附加参数')
            # 专用入口不改变 parse_args 的七元组契约，也不开放端点或命令覆盖。
            port, host, timeout, connect_timeout, use_su, vscreen_launch, cmd = (
                5555, '127.0.0.1', 8, 8, False, False, 'id')
        else:
            port, host, timeout, connect_timeout, use_su, vscreen_launch, cmd = parse_args(sys.argv[1:])
        launch_ticket = read_vscreen_ticket() if vscreen_launch else ''
    except ValueError as e:
        print('INVALID_ARGUMENT: %s\n[EXIT=2]' % e)
        return 2
    try:
        plan = request_native_vscreen_start(cmd, launch_ticket) if vscreen_launch else request_native_execution(cmd, use_su, bool(port or host))
        if bootstrap_verify:
            if isinstance(plan, ShellResult):
                if plan.exit_code == 0:
                    raise ExecutionUnknown('未取得固定端点的 ADB 计划，无法确认自举会话')
            elif (not isinstance(plan, dict) or plan.get('version') != 1
                    or plan.get('kind') != 'READ' or plan.get('argv') != ['id']
                    or plan.get('su', False)):
                raise policy.Blocked('自举验证只接受无 su 的原生 id 只读计划')
    except policy.Blocked as error:
        print('[POLICY_BLOCKED] %s\n[EXIT=126]' % error)
        return 126
    except ExecutionUnknown as error:
        print('EXECUTION_UNKNOWN: %s\n[EXIT=125]' % error)
        return 125
    if isinstance(plan, ShellResult):
        print(plan.output)
        return plan.exit_code
    if not (os.path.isfile(KEY) and os.path.isfile(KEYPUB)):
        print('NO_KEY: 请到设置 → 设备能力授权完成 ADB 无线配对\n[EXIT=1]')
        return 1
    try:
        from adb_shell_wifi.adb_device import AdbDeviceTls
        from adb_shell_wifi.auth.sign_pythonrsa import PythonRSASigner
    except ImportError:
        print('DEPS_MISSING: ADB 依赖未就绪，请重新打开配对页准备环境\n[EXIT=1]')
        return 1
    if not port:
        try:
            with open(KEYDIR + '/connect_port') as f:
                port = int(f.read(20).strip())
            if not 1 <= port <= 65535: port = 0
        except (OSError, ValueError):
            port = 0
    if not host:
        try:
            with open(KEYDIR + '/connect_host') as f:
                saved_host = f.read(128).strip()
            if saved_host in candidate_hosts(): host = saved_host
        except OSError:
            pass
    try:
        if bootstrap_verify:
            # 固定端点只尝试一次；ConnectFail/ExecutionUnknown 均直接返回，禁止发现或重放。
            result = run_on_endpoint(AdbDeviceTls, PythonRSASigner, plan,
                host, port, time.monotonic() + connect_timeout, timeout)
        else:
            result = connect_with_retry(AdbDeviceTls, PythonRSASigner, plan, port, host, connect_timeout, timeout)
    except policy.Blocked as error:
        print('[POLICY_BLOCKED] %s\n[EXIT=126]' % error)
        return 126
    except ConnectFail as e:
        print('CONNECT_FAIL: %s\n[EXIT=124]' % e)
        return 124
    except ExecutionUnknown as e:
        print('EXECUTION_UNKNOWN: %s\n[EXIT=125]' % e)
        return 125
    sys.stdout.write(result.output)
    if result.output and not result.output.endswith('\n'): print()
    print('[EXIT=%d]' % result.exit_code)
    return result.exit_code


if __name__ == '__main__':
    sys.exit(main())
