#!/usr/bin/env python3
"""隔离 ADB 回归：假 socket/ADB/mDNS/凭据，不连接设备，不读取用户配置。"""
import contextlib
import errno
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import types
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch, Mock

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ASSETS / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


adb = load('adb_test_shell', 'adb-shell.py')
pair = load('adb_test_pair', 'adb-pair.py')


class FlowTest(unittest.TestCase):
    def setUp(self):
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        # 漏掉任何隔离都会直接失败，而不是偷偷连接、执行进程或读取实际凭据。
        self.stack.enter_context(patch('socket.socket', side_effect=AssertionError('真实 socket 被禁止')))
        self.stack.enter_context(patch('socket.create_connection', side_effect=AssertionError('真实连接被禁止')))
        self.stack.enter_context(patch('subprocess.Popen', side_effect=AssertionError('真实子进程被禁止')))
        self.stack.enter_context(patch.object(adb, 'local_ips', return_value=['192.0.2.1']))
        self.stack.enter_context(patch.object(adb, 'open', side_effect=lambda *a, **k: io.BytesIO(b'synthetic'), create=True))
        self.stack.enter_context(patch.object(adb, 'remember_endpoint'))
        self.stack.enter_context(patch.object(adb, 'load_port_history', return_value=[]))
        self.discovery = self.stack.enter_context(patch.object(adb, 'discover_endpoints', return_value=[]))
        self.stack.enter_context(patch.object(pair, 'adb', adb))
        self.stack.enter_context(patch.object(pair, 'ensure_key'))
        self.stack.enter_context(patch.object(pair, 'check_deps', return_value=True))
        self.stack.enter_context(patch.object(pair, 'open', side_effect=lambda *a, **k: io.StringIO('39000'), create=True))
        self.events = []
        self.connect_error = None
        self.shell_error = None
        self.remote_output = '结果\n'
        self.remote_exit = 0
        self.framed = True
        test = self
        class Device:
            def __init__(self, host, port, default_transport_timeout_s=None):
                test.assertGreater(default_transport_timeout_s, 0)
                self.host, self.port = host, port
                test.events.append(('new', host, port))
            def connect(self, **kwargs):
                test.events.append(('connect', self.host, self.port))
                for name in ('transport_timeout_s', 'read_timeout_s', 'auth_timeout_s'):
                    test.assertGreater(kwargs[name], 0)
                    test.assertLessEqual(kwargs[name], 8)
                if test.connect_error:
                    error = test.connect_error(self.host, self.port)
                    if error: raise error
                return True
            def shell(self, command, **kwargs):
                test.events.append(('shell', self.host, self.port))
                for name in ('transport_timeout_s', 'read_timeout_s', 'timeout_s'):
                    test.assertGreater(kwargs[name], 0)
                if test.shell_error: raise test.shell_error
                marker = re.search(r'__DSHA_EXIT_[0-9a-f]+__=', command).group()
                return test.remote_output + ('\n' + marker + str(test.remote_exit) + '\n' if test.framed else '')
            def close(self): test.events.append(('close', self.host, self.port))
        self.Device = Device
        self.Signer = lambda *a: object()

    def execute(self):
        return adb.connect_with_retry(self.Device, self.Signer, 'input tap 10 10', 39000)

    def sensitive_plan(self):
        return {'version': 1, 'kind': 'READ',
                'argv': ['content', 'query', '--uri', 'content://sms', '--where', '1=0', '--user', '0'],
                'capability': 'sms.read', 'authorization': 'remembered', 'su': False}

    def test_sensitive_revocation_during_connect_prevents_dispatch(self):
        granted = [True]
        def connected(host, port):
            granted[0] = False
        def current_plan(*args, **kwargs):
            if not granted[0]:
                raise adb.policy.Blocked('synthetic authorization revoked')
            return self.sensitive_plan()
        self.connect_error = connected
        with patch.object(adb, 'request_device_plan', side_effect=current_plan):
            with self.assertRaises(adb.policy.Blocked):
                adb.connect_with_retry(self.Device, self.Signer, self.sensitive_plan(), 39000)
        self.assertFalse(any(e[0] == 'shell' for e in self.events))
        self.assertEqual(1, sum(e[0] == 'close' for e in self.events))
        self.discovery.assert_not_called()

    def test_changed_sensitive_plan_never_dispatches(self):
        changed = self.sensitive_plan()
        changed['argv'] = [*changed['argv'][:-1], '10']
        with patch.object(adb, 'request_device_plan', return_value=changed):
            with self.assertRaises(adb.policy.Blocked):
                adb.connect_with_retry(self.Device, self.Signer, self.sensitive_plan(), 39000)
        self.assertFalse(any(e[0] == 'shell' for e in self.events))

    def test_current_sensitive_plan_checked_after_connect_before_single_send(self):
        def current_plan(command, use_su=False):
            self.assertTrue(any(e[0] == 'connect' for e in self.events))
            self.assertFalse(any(e[0] == 'shell' for e in self.events))
            self.assertFalse(use_su)
            self.assertIn('1=0', command)
            return self.sensitive_plan()
        with patch.object(adb, 'request_device_plan', side_effect=current_plan) as check:
            result = adb.connect_with_retry(self.Device, self.Signer, self.sensitive_plan(), 39000)
        self.assertEqual(0, result.exit_code)
        check.assert_called_once()
        self.assertEqual(1, sum(e[0] == 'shell' for e in self.events))

    def test_sensitive_revalidation_failure_closes_without_send_or_retry(self):
        with patch.object(adb, 'request_device_plan', side_effect=adb.policy.Blocked('synthetic bridge unavailable')):
            with self.assertRaises(adb.policy.Blocked):
                adb.connect_with_retry(self.Device, self.Signer, self.sensitive_plan(), 39000)
        self.assertFalse(any(e[0] == 'shell' for e in self.events))
        self.assertEqual(1, sum(e[0] == 'close' for e in self.events))
        self.discovery.assert_not_called()

    def test_timeout_after_dispatch_never_replays(self):
        self.shell_error = TimeoutError('回复丢失')
        with self.assertRaises(adb.ExecutionUnknown): self.execute()
        self.assertEqual(1, sum(e[0] == 'shell' for e in self.events))
        self.assertEqual(1, sum(e[0] == 'close' for e in self.events))
        self.discovery.assert_not_called()

    def test_library_type_error_never_reexecutes_without_timeouts(self):
        self.shell_error = TypeError('不兼容库')
        with self.assertRaises(adb.ExecutionUnknown): self.execute()
        self.assertEqual(1, sum(e[0] == 'shell' for e in self.events))

    def test_connect_failure_can_retry_but_closes_failed_device(self):
        self.connect_error = lambda host, port: ConnectionRefusedError() if host == '192.0.2.1' else None
        self.assertEqual(0, self.execute().exit_code)
        self.assertEqual(2, sum(e[0] == 'close' for e in self.events))
        self.assertEqual(1, sum(e[0] == 'shell' for e in self.events))

    def test_budget_stops_connect_retries(self):
        clock = [100.0]
        def failed(host, port):
            clock[0] += 15
            return TimeoutError()
        self.connect_error = failed
        with patch.object(adb.time, 'monotonic', side_effect=lambda: clock[0]):
            with self.assertRaises(adb.ConnectFail): self.execute()
        self.discovery.assert_not_called()
        self.assertFalse(any(e[0] == 'shell' for e in self.events))

    def test_real_remote_exit_is_returned(self):
        self.remote_exit = 17
        result = self.execute()
        self.assertEqual(17, result.exit_code)
        self.assertEqual(self.remote_output, result.output)
        self.assertEqual(1, sum(e[0] == 'shell' for e in self.events))

    def test_missing_exit_marker_is_unknown_not_success(self):
        self.framed = False
        with self.assertRaises(adb.ExecutionUnknown): self.execute()
        self.discovery.assert_not_called()

    def test_exit_framing_handles_exit_exec_quotes_and_bytes(self):
        for command in ["exit 7", "exec false", "printf '%s' 'a b'", "echo x\nexit 3"]:
            wrapped = adb.frame_command(command, 'MARK=')
            self.assertTrue(wrapped.startswith('/system/bin/sh -c '))
            self.assertIn('__dsha_rc=$?', wrapped)
        result = adb.parse_shell_result(b'one\r\nMARK=255\r\n', 'MARK=')
        self.assertEqual(('one', 255), (result.output, result.exit_code))
        for raw in ['MARK=0', '\nMARK=256\n', '\nOTHER=0\n', '\nMARK=0\ntruncated']:
            with self.assertRaises(adb.ExecutionUnknown): adb.parse_shell_result(raw, 'MARK=')

    def main_fakes(self, command, root=False, disabled=False, blocked=False):
        mods = {'adb_shell_wifi': types.ModuleType('adb_shell_wifi'),
                'adb_shell_wifi.adb_device': types.SimpleNamespace(AdbDeviceTls=self.Device),
                'adb_shell_wifi.auth': types.ModuleType('auth'),
                'adb_shell_wifi.auth.sign_pythonrsa': types.SimpleNamespace(PythonRSASigner=self.Signer)}
        self.stack.enter_context(patch.dict(sys.modules, mods))
        self.stack.enter_context(patch.object(adb.sys, 'argv', ['adb-shell.py', '--host', '192.0.2.1', '--port', '39000'] + command))
        self.stack.enter_context(patch.object(adb.os, 'environ', {}))
        self.native_plan = self.stack.enter_context(patch.object(adb, 'request_native_execution',
            side_effect=adb.policy.Blocked('策略拒绝') if blocked else None,
            return_value={'version': 1, 'kind': 'READ', 'argv': ['id'], 'su': root}))
        self.stack.enter_context(patch.object(adb.os.path, 'isfile', side_effect=lambda p: p in (adb.KEY, adb.KEYPUB)))

    def test_main_uses_native_plan_and_propagates_nonzero_exit(self):
        self.main_fakes(['id'])
        self.remote_exit = 5
        with contextlib.redirect_stdout(io.StringIO()) as out:
            result = adb.main()
        self.assertEqual(5, result)
        self.assertTrue(out.getvalue().endswith('[EXIT=5]\n'))
        self.native_plan.assert_called_once_with('id', False, True)
        wrapper = (ASSETS / 'adb-setup.sh').read_text(encoding='utf-8').split("cat > /root/dsh-bin/adb-shell <<'EOF'", 1)[1].split('\nEOF', 1)[0]
        self.assertNotIn('dsh-confirm.sh', wrapper)
        self.assertIn('exec python3', wrapper)

    def test_native_denial_does_not_connect(self):
        self.main_fakes(['input tap 10 10'], blocked=True)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(126, adb.main())
        self.assertEqual([], self.events)

    def test_root_authorization_required_even_with_confirmation_disabled(self):
        self.main_fakes(['--su', 'id'], disabled=True, blocked=True)
        with contextlib.redirect_stdout(io.StringIO()): self.assertEqual(126, adb.main())
        self.assertEqual([], self.events)

    def test_root_still_requires_native_plan_when_authorized(self):
        self.main_fakes(['--su', 'id'], root=True, disabled=True)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, adb.main())
        self.native_plan.assert_called_once_with('id', True, True)

    def test_confirmation_switch_never_bypasses_native_policy(self):
        self.main_fakes(['id'], disabled=True)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, adb.main())
        self.native_plan.assert_called_once_with('id', False, True)

    def test_internal_flag_never_bypasses_native_denial(self):
        self.main_fakes(['setenforce 0'], root=True, disabled=True, blocked=True)
        with patch.dict(adb.os.environ, {'DSH_INTERNAL': '1', 'DSH_NO_CONFIRM': '1'}), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(126, adb.main())
        self.assertEqual([], self.events)

    def test_main_unknown_result_nonzero_and_no_replay(self):
        self.main_fakes(['id'])
        self.shell_error = TimeoutError()
        with contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(125, adb.main())
        self.assertIn('EXECUTION_UNKNOWN:', out.getvalue())

    def test_pairing_warning_not_connection_success_or_repeated_pair(self):
        with patch.object(pair.sys, 'argv', ['adb-pair.py', '--code', '123456', '--port', '39000']), \
             patch.object(pair, 'probe', return_value=True), patch.object(pair, 'do_pair', return_value=(True, 'PAIR_OK: 完成')) as handshake, \
             patch.object(pair, 'verify_connection', side_effect=adb.ConnectFail('端口失效')), \
             patch.object(pair.time, 'sleep'), contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(0, pair.main())
        handshake.assert_called_once()
        self.assertIn('PAIR_OK:', out.getvalue())
        self.assertIn('CONNECT_WARN:', out.getvalue())
        self.assertNotIn('CONNECT_OK:', out.getvalue())

    def test_verify_only_never_pairs(self):
        with patch.object(pair.sys, 'argv', ['adb-pair.py', '--verify-only']), \
             patch.object(pair.os.path, 'isfile', return_value=True), \
             patch.object(pair, 'verify_connection') as verify, patch.object(pair, 'do_pair') as handshake:
            self.assertEqual(0, pair.main())
        verify.assert_called_once()
        handshake.assert_not_called()

    def test_invalid_pair_input_does_not_prepare_or_pair(self):
        for code in ['12345', '1234567', '１２３４５６']:
            with patch.object(pair.sys, 'argv', ['adb-pair.py', '--code', code]), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2, pair.main())
        pair.ensure_key.assert_not_called()

    def test_script_and_wrapper_version_match_the_single_asset_contract(self):
        source = (ROOT / 'app/src/main/java/com/deepseekharness/app/bridge/AdbBridge.java').read_text(encoding='utf-8')
        version = (ASSETS/'adb-script-version').read_text(encoding='ascii').strip()
        self.assertRegex(version, r'^[0-9]+$')
        for file in ['adb-shell.py', 'adb-pair.py', 'adb-setup.sh', 'dsha-device-shell.sh']:
            markers = re.findall(r'^# DSHA_ADB_SCRIPT_VERSION=([0-9]+)$', (ASSETS / file).read_text(encoding='utf-8'), re.M)
            self.assertTrue(markers, file)
            self.assertTrue(all(value == version for value in markers), file)
        self.assertIn('bundledDeviceScriptsMatch()', source)
        self.assertNotIn('SCRIPT_VERSION =', source)

    def test_keepalive_grants_only_native_actual_package_and_missing_identity_sends_nothing(self):
        mods={'adb_shell_wifi.adb_device':types.SimpleNamespace(AdbDeviceTls=self.Device),
              'adb_shell_wifi.auth.sign_pythonrsa':types.SimpleNamespace(PythonRSASigner=self.Signer)}
        with patch.dict(sys.modules,mods), patch.object(pair.sys,'argv',['adb-pair.py','--grant-keepalive']), \
                patch.object(adb,'request_device_plan',return_value={'version':1,'kind':'READ','selfPackage':'com.dsh.clienu'}) as identity, \
                patch.object(adb,'connect_with_retry',return_value=types.SimpleNamespace(output='done',exit_code=0)) as send, \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0,pair.main());identity.assert_called_once_with('id',False)
            self.assertEqual(['/system/bin/pm','grant','com.dsh.clienu','android.permission.WRITE_SECURE_SETTINGS'],__import__('shlex').split(send.call_args.args[2]))
            send.reset_mock();identity.return_value={'version':1,'kind':'READ'}
            self.assertEqual(1,pair.main());send.assert_not_called()


