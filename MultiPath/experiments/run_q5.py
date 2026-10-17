"""Run the six Q5 cases on separate server/client machines.

Server: python3 MultiPath/experiments/run_q5.py server --batch q5-20261017
Client: python3 MultiPath/experiments/run_q5.py client --batch q5-20261017 --host SERVER_IP
Merge client results with Git, then: python3 MultiPath/experiments/run_q5.py summarize --batch q5-20261017
"""
import argparse
import csv
from datetime import datetime
from zoneinfo import ZoneInfo
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
SOURCE_HASH = '653bfc4afd75e6b758b9355ee8d2eea00bc888991e7a2725b6fede2eb51ca8bc'
SOURCE_BYTES = 53186840
CASES = [(f'{case}-{scheduler}', scheduler, delays)
         for case, delays in [('A', ['4', '2', '10', '4']), ('B', ['10', '4', '10', '4'])]
         for scheduler in ['minrtt', 'bwratio', 'balanced']]
ELAPSED = re.compile(r'Client elapsed seconds \(receiver start through flush, includes warmup\): ([0-9.]+)')
TRANSFER = re.compile(r'TRANSFER scheduler=(\w+) bytes=(\d+) chunks=(\d+) path0=(\d+) path1=(\d+) sender_seconds=([0-9.]+)')


