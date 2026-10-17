# Assignment 3: Multipath File Transfer

Name: ____________________  
Roll number: ____________________

The experiments use a 53,186,840-byte ZIP transferred over two TCP connections.
Path 1 and Path 2 correspond to IDs 0 and 1 in the logs. Payloads are at most
1435 bytes (37,064 chunks in total), and schedulers allocate windows of up to 256 chunks.

## Q1. In-order delivery

Chunks can arrive in a different order on the two paths. I gave every chunk a
global sequence number and made both receiver threads share one reassembler.
A later chunk waits in a map until all earlier chunks have been written.

```java
// Inside the synchronized accept() method, after validation:
pending.put(sequence, payload);
while (pending.containsKey(nextExpected)) {
    byte[] next = pending.remove(nextExpected);
    output.write(next);
    written += next.length;
    nextExpected++;
}
```

For example, arrival order `1, 0, 3, 2` is written as `0, 1, 2, 3`. Only the shared
reassembler writes the output file.

```text
Connection header: total file size (long)
Chunk: sequence (int) | payload length (int) | timestamp (long) | payload
End of path: sequence = -1
```

The receiver uses `readFully(payload)` because a TCP read may contain only part of
a chunk. The last chunk carries its actual length. Completion requires both paths
to finish and the reconstructed size to match the advertised size.

Tests passed for reordered arrivals, fragmented reads, empty files, exact/partial
chunks, and rejection of truncated transfers. Downloaded files were checked using
SHA-256. [Q1 checks](../results/q1/validation.txt)

## Q2. Bandwidth estimation

Each path has a separate TCP probe channel, on ports 9001 and 9002. The server sends
a 256 KiB burst, and the client measures its duration with `System.nanoTime()`.
The client starts timing before sending READY, so bytes cannot arrive before the
timer starts. It returns the elapsed time to the server.

```text
Server: probe ID and size → Client
Client: start timer, send READY → Server
Server: send payload → Client
Client: stop timer, return duration → Server
```

```java
double sample = PROBE_BYTES * 1_000_000_000.0 / elapsed; // bytes/second
bandwidthEstimateBytesPerSecond = Double.isNaN(old)
        ? sample : 0.875 * old + 0.125 * sample;
```

I used EWMA smoothing with alpha = 0.125 to reduce sudden changes from noisy samples.
The first sample initializes the estimate. Probes pause for one second after each
completed measurement. A 256 KiB burst makes the fixed READY delay a smaller fraction
of the measurement; smaller bursts would make the estimate more latency-dependent.

These are effective-throughput estimates. Separate TCP connections have their own
congestion state, and different ports can still share a physical bottleneck. Probe
traffic also consumes bandwidth. [Q2 checks](../results/q2/validation.txt)

## Q3. Bandwidth-ratio scheduler

The idea is to give more chunks to the path that can carry more data per second.
For example, a 2:1 bandwidth ratio should give approximately a 2:1 allocation.

```text
n = round(N × BW1 / (BW1 + BW2))
Path 1: n chunks
Path 2: N − n chunks
```

The core allocation is proportional to the two rates (the implementation scales
the rates first to avoid overflow):

```java
int firstCount = (int) Math.round(count * share);
// share = BW1 / (BW1 + BW2)
```

A `Scheduler` strategy chooses the allocation, so the sender can switch algorithms
without changing the transfer or reassembly code:

```java
interface Scheduler {
    String name();
    default boolean requiresBandwidth() { return false; }
    int chunksOnFirstPath(int count, PathMetrics first, PathMetrics second);
}
```

At each window, the sender reads fresh estimates and spreads the assigned chunks
through the global sequence numbers. Two workers send concurrently. Both finish
writing their current window before the next allocation. This bounds the work queued to the sender workers,
but the faster worker can wait at a window boundary. MinRTT also chooses a path
per window; it gives that window to the lower-RTT path.

I added delays using `AddDelay(mean, std)`:

```java
Math.max(0, Math.round(meanDelay + stdDelay * random.nextGaussian()))
```

Each worker sleeps before sending a chunk. Bandwidth probes use the same path's
distribution before each payload block of at most 1435 bytes. RTT samples add two
independent one-way delay samples. Negative delays are clamped to zero.

This is a service-delay simulation: sleeping per chunk also limits throughput.
If bandwidth is missing or older than 10 seconds, the scheduler falls back to
minRTT. The formula, delays, allocation counts and file hashes passed the tests.
[Q3 checks](../results/q3/validation.txt)

## Q4. Balanced subflow completion scheduler

This scheduler tries to make both paths finish their assigned work together. It
accounts for initial latency as well as bandwidth. A path with a higher RTT may
need a smaller share even when its bandwidth is good.

```text
T1 = RTT1/2 + n × L/BW1
T2 = RTT2/2 + (N − n) × L/BW2

Set T1 = T2:
n = (N × L/BW2 + (RTT2 − RTT1)/2) / (L/BW1 + L/BW2)
```

