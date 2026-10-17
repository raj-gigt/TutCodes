# Multipath: in-order delivery, probes and interchangeable schedulers

The server supports minRTT and bandwidth-ratio schedulers. Every file chunk gets a global
sequence number before being sent on either TCP path. Both client receiver threads
share a synchronized `Reassembler`, which buffers later chunks and writes only
consecutive chunks to one output stream. One path ending does not complete the file.

## Wire format

All integers are Java DataInputStream/DataOutputStream big-endian binary values.
Each TCP connection begins with the same 8-byte total file size. It then carries:

- 4-byte sequence number, starting at zero across the whole file
- 4-byte payload length (1 to 1435)
- 8-byte sending timestamp in milliseconds
- exactly that many payload bytes

A sequence number of -1 terminates that path; it has no following fields.
The receiver uses readFully for payloads, so TCP read boundaries do not matter.
The final chunk contains only its actual bytes, without zero padding. Both paths
must terminate successfully and the reconstructed byte count must match the file
size before success is reported. Empty files contain only metadata and end markers.

## Build and run

Install a Java JDK on both computers (`java -version` and `javac -version`
should work). Use the same updated code on both computers.

### Server computer — start first

From the `TutCodes` repository root:

```sh
cd MultiPath/Server
mkdir -p build
javac -d build Main.java
java -cp build Main
```

The source file must exist at `MultiPath/Server/video/CN_Lect_NPTEL.zip`.
Run `hostname -I` in another terminal to find the server's LAN IP address.

### Client computer — start after the server

From the `TutCodes` repository root:

```sh
cd MultiPath/Client
mkdir -p build
javac -d build Main.java
java -cp build Main
```

With no arguments, the client uses the hardcoded default IP `10.42.184.197`
for both paths. You do not need to provide it again if that is the server's IP.

Optional: if the server has a different IP, override the default without editing
or recompiling the code:

```sh
java -cp build Main SERVER_IP
```

Replace `SERVER_IP` with the server's actual LAN IPv4 address.

The client creates `video/` automatically and writes
`MultiPath/Client/video/CN_Lect_NPTEL.zip`. Success prints
`Complete file reconstructed in order.`

Keep each terminal in its `Server` or `Client` directory when running; do not
change into `build/`. Both computers must be reachable on the same network.
Allow TCP ports 6001/6002 and UDP ports 8001/8002 on the server if its firewall
blocks them. Restart the server before each new download.

Recompile after changing `Main.java`. Generated classes go into each directory's
`build/` folder. Add these patterns to the repository-root `.gitignore` to keep
compiled files out of commits:

```gitignore
build/
*.class
```

An optional second server address selects the second path:
`java -cp build Main SERVER_IP SECOND_IP`. Normally both paths use the same server.
The client overwrites its output ZIP each run. No cleanup script is needed.
`MultiPath.txt` appends per-chunk lag measurements and is not cleared between runs.

The lag timestamps still depend on clock synchronization between machines; they
are not RTT measurements. Bandwidth probes run independently of the minRTT data
scheduler by default. Q3 adds bandwidth-ratio scheduling and Gaussian data-path
delays; Q4 balanced completion is not implemented yet.

## Tests

From the repository root:

```sh
python3 MultiPath/tests/test_q1.py
python3 MultiPath/tests/test_q2.py --results results/q2
```

Requires Java/Javac, Python 3, and free local ports 6001, 6002, 8001, 8002, 9001, 9002.
Tests compile into temporary directories and preserve the supplied ZIP and logs.
They verify SHA-256 equality for empty, exact-chunk, partial-chunk, and deliberately
reordered/fragmented transfers, plus rejection of truncated payloads. The reordered
case ends one path before the other delivers the missing first chunk.

The sender still loads the whole file into memory, and the reorder buffer can grow
when an early chunk is delayed. This is a teaching implementation, not a bounded-
memory streaming protocol. Failed downloads may leave a partial output file and
are reported as failures rather than successful downloads.

## Q2 bandwidth control channels

