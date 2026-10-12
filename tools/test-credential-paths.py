#!/usr/bin/env python3
"""Build-time table validation. Native consumers have separate behavior tests."""
import importlib.util
import json
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('credential_paths_generator', ROOT / 'tools/generate-credential-paths.py')
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class CredentialPathGeneratorTest(unittest.TestCase):
    def doc(self):
        return json.loads(generator.SOURCE.read_bytes())

    def test_current_input_generates_current_java_exactly(self):
        self.assertEqual(generator.OUTPUT.read_bytes(), generator.generate(generator.SOURCE.read_bytes()))

    def test_missing_real_machine_names_fail_generation(self):
        for name in ('.bridge_token', '.anonymous-user-id'):
            doc = self.doc()
            doc['externalMachine']['exact'].remove(name)
            with self.assertRaises(ValueError):
                generator.generate(json.dumps(doc).encode())

    def test_session_rule_cannot_expand_to_all_projects(self):
        doc = self.doc()
        doc['sessionExclusions'][0]['root'] = 'projects'
        with self.assertRaises(ValueError):
            generator.generate(json.dumps(doc).encode())

    def test_externals_and_backup_groups_stay_distinct(self):
        doc = self.doc()
        self.assertIn('.env', doc['externalCredentials']['exact'])
        self.assertNotIn('.env', doc['externalMachine']['exact'])
        self.assertNotIn('.env', doc['backupMachine']['additionalExact'])
        self.assertIn('.dsha-web.identity', doc['backupMachine']['additionalExact'])

    @staticmethod
    def machine_name(doc, name):
        """按宿主真实判据求值：精确表 + 派生前缀表（与 backup-engine / CredentialPaths 同序）。"""
        if name in doc['externalMachine']['exact']:
            return True
        return any(name.startswith(prefix) for prefix in doc['externalMachine']['prefixes'])

    def test_bridge_port_discovery_file_and_derived_atomics_are_machine_state(self):
        doc = self.doc()
        self.assertEqual('.bridge_port', doc['names']['bridgePort'])
        for name in ('.bridge_port', '.bridge_port.atomic-42', '.bridge_port.previous', '.bridge_port.retained-7'):
            self.assertTrue(name, self.machine_name(doc, name))

    def test_bridge_port_is_not_a_user_credential_and_does_not_widen_other_groups(self):
        doc = self.doc()
        for name in ('.bridge_port', '.bridge_port.atomic-42'):
            self.assertFalse(name in doc['externalCredentials']['exact'], name)
            self.assertFalse(any(name.startswith(p) for p in doc['externalCredentials']['prefixes']), name)
        # 端口端点必须随 externalMachine 进备份排除；不能只靠 backupMachine 附加表。
        self.assertIn('.bridge_port', doc['externalMachine']['exact'])
        # 主机名不因新端点被顺手收进更宽的组。
        self.assertNotIn('.bridge_port', doc['backupMachine']['additionalExact'])
        self.assertFalse(self.machine_name(doc, '.dsh-backup-note.md'))

    def test_port_endpoint_ownership_rejects_missing_or_drifted_names(self):
        for key, value in (('bridgePort', None), ('bridgePort', '.bridge-port'), ('bridgePort', '')):
            doc = self.doc()
            if value is None:
                del doc['names'][key]
            else:
                doc['names'][key] = value
            with self.assertRaises(ValueError):
                generator.generate(json.dumps(doc).encode())

    def test_machine_table_must_keep_carrying_the_port_endpoint(self):
        for drop in ('.bridge_port', '.anonymous-user-id'):
            doc = self.doc()
            doc['externalMachine']['exact'].remove(drop)
            with self.assertRaises(ValueError):
                generator.generate(json.dumps(doc).encode())
        doc = self.doc()
        doc['externalMachine']['prefixes'] = [p for p in doc['externalMachine']['prefixes'] if not p.startswith('.bridge_port')]
        with self.assertRaises(ValueError):
            generator.generate(json.dumps(doc).encode())


if __name__ == '__main__':
    unittest.main()
