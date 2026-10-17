# Assignment 3: Multipath File Transfer

Name: ____________________  
Roll number: ____________________

## Q1. In-order delivery

I added a global sequence number to each chunk and used one shared reassembler
for both receiver threads. The minRTT scheduler is unchanged.

### Packet format

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

## Q2-Q6

To be added after implementation and experiments.
