# Q1: in-order delivery over two TCP connections

The server keeps the tutorial's minRTT scheduler. Every file chunk gets a global
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

## Run

Compile both updated programs: the new protocol is incompatible with the old one.
Run each program from its own directory so the relative video/log paths resolve.

Server machine, from MultiPath/Server:

```sh
javac Main.java
java Main
```

Client machine, from MultiPath/Client:

```sh
javac Main.java
java Main SERVER_IP
```

An optional second address selects the second path: `java Main SERVER_IP SECOND_IP`.
With no arguments, the existing default address 10.42.184.197 is used for both.
The server reads video/CN_Lect_NPTEL.zip; the client creates/overwrites that relative
filename in its own directory. No cleanup script is needed. MultiPath.txt still
appends per-chunk lag measurements and is not cleared between runs.

The lag timestamps still depend on clock synchronization between machines; they
are not RTT measurements. Bandwidth probes, new schedulers, and Gaussian data-path
delays (Q2 onward) are not implemented here.

## Tests

From the repository root:

```sh
python3 MultiPath/tests/test_q1.py
```

Requires Java/Javac, Python 3, and free local ports 6001, 6002, 8001, 8002.
Tests compile into temporary directories and preserve the supplied ZIP and logs.
They verify SHA-256 equality for empty, exact-chunk, partial-chunk, and deliberately
reordered/fragmented transfers, plus rejection of truncated payloads. The reordered
case ends one path before the other delivers the missing first chunk.

The sender still loads the whole file into memory, and the reorder buffer can grow
when an early chunk is delayed. This is a teaching implementation, not a bounded-
memory streaming protocol. Failed downloads may leave a partial output file and
are reported as failures rather than successful downloads.
