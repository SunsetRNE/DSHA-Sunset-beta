#!/usr/bin/env python3
"""固定自举端点的纯主机回归：执行当前源码函数，不访问设备、网络或真实凭据。"""
import ast
import contextlib
import io
from pathlib import Path
import re
import shlex
import socket
import subprocess
import sys
import types
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
BRIDGE = ROOT / 'app/src/main/java/com/deepseekharness/app/bridge/AdbBridge.java'


def source_module(name, path):
    # 只加载实际函数、类、标准库 import 和常量，跳过资产动态加载及 __main__。
    # 没有复制被测连接逻辑，也不生成 pyc 或执行原脚本的顶层入口。
    tree = ast.parse(path.read_text(encoding='utf-8'), filename=str(path))
    body = [node for node in tree.body
            if isinstance(node, (ast.Import, ast.ImportFrom, ast.FunctionDef, ast.ClassDef))
            or isinstance(node, ast.Assign) and all(
                isinstance(target, ast.Name) and target.id.isupper() for target in node.targets)]
    module = types.ModuleType(name)
    module.__file__ = str(path)
    exec(compile(ast.Module(body=body, type_ignores=[]), str(path), 'exec'), module.__dict__)
    return module


adb = source_module('bootstrap_adb_shell', ASSETS / 'adb-shell.py')
adb.policy = source_module('bootstrap_device_policy', ASSETS / 'device-shell-policy.py')


