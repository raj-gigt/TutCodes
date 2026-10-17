"""Q2 tests using loopback ports 9001/9002. No video or existing runtime logs touched.
Run from the repository root: python3 MultiPath/tests/test_q2.py
Optional --results DIR saves controlled test evidence (overwrites matching names).
"""
import argparse
import csv
import math
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVER_HARNESS = '''
public class ServerProbeHarness {
    public static void main(String[] args) throws Exception {
        try (BandwidthHandler a = new BandwidthHandler(9001, 0);
             BandwidthHandler b = new BandwidthHandler(9002, 1)) {
            a.start(); b.start();
            System.out.println("READY"); System.out.flush();
            System.in.read();
            a.close(); b.close(); a.awaitStopped(); b.awaitStopped();
            System.out.println("STOPPED");
        }
    }
}
'''
CLIENT_HARNESS = '''
public class ClientProbeHarness {
    public static void main(String[] args) throws Exception {
        try (BandwidthHandler a = new BandwidthHandler("127.0.0.1", 9001, 0);
             BandwidthHandler b = new BandwidthHandler("127.0.0.1", 9002, 1)) {
            a.start(); b.start();
            System.out.println("READY"); System.out.flush();
            System.in.read();
            a.close(); b.close(); a.awaitStopped(); b.awaitStopped();
            System.out.println("STOPPED");
        }
    }
}
'''


def read_exact(peer, length):
    result = bytearray()
    while len(result) < length:
        chunk = peer.recv(length - len(result))
        if not chunk:
            raise EOFError('incomplete record')
        result.extend(chunk)
    return bytes(result)


def connect(port):
    deadline = time.monotonic() + 5
    while True:
        try:
            peer = socket.create_connection(('127.0.0.1', port), timeout=3)
            return peer
        except ConnectionRefusedError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(.05)


def launch(classes, name, work):
    proc = subprocess.Popen(['java', '-cp', str(classes), name], cwd=work,
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True)
    if proc.stdout.readline().strip() != 'READY':
        out, err = proc.communicate(timeout=5)
        raise AssertionError('Harness failed to start: ' + out + err)
    return proc