Allow TCP ports 9001 and 9002 on the server, in addition to TCP data ports 6001/6002
and UDP RTT ports 8001/8002. Compile both endpoints as before. No extra launch
arguments are needed; each probe connects to the IP supplied for its data path.

Each server BandwidthHandler sends a 256 KiB burst, with a one-second pause after
receiving its measurement. The client starts a monotonic timer before replying
READY, receives the entire burst, then returns elapsed nanoseconds. The server
calculates bytes/second and smooths samples with alpha=0.125. The first sample
initializes the estimate. Probe IDs pair headers, READY messages and results.

The server prints sample and smoothed rates in Mbps (decimal megabits/second).
It writes Bandwidth-Path0.csv and Bandwidth-Path1.csv in its working directory,
including sample ID, payload bytes, elapsed nanoseconds, sample bytes/second and
EWMA bytes/second. These CSVs are replaced on each server run; copy them into a
separate results directory before the next experiment if you want to retain them.

The estimate is available as bandwidthEstimateBytesPerSecond; lastSampleNanos
records freshness. Before any valid sample the estimate is NaN, not zero. A failed
probe does not become a zero-bandwidth sample. Failed/partial connections are closed
and retried; the previous estimate remains available with its original timestamp.
Reads time out after three seconds. Closing a probe worker closes its socket and
interrupts its pause, allowing transfer completion to stop it.

This estimates effective burst throughput, including READY transit time, not exact
link capacity. Separate control and data TCP connections have independent TCP state.
Matching their destination IPs does not guarantee identical routes or independent
physical paths. Identical endpoint/interface pairs may share bandwidth, and probe
traffic itself introduces load. Q2 does not simulate bandwidth heterogeneity.

The Q2 tests exercise both probe implementations, controlled per-path durations,
EWMA arithmetic, invalid messages, fragmented reads, reconnection and shutdown.
Saved controlled CSVs are synthetic arithmetic checks; loopback CSVs are actual
local samples and should not be presented as two-machine benchmark results.

## Q3 scheduler and delays

Run from MultiPath/Server after `javac -d build Main.java`:

```sh
java -cp build Main bwratio 4 2 10 4
java -cp build Main minrtt 4 2 10 4
```

Use one command per run. Arguments are scheduler, path-0 mean/std, path-1 mean/std
in milliseconds. With no arguments the server uses minRTT and zero simulated delay.
The client command is unchanged; recompile the updated client to get its elapsed-time
message (receiver start through verified output flush, including server warmup).

Both strategies operate on windows of up to 256 chunks. The ratio strategy assigns
round(N * BW0 / (BW0 + BW1)) chunks to path 0 and the remainder to path 1, using
fresh smoothed estimates at each window. Two workers send concurrently. Each window
waits for both workers to finish writing before the next allocation, which bounds
queued chunks but can idle the faster worker. MinRTT selects one path per window.

The server logs allocation, estimates, and fallback decisions in Scheduler.csv.
The final TRANSFER line records per-path chunk counts and sender duration from
metadata transmission through final end-marker writes; it is not client download
time. The ratio scheduler waits up to 15 seconds after its 2-second warmup for BW
samples. Unavailable or older-than-10-second samples cause minRTT fallback.

Gaussian delay samples are rounded and clamped at zero. Data workers sleep per
chunk; bandwidth probes sleep per payload block (at most 1435 bytes), using the
same mean/std for their associated path. RTT estimates add two one-way samples.
This models per-chunk service delay and therefore limits throughput too; it does
not emulate independent propagation delays with unlimited packets in flight.

Tests from the repository root:

```sh
javac -d /tmp/multipath-q3-tests MultiPath/Server/Main.java MultiPath/tests/SchedulerTest.java
java -cp /tmp/multipath-q3-tests SchedulerTest
python3 MultiPath/tests/test_q1.py --scheduler bwratio --delays 4 2 10 4
```

The data frame format remains compatible with Q1/Q2 clients. Archive Scheduler.csv
and the bandwidth CSVs after each run; subsequent runs replace them.