class BootstrapEndpointTest(unittest.TestCase):
    def setUp(self):
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.events = []
        self.credential_reads = []
        self.connect_error = None
        self.connected = True
        self.shell_error = None
        self.framed = True
        self.remote_exit = 0
        self.output = 'uid=2000(shell) gid=2000(shell)\n'
        self.sent_commands = []
        test = self

        class Device:
            def __init__(self, host, port, default_transport_timeout_s):
                self.host, self.port = host, port
                test.assertEqual(4.0, default_transport_timeout_s)
                test.events.append(('new', host, port))

            def connect(self, **kwargs):
                test.events.append(('connect', self.host, self.port))
                test.assertEqual(4.0, kwargs['transport_timeout_s'])
                test.assertEqual(8, kwargs['auth_timeout_s'])
                test.assertEqual(8, kwargs['read_timeout_s'])
                test.assertEqual(b'fixture-private', kwargs['tls_priv_pem'])
                if test.connect_error:
                    raise test.connect_error
                return test.connected

            def shell(self, command, **kwargs):
                test.events.append(('shell', self.host, self.port))
                test.sent_commands.append(command)
                test.assertEqual(8, kwargs['timeout_s'])
                if test.shell_error:
                    raise test.shell_error
                marker = re.search(r'__DSHA_EXIT_[a-f0-9]+__=', command).group()
                suffix = '\n' + marker + str(test.remote_exit) + '\n' if test.framed else ''
                return (test.output + suffix).encode('utf-8')

            def close(self):
                test.events.append(('close', self.host, self.port))

        def signer(public, private):
            self.assertEqual((b'fixture-public', b'fixture-private'), (public, private))
            return object()

        def credentials(path, mode='r', **kwargs):
            self.assertEqual('rb', mode, '不允许历史读取或任何写入')
            self.assertIn(path, (adb.KEY, adb.KEYPUB), '不允许读取其他文件或桥 token')
            self.credential_reads.append(path)
            return io.BytesIO(b'fixture-private' if path == adb.KEY else b'fixture-public')

        # 每个实际 I/O 边界都隔离；漏掉替身会失败，不能退到真实网络/凭据/进程。
        self.stack.enter_context(patch.object(socket, 'socket', side_effect=AssertionError('禁止真实 socket')))
        self.stack.enter_context(patch.object(socket, 'create_connection', side_effect=AssertionError('禁止真实网络')))
        self.stack.enter_context(patch.object(subprocess, 'Popen', side_effect=AssertionError('禁止真实子进程')))
        self.stack.enter_context(patch.object(adb, 'open', side_effect=credentials, create=True))
        fake_os = types.SimpleNamespace(path=types.SimpleNamespace(
            isfile=lambda path: path in (adb.KEY, adb.KEYPUB)))
        self.stack.enter_context(patch.object(adb, 'os', fake_os))
        self.stack.enter_context(patch.object(adb, 'time', types.SimpleNamespace(monotonic=lambda: 100.0)))
        self.remember = self.stack.enter_context(patch.object(adb, 'remember_endpoint'))
        self.forbidden = {}
        for name in ('candidate_hosts', 'local_ips', 'discover_endpoints', 'load_port_history',
                     'connect_with_retry', 'request_device_plan', 'request_native_vscreen_start',
                     'request_native_vscreen_commit', 'read_vscreen_ticket'):
            self.forbidden[name] = self.stack.enter_context(patch.object(
                adb, name, side_effect=AssertionError('自举验证禁止调用 ' + name)))
        self.native = self.stack.enter_context(patch.object(adb, 'request_native_execution',
            return_value={'version': 1, 'kind': 'READ', 'argv': ['id'], 'su': False}))
        modules = {name: types.ModuleType(name) for name in (
            'adb_shell_wifi', 'adb_shell_wifi.adb_device',
            'adb_shell_wifi.auth', 'adb_shell_wifi.auth.sign_pythonrsa')}
        modules['adb_shell_wifi.adb_device'].AdbDeviceTls = Device
        modules['adb_shell_wifi.auth.sign_pythonrsa'].PythonRSASigner = signer
        self.stack.enter_context(patch.dict(sys.modules, modules))

    def invoke(self, args=None):
        if args is None:
            args = ['--bootstrap-verify']
        with patch.object(adb.sys, 'argv', ['adb-shell.py', *args]), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            code = adb.main()
        return code, output.getvalue()

    def assert_single_endpoint(self, sent):
        kinds = ['new', 'connect'] + (['shell'] if sent else []) + ['close']
        self.assertEqual([(kind, '127.0.0.1', 5555) for kind in kinds], self.events)
        self.assertEqual([adb.KEYPUB, adb.KEY], self.credential_reads)
        self.native.assert_called_once_with('id', False, True)
        for forbidden in self.forbidden.values():
            forbidden.assert_not_called()

    def test_success_only_connects_loopback_5555_and_sends_id_once(self):
        code, output = self.invoke()
        self.assertEqual(0, code)
        self.assertEqual(self.output + '[EXIT=0]\n', output)
        self.assert_single_endpoint(sent=True)
        self.assertEqual(1, len(self.sent_commands))
        self.assertEqual('/system/bin/id', shlex.split(self.sent_commands[0])[2].rstrip(';'))
        self.remember.assert_called_once_with('127.0.0.1', 5555)

    def test_connect_fail_does_not_try_another_host_or_port(self):
        self.connect_error = ConnectionRefusedError('fixture refused')
        code, output = self.invoke()
        self.assertEqual(124, code)
        self.assertIn('CONNECT_FAIL:', output)
        self.assertTrue(output.endswith('[EXIT=124]\n'))
        self.assert_single_endpoint(sent=False)
        self.remember.assert_not_called()

    def test_failed_handshake_is_terminal_without_discovery(self):
        self.connected = False
        code, output = self.invoke()
        self.assertEqual(124, code)
        self.assertIn('CONNECT_FAIL:', output)
        self.assert_single_endpoint(sent=False)
        self.remember.assert_not_called()

    def test_execution_unknown_never_replays(self):
        self.shell_error = TimeoutError('fixture lost response')
        code, output = self.invoke()
        self.assertEqual(125, code)
        self.assertIn('EXECUTION_UNKNOWN:', output)
        self.assertTrue(output.endswith('[EXIT=125]\n'))
        self.assert_single_endpoint(sent=True)
        self.remember.assert_not_called()

    def test_missing_exit_marker_is_unknown_without_replay(self):
        self.framed = False
        code, output = self.invoke()
        self.assertEqual(125, code)
        self.assertIn('EXECUTION_UNKNOWN:', output)
        self.assert_single_endpoint(sent=True)
        self.remember.assert_not_called()

    def test_remote_nonzero_exit_is_preserved_without_retry(self):
        self.remote_exit = 17
        code, output = self.invoke()
        self.assertEqual(17, code)
        self.assertTrue(output.endswith('[EXIT=17]\n'))
        self.assert_single_endpoint(sent=True)

    def test_native_policy_denial_cannot_be_bypassed(self):
        self.native.side_effect = adb.policy.Blocked('fixture denied')
        code, output = self.invoke()
        self.assertEqual(126, code)
        self.assertIn('[POLICY_BLOCKED]', output)
        self.native.assert_called_once_with('id', False, True)
        self.assertEqual([], self.events)
        self.assertEqual([], self.credential_reads)

    def test_native_unknown_does_not_start_adb(self):
        self.native.side_effect = adb.ExecutionUnknown('fixture response lost')
        code, output = self.invoke()
        self.assertEqual(125, code)
        self.assertIn('EXECUTION_UNKNOWN:', output)
        self.native.assert_called_once_with('id', False, True)
        self.assertEqual([], self.events)
        self.assertEqual([], self.credential_reads)

    def test_native_completed_failure_is_preserved_without_connecting(self):
        for exit_code in (124, 125, 126):
            with self.subTest(exit_code=exit_code):
                self.native.return_value = adb.ShellResult('fixture denied\n[EXIT=%d]' % exit_code, exit_code)
                code, output = self.invoke()
                self.assertEqual(exit_code, code)
                self.assertTrue(output.endswith('[EXIT=%d]\n' % exit_code))
                self.assertEqual([], self.events)
                self.assertEqual([], self.credential_reads)

    def test_native_completed_success_cannot_prove_the_fixed_endpoint(self):
        self.native.return_value = adb.ShellResult('uid=0(root)\n[EXIT=0]', 0)
        code, output = self.invoke()
        self.assertEqual(125, code)
        self.assertIn('EXECUTION_UNKNOWN:', output)
        self.assertEqual([], self.events)
        self.assertEqual([], self.credential_reads)

    def test_changed_or_elevated_plan_is_rejected_before_connecting(self):
        for plan in (None, {}, {'version': 2, 'kind': 'READ', 'argv': ['id']},
                     {'version': 1, 'kind': 'STOP', 'argv': ['id']},
                     {'version': 1, 'kind': 'READ', 'argv': ['id', '-u']},
                     {'version': 1, 'kind': 'READ', 'argv': ['setenforce', '0']},
                     {'version': 1, 'kind': 'READ', 'argv': ['id'], 'su': True}):
            with self.subTest(plan=plan):
                self.native.return_value = plan
                code, output = self.invoke()
                self.assertEqual(126, code)
                self.assertIn('[POLICY_BLOCKED]', output)
                self.assertEqual([], self.events)
                self.assertEqual([], self.credential_reads)

    def test_bootstrap_mode_rejects_command_identity_endpoint_and_timeout_overrides(self):
        for extra in (['id'], ['--', 'id'], ['--su'], ['--vscreen-launch'], ['--bootstrap-verify'],
                      ['--host', '192.0.2.1'], ['--port', '5556'], ['--timeout', '1'],
                      ['--connect-timeout', '1'], ['setenforce', '0']):
            with self.subTest(extra=extra):
                code, output = self.invoke(['--bootstrap-verify', *extra])
                self.assertEqual(2, code)
                self.assertIn('INVALID_ARGUMENT:', output)
                self.native.assert_not_called()
                self.assertEqual([], self.events)
                self.assertEqual([], self.credential_reads)

    def test_parse_args_retains_the_existing_seven_tuple_contract(self):
        cases = [([], (0, '', 90.0, 25.0, False, False, 'id')),
                 (['--host', '192.0.2.1', '--port', '39000', '--timeout', '10', '--connect-timeout', '9', 'id'],
                  (39000, '192.0.2.1', 10, 9, False, False, 'id')),
                 (['--su', '--', 'id', '-u'], (0, '', 90.0, 25.0, True, False, 'id -u')),
                 (['--vscreen-launch', '--', 'managed launch'],
                  (0, '', 90.0, 25.0, False, True, 'managed launch'))]
        for args, expected in cases:
            with self.subTest(args=args):
                parsed = adb.parse_args(args)
                self.assertIsInstance(parsed, tuple)
                self.assertEqual(7, len(parsed))
                self.assertEqual(expected, parsed)

    def test_ordinary_explicit_endpoint_still_uses_existing_connector(self):
        result = adb.ShellResult('ordinary\n', 0)
        with patch.object(adb, 'connect_with_retry', return_value=result) as retry:
            code, output = self.invoke(['--host', '192.0.2.1', '--port', '39000', 'id'])
        self.assertEqual((0, 'ordinary\n[EXIT=0]\n'), (code, output))
        retry.assert_called_once()
        self.assertEqual((self.native.return_value, 39000, '192.0.2.1', 25.0, 90.0),
                         retry.call_args.args[2:])
        self.native.assert_called_once_with('id', False, True)
        self.assertEqual([], self.events)
        self.assertEqual([], self.credential_reads)

    def test_bridge_invokes_the_fixed_mode_with_no_extra_arguments(self):
        source = BRIDGE.read_text(encoding='utf-8')
        method = re.search(r'public static String verifyBootstrap\([^\n]*\).*?\n  \}', source, re.S)
        self.assertIsNotNone(method, '缺少原生自举验证入口')
        body = method.group()
        commands = re.findall(r'execOwned\(\s*proot,\s*"([^"\n]+)"\s*,\s*20000\s*\)', body)
        self.assertEqual(['python3 /root/.dsh/adb-shell.py --bootstrap-verify'], commands)
        self.assertEqual(2, body.count('bundledDeviceScriptsMatch()'))
        self.assertNotIn('grantSecureSettings', body)
        # 桥中实际命令的参数继续驱动当前 main；不是只检查源码包含某个字符串。
        code, output = self.invoke(shlex.split(commands[0])[2:])
        self.assertEqual(0, code)
        self.assertTrue(output.endswith('[EXIT=0]\n'))
        self.assert_single_endpoint(sent=True)


if __name__ == '__main__':
    unittest.main(verbosity=2)