def stop(proc):
    try:
        out, err = proc.communicate('\n', timeout=5)
        assert proc.returncode == 0, out + err
        assert 'STOPPED' in out, out + err
        return out, err
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.wait()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--results', type=Path)
    args = parser.parse_args()
    evidence = []
    with tempfile.TemporaryDirectory(prefix='multipath-q2-') as directory:
        base = Path(directory)
        classes = {}
        for side, harness in [('Server', SERVER_HARNESS), ('Client', CLIENT_HARNESS)]:
            folder = base / side
            folder.mkdir()
            source = folder / (side + 'ProbeHarness.java')
            source.write_text(harness)
            subprocess.run(['javac', '-d', str(folder), str(ROOT / side / 'Main.java'), str(source)], check=True)
            classes[side] = folder

        server_dir = base / 'controlled-server'
        server_dir.mkdir()
        server = launch(classes['Server'], 'ServerProbeHarness', server_dir)
        failures = []

        def controlled_receiver(path, durations):
            try:
                # Reject a mismatched READY on path 0 and a nonpositive duration on path 1.
                with connect(9001 + path) as peer:
                    probe_id, length = struct.unpack('!ii', read_exact(peer, 8))
                    peer.sendall(struct.pack('!i', probe_id + (1 if path == 0 else 0)))
                    if path == 1:
                        read_exact(peer, length)
                        peer.sendall(struct.pack('!iq', probe_id, 0))
                    assert peer.recv(1) == b'', 'Invalid record did not close connection'
                with connect(9001 + path) as peer:
                    for duration in durations:
                        probe_id, length = struct.unpack('!ii', read_exact(peer, 8))
                        assert length == 262144
                        peer.sendall(struct.pack('!i', probe_id))
                        assert len(read_exact(peer, length)) == length
                        # Deliberately supplied durations test arithmetic, not network capacity.
                        peer.sendall(struct.pack('!iq', probe_id, duration))
                    time.sleep(.15)  # allow the final result to be logged
            except Exception as exc:
                failures.append(exc)

        workers = [threading.Thread(target=controlled_receiver, args=(0, [100000000, 200000000])),
                   threading.Thread(target=controlled_receiver, args=(1, [400000000, 200000000]))]
        try:
            for worker in workers: worker.start()
            for worker in workers: worker.join(timeout=8)
            assert all(not w.is_alive() for w in workers), 'Controlled probe timed out'
            assert not failures, failures
        finally:
            out, err = stop(server)
        for path, durations in enumerate(([100000000, 200000000], [400000000, 200000000])):
            rows = list(csv.DictReader((server_dir / f'Bandwidth-Path{path}.csv').open()))
            assert len(rows) == 2, rows
            expected = None
            for row, duration in zip(rows, durations):
                sample = 262144 * 1e9 / duration
                expected = sample if expected is None else .875 * expected + .125 * sample
                assert int(row['elapsed_ns']) == duration
                assert math.isclose(float(row['sample_bytes_per_second']), sample, abs_tol=1e-5)
                assert math.isclose(float(row['ewma_bytes_per_second']), expected, abs_tol=1e-5)
        evidence.append('PASS independent per-path samples, first-sample initialization, and EWMA arithmetic')
        evidence.append('PASS invalid READY/duration rejected, connection reset, subsequent probes succeed')
        evidence.append('PASS server workers stop while sleeping or waiting for a connection')

        # Exercise the real client timer and fragmented reads using paced fake servers.
        listeners = []
        failures = []
        measured = {}
        for port in (9001, 9002):
            listener = socket.socket()
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('127.0.0.1', port))
            listener.listen()
            listener.settimeout(5)
            listeners.append(listener)
        client = launch(classes['Client'], 'ClientProbeHarness', base)

        def paced_sender(path):
            try:
                # Malformed length must be rejected, then the client must reconnect.
                with listeners[path].accept()[0] as peer:
                    peer.settimeout(3)
                    peer.sendall(struct.pack('!ii', 0, -10))
                    assert peer.recv(1) == b''
                with listeners[path].accept()[0] as peer:
                    peer.settimeout(3)
                    for byte in struct.pack('!ii', 1, 131072):
                        peer.sendall(bytes([byte]))
                    assert struct.unpack('!i', read_exact(peer, 4))[0] == 1
                    for _ in range(4):
                        time.sleep(.02)
                        peer.sendall(b'x' * 32768)
                    result_id, elapsed = struct.unpack('!iq', read_exact(peer, 12))
                    assert result_id == 1
                    assert 60000000 <= elapsed < 3000000000, elapsed
                    measured[path] = elapsed
                    # Keep the connection open, with the client blocked waiting for a header.
                    assert peer.recv(1) == b''
            except Exception as exc:
                failures.append(exc)
            finally:
                listeners[path].close()

        workers = [threading.Thread(target=paced_sender, args=(path,)) for path in (0, 1)]
        try:
            for worker in workers: worker.start()
            deadline = time.monotonic() + 6
            while len(measured) < 2 and not failures and time.monotonic() < deadline:
                time.sleep(.02)
            assert len(measured) == 2, failures
        finally:
            stop(client)
            for worker in workers: worker.join(timeout=4)
            for listener in listeners: listener.close()
        assert not failures, failures
        evidence.append('PASS client monotonic timing and fragmented probe reads on both paths')
        evidence.append('PASS client rejects invalid sizes and reconnects')
        evidence.append('PASS client closes blocked reads and stops both workers')

        # Use both actual implementations together and validate their measured CSV values.
        live_dir = base / 'live'
        live_dir.mkdir()
        server = launch(classes['Server'], 'ServerProbeHarness', live_dir)
        client = launch(classes['Client'], 'ClientProbeHarness', base)
        try:
            deadline = time.monotonic() + 6
            while time.monotonic() < deadline:
                rows = []
                for path in (0, 1):
                    file = live_dir / f'Bandwidth-Path{path}.csv'
                    if file.exists():
                        with file.open() as log:
                            rows.append(list(csv.DictReader(log)))
                    else:
                        rows.append([])
                if all(len(r) >= 2 for r in rows): break
                time.sleep(.05)
            assert all(len(r) >= 2 for r in rows), rows
            for path_rows in rows:
                previous = None
                for row in path_rows:
                    duration = int(row['elapsed_ns'])
                    assert duration > 0
                    sample = 262144 * 1e9 / duration
                    assert math.isclose(float(row['sample_bytes_per_second']), sample, rel_tol=1e-8)
                    expected = sample if previous is None else .875 * previous + .125 * sample
                    assert math.isclose(float(row['ewma_bytes_per_second']), expected, rel_tol=1e-8)
                    previous = expected
        finally:
            stop(client)
            stop(server)
        evidence.append('PASS actual server/client probe exchange and finite positive estimates on both paths')

        if args.results:
            args.results.mkdir(parents=True, exist_ok=True)
            for path in (0, 1):
                shutil.copyfile(server_dir / f'Bandwidth-Path{path}.csv', args.results / f'controlled-path{path}.csv')
                shutil.copyfile(live_dir / f'Bandwidth-Path{path}.csv', args.results / f'loopback-path{path}.csv')
            (args.results / 'validation.txt').write_text(
                'Q2 integration validation\nCommand: python3 MultiPath/tests/test_q2.py --results results/q2\n'
                'Controlled CSV durations are synthetic protocol inputs for arithmetic checks.\n'
                'Loopback CSVs are real local measurements, not IITM-network benchmarks.\n\n' + '\n'.join(evidence) + '\n')
        print('\n'.join(evidence))


if __name__ == '__main__':
    main()
