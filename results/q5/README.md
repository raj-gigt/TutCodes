# Q5 and Q6 experiments

Three schedulers are run under each required delay configuration:

- A: path 0 mean/std 4/2 ms; path 1 mean/std 10/4 ms.
- B: both paths mean/std 10/4 ms.

The file, 1435-byte chunks, 256-chunk windows, probe size/interval, smoothing and
delay model remain fixed. Each case has a fresh Java server/client process.
One measured run per case completed; conclusions describe this set without
claiming statistical significance. Q3/Q4 demonstration runs are not substituted
for Q5 client times.

Each batch contains plan.json, server/CASE logs, client/CASE evidence, and combined
summary files. Client elapsed seconds include warmup and end after output flush.
Source/output size and SHA-256 must match before a case is accepted.

## Client batch q5-20261017

All six cases completed successfully. Times measure receiver start through output
flush, including warmup. Each downloaded ZIP was 53,186,840 bytes.

| Case | Client seconds | Hash verification |
|---|---:|---|
| A-minrtt | 192.764870 | PASS |
| A-bwratio | 137.545372 | PASS |
| A-balanced | 141.235329 | PASS |
| B-minrtt | 458.109298 | PASS |
| B-bwratio | 232.171317 | PASS |
| B-balanced | 240.650121 | PASS |

All six output files have this SHA-256:

```text
653bfc4afd75e6b758b9355ee8d2eea00bc888991e7a2725b6fede2eb51ca8bc
```

[Per-case client logs and metadata](q5-20261017/client/). All six case labels, source/output hashes, byte counts, client console times and
870 scheduler windows have been checked against the server records.

## Combined verification

All six client/source hashes and byte counts match. Combined times are available in
[q5-20261017/summary.csv](q5-20261017/summary.csv), with full records in summary.json.
[verification.json](q5-20261017/verification.json) records the cross-checks. All 870
windows match their scheduler rules, and no fallback windows occurred.
