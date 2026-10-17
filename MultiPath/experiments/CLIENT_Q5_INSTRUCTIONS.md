# Client handoff: Assignment 3, Q5

You are operating the client laptop. The server laptop is running the matching
six-case batch. Run the client cases, capture their results, and share the evidence
through this repository. Keep the process alive until the entire batch finishes.

## 1. Pull and start

From the repository root, pull the latest main branch. Check local changes before
pulling and preserve any existing work.

```sh
git status --short
git pull --rebase
python3 MultiPath/experiments/run_q5.py client --batch q5-20261017 --host 10.42.184.197
```

Python 3, Java and javac are required. The script compiles the client automatically.
No server needs to be started on this laptop. The client connects to server TCP
ports 6001/6002 and 9001/9002, plus UDP RTT ports 8001/8002.

**Run the command once and leave it running.** Keep the laptop awake and connected
to the same reachable network. Use a persistent terminal/tool session and poll its
output. A short tool yield is fine; do not impose a short overall process timeout
or terminate the process because no new output appears. Individual runs can take
several minutes, and the complete set may take 20-25 minutes or longer.

The script prints CLIENT_STARTED and CLIENT_FINISHED for each case. Most Java
console output is captured in log files while a transfer runs, so quiet periods
are expected. Finish only when CLIENT_BATCH_COMPLETE appears or a failure is reported.
Do not launch another client or network integration tests during the batch.

## 2. Fixed order and parameters

The script automatically runs these six cases in the same order as the server:

```text
A-minrtt:   delays 4/2 ms on path 0, 10/4 ms on path 1
A-bwratio:  delays 4/2 ms on path 0, 10/4 ms on path 1
A-balanced: delays 4/2 ms on path 0, 10/4 ms on path 1
B-minrtt:   delays 10/4 ms on both paths
B-bwratio:  delays 10/4 ms on both paths
B-balanced: delays 10/4 ms on both paths
```

The server selects the scheduler and adds the delays; the client command stays the
same. Allow the script to handle brief connection-refused retries between cases.
The batch name must remain q5-20261017 on both laptops.

## 3. Evidence and success checks

For each case the script writes:

```text
results/q5/q5-20261017/client/CASE/run.json
results/q5/q5-20261017/client/CASE/client-attempt-N.log
```

The JSON records client elapsed seconds, the timing boundary, exit code, byte count,
SHA-256, case label and Git revision. Each case must have verified=true and contain
"Complete file reconstructed in order." in its successful console log.

All downloads must have:

```text
Size:   53186840 bytes
SHA256: 653bfc4afd75e6b758b9355ee8d2eea00bc888991e7a2725b6fede2eb51ca8bc
```

Use the printed "Client elapsed seconds (receiver start through flush, includes
warmup)" value. Shell wall time includes other work and is not the experiment metric.
Do not substitute server sending time, per-chunk lag, or a previous Q3/Q4 time.

The downloaded video files and per-chunk lag logs are excluded from Git. Keep the
run.json files and client-attempt logs as the recorded evidence.

## 4. On failure

The script stops on a connected-transfer error, integrity mismatch or missing time.
Preserve its final output, case directory and all logs. Tell the user which case
failed and its exact error. Do not restart from case A, delete existing result
folders, relabel a case, or run an extra client without coordinating with the server.
The runner refuses to overwrite existing case directories to protect measurements.

## 5. Commit and push client evidence

Once all six cases pass, check that only the intended evidence is staged. Preserve
unrelated local changes. The server laptop may have pushed server logs while the
client was running; integrate those changes before pushing, preserving both sides.

```sh
git add results/q5/q5-20261017/client
git commit -m "Record Q5 client times and download verification"
git pull --rebase
git push origin main
```

If only some cases passed before a failure, share their evidence and failure logs
with a clearly labeled commit; do not claim that the batch completed.

Finally, report the six case labels and client times to the user. The server Codex
will pull this evidence, compare source/output hashes, combine the results and write
Q6 and the final report.