class NativeRouteTest(unittest.TestCase):
    def test_root_or_shizuku_success_needs_no_adb_keys_or_dependencies(self):
        for code in (0, 17, 126):
            output = 'native result\n[EXIT=%d]' % code
            with patch.object(adb.sys, 'argv', ['adb-shell.py', 'id']), \
                 patch.object(adb, 'request_native_execution', return_value=adb.ShellResult(output, code)) as request, \
                 patch.object(adb.os.path, 'isfile', side_effect=AssertionError('不能读取 ADB 密钥')), \
                 patch.object(adb, 'connect_with_retry', side_effect=AssertionError('不能调用 ADB')), \
                 contextlib.redirect_stdout(io.StringIO()) as stdout:
                self.assertEqual(code, adb.main())
            request.assert_called_once_with('id', False, False)
            self.assertEqual(output + '\n', stdout.getvalue())

    def test_lost_native_response_never_falls_back_to_adb(self):
        with patch.object(adb.sys, 'argv', ['adb-shell.py', 'id']), \
             patch.object(adb, 'request_native_execution', side_effect=adb.ExecutionUnknown('已发送')), \
             patch.object(adb, 'connect_with_retry', side_effect=AssertionError('不能重放')), \
             contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(125, adb.main())

    def invoke_bridge(self, value=None, error=None):
        response = Mock()
        response.__enter__ = Mock(return_value=response); response.__exit__ = Mock(return_value=False)
        response.read.return_value = json.dumps({'result': json.dumps(value)}).encode()
        opener = Mock(); opener.open.return_value = response; opener.open.side_effect = error
        with patch.object(adb, 'open', return_value=io.StringIO('fake-token'), create=True), \
             patch.object(urllib.request, 'build_opener', return_value=opener):
            try:
                return adb.request_native_execution('id')
            finally:
                opener.open.assert_called_once()
                self.assertNotIn('fake-token', opener.open.call_args.args[0].full_url)

    def test_only_explicit_plan_can_trigger_adb(self):
        plan = {'version': 1, 'kind': 'READ', 'argv': ['id'], 'su': False}
        self.assertEqual(plan, self.invoke_bridge({'state': 'adb', 'plan': plan}))
        result = self.invoke_bridge({'state': 'completed', 'output': 'uid=0(root)\n[EXIT=0]', 'exit': 0})
        self.assertEqual(0, result.exit_code)
        for bad in ({'kind': 'READ', 'argv': ['id']}, {'state': 'adb'},
                    {'state': 'completed', 'output': 'truncated'},
                    {'state': 'completed', 'output': 'x', 'exit': 999}):
            with self.assertRaises(adb.ExecutionUnknown): self.invoke_bridge(bad)
        with self.assertRaises(adb.ExecutionUnknown): self.invoke_bridge(error=TimeoutError())