def now():
    return datetime.now(ZoneInfo('Asia/Kolkata')).isoformat()


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def file_hash(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def revision():
    return subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()


def compile_java(side):
    folder = ROOT / 'MultiPath' / side
    subprocess.run(['javac', '-d', str(folder / 'build'), str(folder / 'Main.java')], check=True)
    return folder


def server(batch):
    folder = compile_java('Server')
    source = folder / 'video/CN_Lect_NPTEL.zip'
    if source.stat().st_size != SOURCE_BYTES or file_hash(source) != SOURCE_HASH:
        raise RuntimeError('Source differs from the fixed Q5 experiment file')
    batch.mkdir(parents=True, exist_ok=True)
    save(batch / 'plan.json', {'cases': [{'case': name, 'scheduler': scheduler, 'delays_ms': delays}
                                       for name, scheduler, delays in CASES],
                               'source_bytes': SOURCE_BYTES, 'source_sha256': SOURCE_HASH,
                               'timing': 'Client receiver start through verified output flush, including warmup.',
                               'repetitions_per_case': 1})
    for name, scheduler, delays in CASES:
        run = batch / 'server' / name
        run.mkdir(parents=True, exist_ok=False)  # Never overwrite an earlier experiment.
        command = ['java', '-cp', str(folder / 'build'), 'Main', scheduler, *delays]
        meta = {'case': name, 'scheduler': scheduler, 'delays_ms': delays,
                'started_at': now(), 'commit': revision(), 'command': command,
                'source_bytes': SOURCE_BYTES, 'source_sha256': SOURCE_HASH,
                'status': 'waiting for client', 'window_chunks': 256,
                'probe_bytes': 262144, 'probe_pause_ms': 1000, 'ewma_alpha': .125}
        save(run / 'run.json', meta)
        print(f'RUN_STARTED {name}: {scheduler} {" ".join(delays)}', flush=True)
        with (run / 'server.log').open('w') as log:
            proc = subprocess.Popen(command, cwd=folder, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, text=True, bufsize=1)
            try:
                for line in proc.stdout:
                    log.write(line)
                    log.flush()
                    if line.startswith(('CONFIG', 'Waiting', 'Connected', 'TRANSFER', 'Exception', 'Caused')):
                        print(line, end='', flush=True)
                    if 'Connected to Path' in line:
                        meta['status'] = 'transferring'
                        save(run / 'run.json', meta)
                code = proc.wait()
            finally:
                if proc.poll() is None:
                    proc.terminate()
                    proc.wait()
        meta.update(ended_at=now(), server_exit_code=code,
                    status='server completed' if code == 0 else 'server failed')
        for filename in ['Bandwidth-Path0.csv', 'Bandwidth-Path1.csv', 'Scheduler.csv']:
            path = folder / filename
            if path.exists():
                shutil.copy2(path, run / filename)
        text = (run / 'server.log').read_text()
        match = TRANSFER.search(text)
        if match:
            meta.update(file_bytes=int(match[2]), total_chunks=int(match[3]),
                        path0_chunks=int(match[4]), path1_chunks=int(match[5]), sender_seconds=float(match[6]))
        save(run / 'run.json', meta)
        print(f'RUN_FINISHED {name} exit={code}', flush=True)
        if code != 0 or not match:
            raise RuntimeError(f'{name} failed; batch stopped to preserve case alignment')
    print('SERVER_BATCH_COMPLETE', flush=True)


def client(batch, host, second_host):
    folder = compile_java('Client')
    for name, scheduler, delays in CASES:
        run = batch / 'client' / name
        run.mkdir(parents=True, exist_ok=False)
        command = ['java', '-cp', str(folder / 'build'), 'Main', host, second_host or host]
        print(f'CLIENT_STARTED {name}', flush=True)
        meta = {'case': name, 'started_at': now(), 'commit': revision(), 'command': command}
        save(run / 'run.json', meta)
        for attempt in range(60):
            # Do not consume the server's one accepted connection with a readiness probe.
            result = subprocess.run(command, cwd=run, capture_output=True, text=True)
            text = result.stdout + result.stderr
            (run / f'client-attempt-{attempt + 1}.log').write_text(text)
            if result.returncode != 0 and 'ConnectException: Connection refused' in text and 'Connected :' not in text:
                time.sleep(1)
                continue
            break
        meta.update(ended_at=now(), client_exit_code=result.returncode)
        match = ELAPSED.search(text)
        output = run / 'video/CN_Lect_NPTEL.zip'
        if output.exists():
            meta.update(file_bytes=output.stat().st_size, client_sha256=file_hash(output))
        if match:
            meta['client_elapsed_seconds'] = float(match[1])
        meta['timing_boundary'] = 'Receiver start through verified output flush, including server warmup.'
        meta['verified'] = (result.returncode == 0 and match is not None
                            and 'Complete file reconstructed in order.' in text
                            and meta.get('file_bytes') == SOURCE_BYTES
                            and meta.get('client_sha256') == SOURCE_HASH)
        save(run / 'run.json', meta)
        if not meta['verified']:
            raise RuntimeError(f'{name} client failed; see {run}. Batch stopped.')
        print(f'CLIENT_FINISHED {name}: {meta["client_elapsed_seconds"]:.6f} seconds; SHA-256 matches', flush=True)
    print('CLIENT_BATCH_COMPLETE; commit/push the client logs and run.json files under ' + str(batch), flush=True)


def summarize(batch):
    rows = []
    for name, scheduler, delays in CASES:
        server_path = batch / 'server' / name / 'run.json'
        client_path = batch / 'client' / name / 'run.json'
        server_meta = json.loads(server_path.read_text()) if server_path.exists() else {}
        client_meta = json.loads(client_path.read_text()) if client_path.exists() else {}
        verified = (server_meta.get('server_exit_code') == 0 and client_meta.get('verified') is True
                    and server_meta.get('source_sha256') == client_meta.get('client_sha256')
                    and server_meta.get('file_bytes') == client_meta.get('file_bytes'))
        rows.append({'case': name, 'scheduler': scheduler, 'delays_ms': delays,
                     'verified': verified, 'client_seconds': client_meta.get('client_elapsed_seconds'),
                     'sender_seconds': server_meta.get('sender_seconds'),
                     'path0_chunks': server_meta.get('path0_chunks'), 'path1_chunks': server_meta.get('path1_chunks')})
    save(batch / 'summary.json', rows)
    with (batch / 'summary.csv').open('w', newline='') as file:
        writer = csv.DictWriter(file, fieldnames=['case', 'scheduler', 'verified', 'client_seconds', 'sender_seconds', 'path0_chunks', 'path1_chunks'])
        writer.writeheader()
        writer.writerows({k: v for k, v in row.items() if k != 'delays_ms'} for row in rows)
    print(json.dumps(rows, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['server', 'client', 'summarize'])
    parser.add_argument('--batch', required=True, help='Unique directory name within results/q5')
    parser.add_argument('--host', default='10.42.184.197')
    parser.add_argument('--second-host')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]+', args.batch):
        parser.error('Use letters, digits, underscores and hyphens for the batch name')
    batch = ROOT / 'results/q5' / args.batch
    if args.mode == 'server': server(batch)
    elif args.mode == 'client': client(batch, args.host, args.second_host)
    else: summarize(batch)


if __name__ == '__main__':
    main()
