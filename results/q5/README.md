# Q5 and Q6 experiments

Three schedulers are run under each required delay configuration:

- A: path 0 mean/std 4/2 ms; path 1 mean/std 10/4 ms.
- B: both paths mean/std 10/4 ms.

The file, 1435-byte chunks, 256-chunk windows, probe size/interval, smoothing and
delay model remain fixed. Each case has a fresh Java server/client process.
One measured run per case is planned; conclusions will describe this set without
claiming statistical significance. Q3/Q4 demonstration runs are not substituted
for Q5 client times.

Each batch contains plan.json, server/CASE logs, client/CASE evidence, and combined
summary files. Client elapsed seconds include warmup and end after output flush.
Source/output size and SHA-256 must match before a case is accepted.
