# Q5 experiment batch: q5-20261017

Six sequential two-machine downloads use the same 53,186,840-byte file.
A = path 0 delay mean/std 4/2 ms and path 1 10/4 ms.
B = both paths delay mean/std 10/4 ms.
Each configuration tests minrtt, bwratio and balanced, once each.

- plan.json: case order, delay settings, file size/hash and timing boundary.
- server/CASE/server.log: complete server console output.
- server/CASE/run.json: revision, parameters, timestamps, exit code, counts and sender time.
- server/CASE/Scheduler.csv: per-window estimates and allocations.
- server/CASE/Bandwidth-Path*.csv: probe samples and EWMA in bytes/second.
- server/CASE/audit.json: checks of every allocation, chunk totals and probe calculations.
- client/CASE/run.json: client elapsed time, hash, size and success checks.
- client/CASE/client-attempt-*.log: captured client console evidence.
- summary.json / summary.csv: combined client/server results after verification.

Client time starts with the receiver threads and ends after both paths finish,
size verification and output flush. It includes server warmup/initial-estimate wait
and excludes compilation, connection setup, post-download hashing and control-thread
shutdown. Sender time has a different boundary and is retained separately.

Random delay draws are independent across runs. These are single observations per
case, not averages over repeated trials. Physical routes may share a bottleneck;
the path heterogeneity is created by the configured per-chunk service delays.
