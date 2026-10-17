# Assignment 3: Multipath File Transfer

Name: ____________________  
Roll number: ____________________

## Q1. In-order delivery

I added a global sequence number to each chunk and used one shared reassembler
for both receiver threads. The minRTT scheduler is unchanged.

### Application frame format

Each TCP connection first receives the total file size (`long`). The chunk format is:

```text
sequence number (int) | payload length (int) | timestamp (long) | payload
```

The maximum payload is 1435 bytes. The last chunk carries only the remaining bytes.
A sequence number of `-1` marks the end of a path.

### Sender

The sequence number increases across both paths. After choosing a path using
minRTT, the server sends:

```java
out.writeInt(sequence++);
out.writeInt(length);
out.writeLong(System.currentTimeMillis());
out.write(fileBytes, offset, length);
```

### Receiver

Each receiver reads a complete record and passes it to the shared reassembler:

```java
int sequence = input.readInt();
if (sequence == -1) return;
int length = input.readInt();
// Validate length before allocating the payload.
long sentTime = input.readLong();
byte[] payload = new byte[length];
input.readFully(payload);
reassembler.accept(sequence, payload);
```

The synchronized `accept()` method validates the chunk and buffers it until all
previous chunks have been written. Its ordering logic is:

```java
pending.put(sequence, payload);
while (pending.containsKey(nextExpected)) {
    byte[] next = pending.remove(nextExpected);
    output.write(next);
    written += next.length;
    nextExpected++;
}
```

For example, arrival order `1, 0, 3, 2` produces file order `0, 1, 2, 3`.
Both threads share one output stream. Success is reported only after both paths
finish, no receiver errors occur, and the written byte count matches the file size
with no chunks left pending.

### Validation

The two-computer transfer completed with `Complete file reconstructed in order.`
The downloaded ZIP was 53,186,840 bytes. The server and client SHA-256 hashes
were verified to match:

```text
653bfc4afd75e6b758b9355ee8d2eea00bc888991e7a2725b6fede2eb51ca8bc
```

This confirms byte-for-byte reconstruction for the tested transfer. The recorded
[integration checks](../results/q1/validation.txt) also cover deliberately reordered
and fragmented data, empty files, chunk boundaries, and truncated-payload rejection.

## Q2. Bandwidth estimation

I added two TCP control channels on ports 9001 and 9002. Each uses the same server
IP as its corresponding data path. The server sends a 256 KiB probe burst and waits
one second between completed probes. These bytes are discarded by the client and
are not part of the downloaded file.

### Measurement

```text
Server → Client: probe ID, payload size
Client: start timer
Client → Server: READY (probe ID)
Server → Client: 262144 payload bytes
Client: stop timer
Client → Server: probe ID, elapsed nanoseconds
```

The timer uses `System.nanoTime()` on the client, so synchronized clocks are not
needed. Starting before READY prevents the payload from arriving before timing starts.

```java
double sample = PROBE_BYTES * 1_000_000_000.0 / elapsed;
```

Samples are in bytes/second. They include the READY request delay and payload
transfer time, so they estimate effective burst throughput rather than exact link
capacity. The result message back to the server is outside the measured interval.
I used a 256 KiB burst because smaller bursts would make the fixed READY request
delay a larger part of the measurement, making the estimate more latency-dependent.

### Smoothing

I used an EWMA with `alpha = 0.125`. The first valid sample initializes the estimate;
subsequent samples are smoothed independently for each path:

```java
bandwidthEstimateBytesPerSecond = Double.isNaN(old)
        ? sample : 0.875 * old + 0.125 * sample;
```

This reduces sudden changes from noisy samples. Before the first sample, the estimate
is `NaN` (unavailable). A timestamp records when the latest valid estimate was obtained.
The estimate is `volatile` so the sender can read updates from the probe thread.
MinRTT remains the data scheduler for Q2.

### Results

```sh
python3 MultiPath/tests/test_q2.py --results results/q2
```

The tests passed for independent estimates, smoothing, fragmented reads, invalid
messages, reconnecting, and stopping blocked probe workers. Both real probe endpoints
also exchanged bursts successfully over loopback.

Controlled durations verified the calculation (these are test inputs, not network measurements):

```text
Path 0: 100 ms → 200 ms; samples 2621440 → 1310720 B/s; final EWMA 2457600 B/s
Path 1: 400 ms → 200 ms; samples  655360 → 1310720 B/s; final EWMA  737280 B/s
```

[Validation](../results/q2/validation.txt) ·
[Path 0 controlled results](../results/q2/controlled-path0.csv) ·
[Path 1 controlled results](../results/q2/controlled-path1.csv) ·
[Path 0 loopback samples](../results/q2/loopback-path0.csv) ·
[Path 1 loopback samples](../results/q2/loopback-path1.csv)

### Two-machine run: 17 October 2026

The server completed with exit code 0 using minRTT and a 53,186,840-byte file.
Each path recorded five probe samples (rates below are decimal Mbps):

```text
Path 0 samples: 31.223, 53.763, 49.506, 13.082, 22.004
Path 1 samples: 35.679, 52.169, 44.651, 12.540, 22.023

Path 0: mean sample = 33.915 Mbps; final EWMA = 31.724 Mbps
Path 1: mean sample = 33.412 Mbps; final EWMA = 33.680 Mbps
```

Both paths showed a similar drop and recovery. Their close averages do not show a
persistent bandwidth difference in this short run. The EWMA changes more gradually
than individual samples. These probe rates are not file-download throughput.

[Run logs and measurements](../results/q2/lan-run-20261017-145341/README.md).
The client-reported SHA-256 matches the server source hash, confirming correct
file reconstruction with the bandwidth probes active:

```text
653bfc4afd75e6b758b9355ee8d2eea00bc888991e7a2725b6fede2eb51ca8bc
```

Download time was not measured; the server's launch-to-exit interval includes
waiting for the client.

### Limitations

- Separate ports do not guarantee separate physical paths. With the same IP and
  interface, both probes may share a bottleneck; differences can be measurement noise.
- Separate TCP connections have their own congestion state. Their throughput is a
  proxy for the associated data path, not its exact achieved rate.
- Probe traffic consumes bandwidth and may affect file transfers. Burst size,
  latency, TCP startup, and host processing also affect the samples.
- Automated tests used loopback; the separate two-machine run is recorded above.
  No artificial bandwidth difference was introduced in Q2.

## Q3. Bandwidth-ratio scheduler

I added a `Scheduler` interface with `MinRttScheduler` and `BandwidthRatioScheduler`
implementations. The scheduler can be selected from the command line:

```sh
java -cp build Main bwratio 4 2 10 4
# Alternative: java -cp build Main minrtt 4 2 10 4
```

The four numbers are path 0 mean/std and path 1 mean/std, in milliseconds.

### Allocation

The file is scheduled in windows of up to 256 chunks. Each window uses the latest
smoothed bandwidth estimates from Q2:

```text
n = round(N × BW0 / (BW0 + BW1))
Path 0 receives n chunks; path 1 receives N - n chunks.
```

The assigned chunks are spread through the window's sequence numbers. Two sender
workers transmit concurrently, and the next window begins after both workers finish
writing the current one. This bounds queued work, but a faster path can wait at the
window boundary. MinRTT also selects a path per window for a consistent comparison.

```java
interface Scheduler {
    String name();
    default boolean requiresBandwidth() { return false; }
    int chunksOnFirstPath(int count, PathMetrics first, PathMetrics second);
}
```

The ratio scheduler waits up to 15 seconds after the 2-second warmup for valid
bandwidth estimates. If either estimate is unavailable or older than 10 seconds,
it uses minRTT for that window and records the fallback in `Scheduler.csv`.

### Simulated delay

`AddDelay` samples a normal distribution, rounds to milliseconds, and clamps
negative values to zero:

```text
delay = max(0, round(mean + std × Gaussian(0, 1)))
```

Each sender sleeps before transmitting a chunk. Bandwidth probes use the same
per-path distribution before every payload block of at most 1435 bytes. RTT samples
include two independently sampled one-way delays. Separate random samples are used
for data and probes.

This is a per-chunk service-delay simulation, not a pure propagation-delay model:
sleeping between chunks also limits effective throughput. Clamping changes the
actual distribution slightly. Both schedulers use the same simulation rules.

### Validation

Strategy tests passed for 2:1 allocation, equal bandwidth, changed estimates,
rounding, fallback, and exact sequence coverage. Normal-delay sampling was checked
with a fixed random seed. File-transfer tests with `bwratio 4 2 10 4` passed for
empty files, exact and partial chunks, reordering, fragmentation, and truncation.
Successful transfers matched the source SHA-256, and every logged window matched
the bandwidth-ratio allocation.

[Validation record](../results/q3/validation.txt)

### Two-machine results

The `bwratio 4 2 10 4` configuration completed on the server with exit code 0. All 145 scheduling
windows matched the ratio formula, with no minRTT fallbacks:

```text
File: 53,186,840 bytes / 37,064 chunks
Path 0: 25,978 chunks (70.09%)
Path 1: 11,086 chunks (29.91%)
Sender duration: 135.396 seconds
```

The larger allocation went to the path with higher measured bandwidth. Mean probe
rates were 2.202 Mbps on path 0 and 0.933 Mbps on path 1. Sender duration excludes warmup and
ends after writing the end markers; it is not client-side download time. The client
completed successfully and its 53,186,840-byte output matched the server SHA-256:

```text
653bfc4afd75e6b758b9355ee8d2eea00bc888991e7a2725b6fede2eb51ca8bc
```

[Client verification](../results/q3/client-verification.txt)

[Run logs and measurements](../results/q3/lan-run-20261017-152422/README.md)

## Q4. Balanced subflow completion scheduler

I added `BalancedCompletionScheduler` using the same strategy interface, windows,
workers and delay settings as Q3:

```sh
java -cp build Main balanced 4 2 10 4
```

For a window of N chunks, the estimated completion times are:

```text
T0 = RTT0/2 + n × L/BW0
T1 = RTT1/2 + (N − n) × L/BW1

n = (N × L/BW1 + (RTT1 − RTT0)/2) / (L/BW0 + L/BW1)
```

Here L = 1435 bytes, bandwidth is in bytes/second, and RTT is converted to seconds.
The result is rounded to the nearest chunk and clamped to [0, N]. The last partial
chunk is approximated as a full chunk for allocation, but only its actual bytes
are transmitted.

With equal RTTs this reduces to the bandwidth-ratio scheduler. A larger RTT on one
path shifts work to the other. For example, with 100 chunks, equal rates of 10
chunks/second, and RTTs of 0 and 2 seconds, the split is 55:45.

Each window refreshes the RTT/BW snapshot. Missing or stale bandwidth, or an invalid
RTT, triggers the logged minRTT fallback. Data and bandwidth probes keep Q3's same
per-path delay distributions. Window barriers and the service-delay simulation
remain approximations, so equal predicted times do not guarantee equal actual times.

### Validation

Tests check unit conversion, RTT effects, clamping, fallback and reduction to the
ratio split when RTTs match. For 1,000 seeded cases, every integer allocation was
checked to confirm the selected split minimizes the predicted completion-time
difference. File-transfer tests check hashes and verify each logged window against
an independent enumeration of the possible splits.

[Validation record](../results/q4/validation.txt)

## Q5-Q6

To be added after experiments.