class DiscoveryTest(unittest.TestCase):
    def run_discovery(self, fail=False, removed=False):
        events = []
        class Zc:
            def get_service_info(self, type_, name, timeout):
                return types.SimpleNamespace(port=31000 if name == 'foreign' else 39000,
                    parsed_addresses=lambda: ['192.0.2.2'] if name == 'foreign' else ['192.0.2.1'])
            def close(self): events.append('close')
        class Browser:
            def __init__(self, zc, type_, listener):
                if fail: raise RuntimeError('发现失败')
                listener.add_service(zc, type_, 'foreign')
                listener.add_service(zc, type_, 'local')
                if removed: listener.remove_service(zc, type_, 'local')
            def cancel(self): events.append('cancel')
        module = types.SimpleNamespace(Zeroconf=Zc, ServiceBrowser=Browser, ServiceListener=object)
        with patch.dict(sys.modules, {'zeroconf': module}), patch.object(adb.time, 'sleep'), \
             patch('socket.socket', side_effect=AssertionError('真实网络被禁止')):
            endpoints = adb.discover_endpoints('_adb-tls-pairing._tcp.local.', .1, ['192.0.2.1'])
        return endpoints, events

    def test_foreign_device_port_is_rejected_and_cleanup_always_runs(self):
        self.assertEqual(([('192.0.2.1', 39000)], ['cancel', 'close']), self.run_discovery())

    def test_service_loss_removes_stale_port(self):
        self.assertEqual(([], ['cancel', 'close']), self.run_discovery(removed=True))

    def test_start_failure_still_closes_zeroconf(self):
        self.assertEqual(([], ['close']), self.run_discovery(fail=True))


