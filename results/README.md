# Assignment 3 results

Store evidence under q1/, q2/, etc., and reference it from report/Assignment3-Report.md.
Keep original measurements separate from interpretations in the report.

## Q1 evidence

- q1/server-user-run.log: server-console excerpt supplied by the user after the
  Q1 changes. It is not a full run capture and contains no completion message,
  client output, file hash, or total download duration.
- The report records the user-verified matching server/client SHA-256 and the
  downloaded file size. A separate client console log was not captured.
- q1/validation.txt: recorded results of the five integration tests run during
  implementation. Test source: ../MultiPath/tests/test_q1.py.

For subsequent experiments, use distinct files for each run and record scheduler,
path parameters, file size, timing boundaries, and repetitions. MultiPath.txt
appends between executions, so it should not be treated as one run without
establishing its boundaries. Preserve existing logs before clearing them.

## Q2 evidence

- q2/validation.txt: Q2 probe integration test results.
- q2/controlled-path0.csv and controlled-path1.csv: deliberately supplied durations
  to check per-path rate calculations and EWMA; not network measurements.
- q2/loopback-path0.csv and loopback-path1.csv: real samples exchanged by the Java
  probe implementations on this machine; not the assignment's LAN experiments.

The server's runtime Bandwidth-Path0.csv and Bandwidth-Path1.csv are overwritten
on restart. Copy each run's files into a distinct results subdirectory to retain
measurements. CSV rates are bytes/second; console rates are decimal Mbps.

- q2/lan-run-20261017-145341/: captured two-machine run, including server console,
  raw per-path CSVs, parameters, source hash, and calculated summaries.

Client-reported SHA-256 for the Q2 two-machine run (matches the server source):

```text
653bfc4afd75e6b758b9355ee8d2eea00bc888991e7a2725b6fede2eb51ca8bc  video/CN_Lect_NPTEL.zip
```

## Q3 client verification — 17 October 2026

The client completed successfully and reconstructed a 53,186,840-byte ZIP.
[Client output and verification](q3/client-verification.txt).

```text
653bfc4afd75e6b758b9355ee8d2eea00bc888991e7a2725b6fede2eb51ca8bc  video/CN_Lect_NPTEL.zip
```

The client hash and file size match the server source for the Q3 retry. This
confirms file integrity, not client download time.
## Q3 evidence

- q3/validation.txt: scheduler and delay tests, plus delayed bandwidth-ratio transfers.
- Each Q3 LAN run archives Scheduler.csv alongside the bandwidth CSVs and server log.
  Sender duration excludes warmup but does not establish client download completion.

- q3/lan-run-20261017-151847/: interrupted first attempt; preserved as a failed run.
- q3/lan-run-20261017-152422/: retry completed on the server; all allocations checked.
  Client completion, file size, and SHA-256 are verified for this retry.
