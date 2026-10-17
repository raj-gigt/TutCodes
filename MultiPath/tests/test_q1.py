"""Q1 integration checks. Run: python3 MultiPath/tests/test_q1.py.
Compiles and transfers test files in temporary directories; requires free ports
6001, 6002, 8001, 8002, 9001 and 9002. Does not touch the supplied ZIP or client lag log.
"""
import argparse
import math
import csv
import hashlib
import socket
import struct
import subprocess
import tempfile
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def frame(sequence, payload):
    return struct.pack('!iiq', sequence, len(payload), int(time.time() * 1000)) + payload


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--scheduler', default='minrtt', choices=['minrtt', 'bwratio', 'balanced'])
    parser.add_argument('--delays', nargs=4, default=['0', '0', '0', '0'])
    options = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='multipath-q1-') as directory:
        base = Path(directory)
        server_classes, client_classes = base / 'server-classes', base / 'client-classes'
        for side, classes in [('Server', server_classes), ('Client', client_classes)]:
            classes.mkdir()
            subprocess.run(['javac', '-d', str(classes), str(ROOT / side / 'Main.java')], check=True)

        def run_case(name, data, fake=False, broken=False):
            work = base / name
            server_dir, client_dir = work / 'server', work / 'client'
            (server_dir / 'video').mkdir(parents=True)
            client_dir.mkdir()
            (server_dir / 'video' / 'CN_Lect_NPTEL.zip').write_bytes(data)
            errors = []
            server = None
            peer = None
            listeners = []
            if fake:
                for port in (6001, 6002):
                    listener = socket.socket()
                    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    listener.bind(('127.0.0.1', port))
                    listener.listen()
                    listener.settimeout(10)
                    listeners.append(listener)

                def serve():
                    try:
                        with listeners[0].accept()[0] as first, listeners[1].accept()[0] as second:
                            header = struct.pack('!q', len(data))
                            if broken:
                                first.sendall(header + frame(0, data)[:-1])
                                second.sendall(header + struct.pack('!i', -1))
                                return
                            # Path 2 finishes before path 1 delivers chunk 0.
                            second.sendall(header + frame(1, data[1435:2870]) + struct.pack('!i', -1))
                            time.sleep(.15)
                            message = header + frame(0, data[:1435]) + frame(2, data[2870:]) + struct.pack('!i', -1)
                            # Fragment headers and payloads, including the end marker.
                            for offset in range(0, len(message), 7):
                                first.sendall(message[offset:offset + 7])
                                time.sleep(.0001)
                    except Exception as error:
                        errors.append(error)
                    finally:
                        for listener in listeners:
                            listener.close()

                peer = threading.Thread(target=serve)
                peer.start()
            else:
                log = open(work / 'server.log', 'w')
                server = subprocess.Popen(['java', '-cp', str(server_classes), 'Main', options.scheduler, *options.delays], cwd=server_dir,
                                          stdout=log, stderr=subprocess.STDOUT)
                # Do not probe readiness by connecting: the demo accepts one connection per path.
                time.sleep(.5)
            try:
                result = subprocess.run(['java', '-cp', str(client_classes), 'Main', '127.0.0.1'],
                                        cwd=client_dir, capture_output=True, text=True, timeout=15)
                if broken:
                    assert result.returncode != 0, 'Truncated payload was accepted'
                    assert 'Complete file reconstructed' not in result.stdout
                else:
                    assert result.returncode == 0, result.stdout + result.stderr
                    received = (client_dir / 'video' / 'CN_Lect_NPTEL.zip').read_bytes()
                    assert digest(received) == digest(data), 'File hash differs'
                    assert 'Complete file reconstructed in order.' in result.stdout
                if server:
                    assert server.wait(timeout=5) == 0, (work / 'server.log').read_text()
                    # Q2 probes must produce estimates alongside the real file transfer.
                    with (server_dir / 'Scheduler.csv').open() as schedule_log:
                        windows = list(csv.DictReader(schedule_log))
                    assert sum(int(w['chunks']) for w in windows) == math.ceil(len(data) / 1435)
                    for row in windows:
                        n = int(row['chunks'])
                        first = int(row['path0_chunks'])
                        assert first + int(row['path1_chunks']) == n
                        if options.scheduler == 'bwratio':
                            assert row['mode'] == 'bwratio', row
                            b0, b1 = float(row['bw0_Bps']), float(row['bw1_Bps'])
                            assert first == math.floor(n * b0 / (b0 + b1) + .5), row
                    if options.scheduler == 'balanced':
                        for row in windows:
                            assert row['mode'] == 'balanced', row
                            n = int(row['chunks'])
                            chosen = int(row['path0_chunks'])
                            b0, b1 = float(row['bw0_Bps']), float(row['bw1_Bps'])
                            r0, r1 = float(row['rtt0_ms']), float(row['rtt1_ms'])
                            def residual(split):
                                return abs(r0 / 2000 + split * 1435 / b0
                                           - r1 / 2000 - (n - split) * 1435 / b1)
                            assert residual(chosen) <= min(residual(k) for k in range(n + 1)) + 1e-7, row
                    for path in (0, 1):
                        with (server_dir / f'Bandwidth-Path{path}.csv').open() as probe_log:
                            samples = list(csv.DictReader(probe_log))
                        assert samples, f'No bandwidth samples on path {path}'
                        assert all(float(row['ewma_bytes_per_second']) > 0 for row in samples)
                if peer:
                    peer.join(timeout=5)
                    assert not peer.is_alive(), 'Fake peer did not stop'
                    assert not errors, errors
                print('PASS', name)
            finally:
                if server:
                    if server.poll() is None:
                        server.kill()
                        server.wait()
                    log.close()
                for listener in listeners:
                    listener.close()

        run_case('empty', b'')
        run_case('exact-chunks', bytes(range(256)) * 1435)
        run_case('partial-final-chunk', bytes(range(251)) * 29)
        run_case('reordered-fragmented-early-eof', bytes(range(251)) * 12, fake=True)
        run_case('reject-truncated-payload', b'broken payload', fake=True, broken=True)


if __name__ == '__main__':
    main()
