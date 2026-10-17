"""Check Q5 case ordering, retry rules, integrity checks, and client timing capture."""
import importlib.util
import json
import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('q5', Path(__file__).resolve().parents[1] / 'experiments/run_q5.py')
q5 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(q5)


class RunnerTest(unittest.TestCase):
    def test_case_plan(self):
        self.assertEqual([c[0] for c in q5.CASES], ['A-minrtt', 'A-bwratio', 'A-balanced', 'B-minrtt', 'B-bwratio', 'B-balanced'])
        self.assertTrue(all(c[2] == ['4', '2', '10', '4'] for c in q5.CASES[:3]))
        self.assertTrue(all(c[2] == ['10', '4', '10', '4'] for c in q5.CASES[3:]))

    def client_run(self, folder, result, payload, calls):
        def run(command, **kwargs):
            calls.append(command)
            target = kwargs['cwd'] / 'video'
            target.mkdir(exist_ok=True)
            (target / 'CN_Lect_NPTEL.zip').write_bytes(payload)
            return result
        return patch.object(q5.subprocess, 'run', side_effect=run)

    def test_success_retry_and_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            calls = []
            def run(command, **kwargs):
                calls.append(command)
                if len(calls) == 1:
                    return subprocess.CompletedProcess(command, 1, '', 'java.net.ConnectException: Connection refused')
                target = kwargs['cwd'] / 'video'
                target.mkdir(exist_ok=True)
                (target / 'CN_Lect_NPTEL.zip').write_bytes(b'file')
                return subprocess.CompletedProcess(command, 0, 'Complete file reconstructed in order.\nClient elapsed seconds (receiver start through flush, includes warmup): 12.345678\n', '')
            with patch.object(q5, 'compile_java', return_value=root), patch.object(q5, 'revision', return_value='test'), \
                 patch.object(q5, 'SOURCE_BYTES', 4), patch.object(q5, 'SOURCE_HASH', hashlib.sha256(b'file').hexdigest()), \
                 patch.object(q5, 'CASES', q5.CASES[:1]), patch.object(q5.subprocess, 'run', side_effect=run), patch.object(q5.time, 'sleep'):
                q5.client(root, '127.0.0.1', None)
                self.assertEqual(len(calls), 2)
                client = json.loads((root / 'client/A-minrtt/run.json').read_text())
                self.assertEqual(client['client_elapsed_seconds'], 12.345678)
                self.assertTrue(client['verified'])
                server_dir = root / 'server/A-minrtt'
                server_dir.mkdir(parents=True)
                (server_dir / 'run.json').write_text(json.dumps({'server_exit_code': 0, 'source_sha256': client['client_sha256'], 'file_bytes': 4}))
                q5.summarize(root)
                self.assertTrue(json.loads((root / 'summary.json').read_text())[0]['verified'])
                with self.assertRaises(FileExistsError):
                    q5.client(root, '127.0.0.1', None)

    def test_dont_retry_connected_or_bad_hash(self):
        for stdout, stderr, code, payload in [
            ('Connected : 0\n', 'java.net.ConnectException: Connection refused', 1, b'file'),
            ('Complete file reconstructed in order.\nClient elapsed seconds (receiver start through flush, includes warmup): 1.0\n', '', 0, b'bad!')]:
            with tempfile.TemporaryDirectory() as directory:
                root, calls = Path(directory), []
                with patch.object(q5, 'compile_java', return_value=root), patch.object(q5, 'revision', return_value='test'), \
                     patch.object(q5, 'SOURCE_BYTES', 4), patch.object(q5, 'SOURCE_HASH', hashlib.sha256(b'file').hexdigest()), \
                     patch.object(q5, 'CASES', q5.CASES[:1]), self.client_run(root, subprocess.CompletedProcess([], code, stdout, stderr), payload, calls):
                    with self.assertRaises(RuntimeError):
                        q5.client(root, '127.0.0.1', None)
                    self.assertEqual(len(calls), 1)
                    self.assertFalse(json.loads((root / 'client/A-minrtt/run.json').read_text())['verified'])


if __name__ == '__main__':
    unittest.main()
