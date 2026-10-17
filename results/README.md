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