class BridgePortDiscoveryTest(unittest.TestCase):
    """双实例桥的端点发现：guest 只认本实例端口，读不到就 fail-closed。

    契约：<DSH_HOME>/.bridge_port（默认 /root/.dsh）是 ASCII 十进制端口 + 换行，1—65535；
    只有真正的 ENOENT 才回退旧默认 3090；文件存在但为 0、坏格式、越界、非 ASCII、是目录
    或权限拒绝都属于「端点未知」——不发请求、不回退默认、不改试别的回环端口。
    """

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix='dsha-adb-bridge-port-')
        self.addCleanup(self.folder.cleanup)
        self.root = self.folder.name
        self.port_file = os.path.join(self.root, '.bridge_port')
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.dict(os.environ, {'DSH_HOME': self.root}))
        self.opener = Mock()
        self.stack.enter_context(patch.object(urllib.request, 'build_opener', return_value=self.opener))
        self.token_paths = []

        def open_fixture(path, *args, **kwargs):
            self.token_paths.append(str(path))
            return io.StringIO('fixture-token')

        self.stack.enter_context(patch.object(adb, 'open', side_effect=open_fixture, create=True))
        self.respond(json.dumps({'state': 'completed', 'output': 'uid=0(root)\n[EXIT=0]', 'exit': 0}))

    def respond(self, result):
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.read.return_value = json.dumps({'result': result}).encode()
        self.opener.open.return_value = response

    def write_port(self, payload):
        with open(self.port_file, 'wb') as target:
            target.write(payload if isinstance(payload, bytes) else str(payload).encode('ascii'))

    def last_request(self):
        self.opener.open.assert_called_once()
        return self.opener.open.call_args.args[0]

    def test_default_home_and_invalid_file_never_falls_back(self):
        self.assertEqual(self.root, adb.bridge_root())
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual('/root/.dsh', adb.bridge_root())
        # 契约文件：≤16 字节普通文件，内容只有 ASCII 十进制 + 一个可选换行。
        # 有效前缀 + 填充/垃圾、Unicode 空白、非 ASCII 字节、越界、0 都不算端点。
        long_garbage = b'3091\n' + b' ' * 64 + b'GARBAGE'
        for payload in (b'0', b'65536', b'70000', b'abc', b'', b' 3090 3091 ',
                        b'+3090', b'3090\n3091\n', b'\xff\xfe\x00', b'9' * 40,
                        b'3091\n' + b' ' * 8, b'3091\nabc', b'3091\n\t', b'3091 ',
                        b'3091\xc2\xa0', b'3091\xe2\x80\xa8', long_garbage):
            with self.subTest(payload=payload):
                self.write_port(payload)
                with self.assertRaises(adb.BridgeUnavailable):
                    adb.read_bridge_port()
                with self.assertRaises(adb.policy.Blocked) as blocked:
                    adb.request_native_execution('id')
                self.assertNotIn('fixture-token', str(blocked.exception))
                self.opener.open.assert_not_called()

    def test_dangling_symlink_is_not_a_missing_file(self):
        """悬空符号链接的 ENOENT 有目录项，不能当成「文件不存在」回退 3090。"""
        os.symlink(os.path.join(self.root, 'missing-target'), self.port_file)
        with self.assertRaises(adb.BridgeUnavailable):
            adb.read_bridge_port()
        with self.assertRaises(adb.policy.Blocked) as blocked:
            adb.request_native_execution('id')
        self.assertNotIn('fixture-token', str(blocked.exception))
        self.opener.open.assert_not_called()
        # 真正删掉目录项之后才允许回退旧默认。
        os.unlink(self.port_file)
        self.assertEqual(3090, adb.read_bridge_port())
        # 指向有效文件的符号链接同样不是契约里的普通文件。
        real = os.path.join(self.root, 'real-port')
        with open(real, 'w') as target:
            target.write('3097\n')
        os.symlink(real, self.port_file)
        with self.assertRaises(adb.BridgeUnavailable):
            adb.read_bridge_port()
        self.opener.open.assert_not_called()

    def test_directory_and_unreadable_port_file_fail_closed(self):
        os.mkdir(self.port_file)
        with self.assertRaises(adb.BridgeUnavailable):
            adb.read_bridge_port()
        with self.assertRaises(adb.policy.Blocked):
            adb.request_native_execution('id')
        self.opener.open.assert_not_called()
        os.rmdir(self.port_file)
        # 目录项存在但打不开：不能走「文件不存在」的旧回退。
        self.write_port('3091\n')
        with patch.object(adb.os, 'open', side_effect=PermissionError('denied')):
            with self.assertRaises(adb.BridgeUnavailable):
                adb.read_bridge_port()
        self.opener.open.assert_not_called()

    def test_missing_file_keeps_legacy_3090_and_sends_token_only_as_header(self):
        self.assertFalse(os.path.exists(self.port_file))
        self.assertEqual(3090, adb.read_bridge_port())
        result = adb.request_native_execution('id')
        self.assertEqual(0, result.exit_code)
        request = self.last_request()
        self.assertEqual('http://127.0.0.1:3090/device/execute?cmd=id&su=0&adb=0', request.full_url)
        self.assertEqual('fixture-token', request.get_header('X-token'))
        self.assertNotIn('fixture-token', request.full_url)
        self.assertEqual([os.path.join(self.root, '.bridge_token')], self.token_paths)

    def test_selected_port_is_used_once_and_refreshed_for_every_request(self):
        self.write_port('3091\n')
        self.assertEqual(3091, adb.read_bridge_port())
        adb.request_native_execution('id')
        first = self.last_request()
        self.assertTrue(first.full_url.startswith('http://127.0.0.1:3091/device/execute?'), first.full_url)
        self.assertEqual('fixture-token', first.get_header('X-token'))
        self.assertNotIn('fixture-token', first.full_url)
        # 桥重启换端口：下一次请求立即跟随，且不重放旧端口。
        self.write_port('3092')
        adb.request_native_execution('id')
        self.assertEqual(2, self.opener.open.call_count)
        second = self.opener.open.call_args.args[0]
        self.assertTrue(second.full_url.startswith('http://127.0.0.1:3092/device/execute?'), second.full_url)
        self.assertNotIn('3090', first.full_url + second.full_url)

    def test_plan_and_vscreen_requests_share_the_selected_port(self):
        self.write_port('3095\n')
        self.respond(json.dumps({'version': 1, 'kind': 'READ', 'argv': ['id']}))
        self.assertEqual('READ', adb.request_device_plan('id', False)['kind'])
        plan_request = self.last_request()
        self.assertTrue(plan_request.full_url.startswith('http://127.0.0.1:3095/device/plan?'), plan_request.full_url)
        self.assertEqual('fixture-token', plan_request.get_header('X-token'))
        self.assertNotIn('fixture-token', plan_request.full_url)
        ticket = 'a' * 48
        self.opener.reset_mock()
        self.respond(json.dumps({'state': 'adb', 'plan': {
            'version': 1, 'kind': 'VIRTUAL_SCREEN', 'argv': ['app_process'],
            'nativeTicket': ticket, 'nativeAuthorization': 'managed-vscreen-start', 'su': False}}))
        started = adb.request_native_vscreen_start('app_process', ticket)
        self.assertEqual('VIRTUAL_SCREEN', started['kind'])
        start_request = self.last_request()
        self.assertTrue(start_request.full_url.startswith('http://127.0.0.1:3095/device/vscreen/start?'), start_request.full_url)
        self.assertNotIn('fixture-token', start_request.full_url)
        self.opener.reset_mock()
        self.respond('VSCREEN_START_COMMITTED')
        adb.request_native_vscreen_commit(ticket)
        commit_request = self.last_request()
        self.assertTrue(commit_request.full_url.startswith('http://127.0.0.1:3095/device/vscreen/commit?'), commit_request.full_url)
        self.assertEqual('fixture-token', commit_request.get_header('X-token'))
        self.assertNotIn('fixture-token', commit_request.full_url)

    def test_bridge_url_rejects_unvalidated_ports(self):
        for invalid in (0, -1, 65536, '3090', None, 3090.0, True):
            with self.subTest(invalid=invalid), self.assertRaises(adb.BridgeUnavailable):
                adb.bridge_url('/device/execute', invalid)
        self.assertEqual('http://127.0.0.1:3090/device/execute', adb.bridge_url('/device/execute', 3090))


if __name__ == '__main__':
    unittest.main(verbosity=2)