Here, L = 1435 bytes, BW is bytes/second, and RTT is in seconds. The result is rounded
to the nearest integer and clamped to `[0, N]`. The code uses an equivalent scaled
calculation to avoid overflow. The final short chunk is approximated as full-size
when allocating work; only its real bytes are sent.

The implementation adds a latency correction to the bandwidth-ratio share:

```java
double harmonicRate = smallest / (1 + smallest / largest);
double deltaSeconds = (second.rttMs - first.rttMs) / 2000.0;
double n = count * share + deltaSeconds * (harmonicRate / Server.CHUNK_BYTES);
return (int) Math.round(Math.max(0, Math.min(count, n)));
```

Here `harmonicRate = BW1 × BW2 / (BW1 + BW2)` and `share = BW1 / (BW1 + BW2)`.
With equal RTTs, the equation reduces to the bandwidth-ratio allocation. For 100
chunks, equal rates of 10 chunks/second, and RTTs of 0 and 2 seconds, it gives 55:45.

`BalancedCompletionScheduler` uses the same strategy interface, workers and delays.
Unavailable bandwidth or an invalid RTT triggers the minRTT fallback.
Tests checked its split against every possible integer allocation for 1,000 seeded
cases, plus complete file transfers. [Q4 checks](../results/q4/validation.txt)

## Q5. File download experiments

I ran all three schedulers on two connected laptops using these delay settings
(mean and standard deviation, in milliseconds):

```text
Case A: Path 1 = (4, 2),  Path 2 = (10, 4)
Case B: Path 1 = (10, 4), Path 2 = (10, 4)
```

Each combination used a fresh server/client process and the same file, chunk size,
window size, probe settings and delay model. I measured one download per combination.
The batch runs the cases automatically:

```sh
# Server laptop, from the repository root:
python3 MultiPath/experiments/run_q5.py server --batch q5-20261017
# Client laptop:
python3 MultiPath/experiments/run_q5.py client --batch q5-20261017 --host 10.42.184.197
```

The client measures from receiver-thread start through successful reconstruction
and output flush using its monotonic clock:

```java
(System.nanoTime() - downloadStart) / 1e9
```

This includes the configured 2-second server warmup and any initial bandwidth-estimate
wait (up to 15 seconds for the bandwidth-based schedulers). Compilation, connection
setup and post-download hashing are outside the measurement. The bandwidth freshness
limit is 10 seconds. Sender duration is recorded separately and is not used below.

| Delay case | Scheduler | Client download time (s) |
|---|---|---:|
| A | minRTT | 192.765 |
| A | Bandwidth ratio | 137.545 |
| A | Balanced completion | 141.235 |
| B | minRTT | 458.109 |
| B | Bandwidth ratio | 232.171 |
| B | Balanced completion | 240.650 |

All six downloads matched the source size and SHA-256, and all 870 scheduling
windows passed their allocation checks, with no fallback windows.

```text
653bfc4afd75e6b758b9355ee8d2eea00bc888991e7a2725b6fede2eb51ca8bc
```

[Combined measurements](../results/q5/q5-20261017/summary.csv) ·
[Raw client/server evidence](../results/q5/q5-20261017/README.md)

## Q6. Observations

Bandwidth ratio had the shortest measured time in both cases. I calculated the
download-time reduction relative to minRTT as:

```text
reduction (%) = 100 × (time_minRTT − time_scheduler) / time_minRTT
```

**Case A:** Bandwidth ratio reduced the download time by **28.6%** and balanced
completion by **26.7%** compared with minRTT. The lower-delay path can process
chunks faster in this simulation, so the bandwidth-based schedulers gave it about
70% of the work and still used the other path. MinRTT sent almost all chunks over
the lower-RTT path, leaving the other worker mostly unused.

**Case B:** Bandwidth ratio reduced the time by **49.3%**, and balanced completion by
**47.5%**. Both paths had similar conditions, so sending roughly half the file on
each path allowed concurrent progress. MinRTT could switch paths between windows,
but it still used only one path in a window.

Balanced completion was 3.690 s slower than bandwidth ratio in A and 8.479 s slower
in B. Its RTT term adjusts the split, but does not guarantee a faster actual download.
When RTTs are similar, its formula approaches the bandwidth-ratio rule. Probe noise,
random delays and waiting at window boundaries can affect the observed difference.

These results support using both paths for this file-transfer workload. They do not
establish a universal ranking between the two bandwidth-based schedulers: each value
is one run, with independent random delay samples. Both logical paths use the same
server IP, and sleeping per chunk models service delay as well as latency. Conclusions
apply to this setup; repeated trials would be needed to quantify variability.

[Calculations](../results/q6/comparison.json)

## References

- Assignment3-Multipath_Assignment (1).pdf, Questions 1-6.
- Multipath-Tutorial.pdf, multipath setup, schedulers and estimate smoothing.
