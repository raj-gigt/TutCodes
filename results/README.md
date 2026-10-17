# Assignment 3 results

Store evidence under q1/, q2/, etc., and reference it from report/Assignment3-Report.md.
Keep original measurements separate from interpretations in the report.

## Q1 evidence

- q1/server-user-run.log: server-console excerpt supplied by the user after the
  Q1 changes. It is not a full run capture and contains no completion message,
  client output, file hash, or total download duration.
- q1/validation.txt: recorded results of the five integration tests run during
  implementation. Test source: ../MultiPath/tests/test_q1.py.

For subsequent experiments, use distinct files for each run and record scheduler,
path parameters, file size, timing boundaries, and repetitions. MultiPath.txt
appends between executions, so it should not be treated as one run without
establishing its boundaries. Preserve existing logs before clearing them.
