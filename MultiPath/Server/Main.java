import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

class AddDelay {
    private final double meanDelay;
    private final double stdDelay;
    private final Random random;

    AddDelay(double meanDelay, double stdDelay) {
        this(meanDelay, stdDelay, new Random());
    }

    AddDelay(double meanDelay, double stdDelay, Random random) {
        if (!Double.isFinite(meanDelay) || !Double.isFinite(stdDelay)
                || meanDelay < 0 || stdDelay < 0) {
            throw new IllegalArgumentException("Delay mean/std must be finite and nonnegative");
        }
        this.meanDelay = meanDelay;
        this.stdDelay = stdDelay;
        this.random = random;
    }

    // Milliseconds, normally distributed before rounding and clamping at zero.
    public synchronized int addDelay() {
        return (int) Math.min(Integer.MAX_VALUE,
                Math.max(0, Math.round(meanDelay + stdDelay * random.nextGaussian())));
    }

    void pause() throws InterruptedException { Thread.sleep(addDelay()); }
}

class Status{
    // Class to log status of current data transfer
    public volatile boolean status;
    public Status(boolean status){
        this.status = status;
    }
}

class RTTHandler implements Runnable {
    // This class handles the RTT control channel
    int serverPort;
    private InetAddress clientIP;
    private int clientPort;
    int timeout;
    int id;
    int bufSize = 20;
    int timestampSendingInternal;
    volatile float rttEstimate = 0.0F;
    Status status;
    private AddDelay delayer;

    public RTTHandler(int serverPort, int timeout, int id, int timestampSendingInternal, Status status, AddDelay delayer) {
        this.serverPort = serverPort;
        this.timeout = timeout;
        this.id = id;
        this.timestampSendingInternal = timestampSendingInternal; // in ms
        this.status = status;
        this.delayer = delayer;
    }

    public void updateEstimate(float newValue){
        this.rttEstimate = ((1 - 0.125F) * this.rttEstimate) + (0.125F * newValue);
    }

    public void connect() throws SocketException, UnknownHostException, InterruptedException {
        // Receive the "Hi" message from the client to get its IP and Port
        try (DatagramSocket socket = new DatagramSocket(this.serverPort)) {

            byte[] buf = new byte[this.bufSize];
            DatagramPacket dpSend;
            DatagramPacket dpReceive;

            // Check completion while waiting for the initial control handshake.
            socket.setSoTimeout(this.timeout);
            try {
                dpReceive = new DatagramPacket(buf, buf.length);
                while (!status.status) {
                    try {
                        socket.receive(dpReceive);
                        break;
                    } catch (SocketTimeoutException e) {
                        // No client yet; a failed/finished transfer can still shut down.
                    }
                }
                if (status.status) return;
                System.out.println(this.id + " Hi Received");

                // Get client IP and Port
                clientIP = dpReceive.getAddress();
                clientPort = dpReceive.getPort();
            } catch (
                    IOException e) {
                throw new RuntimeException(e);
            }

            while (!this.status.status) {
                // Sending timestamps
                long sentTimestamp = System.currentTimeMillis();
                long recvTimestamp;
                buf = String.valueOf(sentTimestamp).getBytes();
                byte[] recvBuf = new byte[buf.length];
                try {
                    dpSend = new DatagramPacket(buf, buf.length, this.clientIP, this.clientPort);
                    socket.send(dpSend);

                    dpReceive = new DatagramPacket(recvBuf, recvBuf.length);
                    socket.setSoTimeout(this.timeout);
                    socket.receive(dpReceive);
                    recvTimestamp = Long.parseLong(new String(recvBuf));
                    long currentTimeStamp = System.currentTimeMillis();
                    // Two independent one-way delay samples approximate the simulated round trip.
                    float rtt = (currentTimeStamp - recvTimestamp)
                            + delayer.addDelay() + delayer.addDelay();
                    if (rtt == 0){
                        rtt = 1.0F;
                    }
                    System.out.println(this.id + " Current RTT: " + rtt + " Estimates RTT: " + this.rttEstimate); //+ " " + recvTimestamp + " " + sentTimestamp);

                    this.updateEstimate(rtt);
                    if (this.status.status) {
                        System.out.println("Stopping Control Channels");
                        break;
                    }

                    // Sleep before next send
                    Thread.sleep(this.timestampSendingInternal);

                } catch (SocketTimeoutException e) {
                    System.out.println(this.id + " Resending Timestamp");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Override
    public void run() {
        try {
            connect();
        } catch (SocketException e) {
            throw new RuntimeException(e);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}

// Active TCP probes: separate from data sockets, one worker and estimate per path.
class BandwidthHandler implements Runnable, AutoCloseable {
    static final int PROBE_BYTES = 256 * 1024;
    static final int INTERVAL_MS = 1000;
    static final int TIMEOUT_MS = 3000;
    static final double ALPHA = 0.125;
    private final int id;
    private final AddDelay delay;
    private final ServerSocket listener;
    private final Thread worker;
    private volatile Socket connection;
    private volatile boolean closed;
    // NaN means no valid sample yet; schedulers must not treat it as zero bandwidth.
    volatile double bandwidthEstimateBytesPerSecond = Double.NaN;
    volatile long lastSampleNanos;

    BandwidthHandler(int port, int id) throws IOException {
        this(port, id, new AddDelay(0, 0));
    }

    BandwidthHandler(int port, int id, AddDelay delay) throws IOException {
        this.id = id;
        this.delay = delay;
        listener = new ServerSocket(port);
        worker = new Thread(this, "bandwidth-path-" + id);
    }

    void start() { worker.start(); }
    void awaitStopped() throws InterruptedException { worker.join(); }

    double updateEstimate(double sample) throws IOException {
        if (!Double.isFinite(sample) || sample <= 0) {
            throw new IOException("Invalid bandwidth sample: " + sample);
        }
        double old = bandwidthEstimateBytesPerSecond;
        bandwidthEstimateBytesPerSecond = Double.isNaN(old)
                ? sample : (1 - ALPHA) * old + ALPHA * sample;
        lastSampleNanos = System.nanoTime();
        return bandwidthEstimateBytesPerSecond;
    }

    public void run() {
        byte[] payload = new byte[PROBE_BYTES];
        new Random(id).nextBytes(payload);
        int probeId = 0;
        try (BufferedWriter log = Files.newBufferedWriter(Paths.get("Bandwidth-Path" + id + ".csv"))) {
            log.write("probe_id,payload_bytes,elapsed_ns,sample_bytes_per_second,ewma_bytes_per_second\n");
            log.flush();
            while (!closed) {
                try (Socket peer = listener.accept()) {
                    connection = peer;
                    if (closed) break;
                    peer.setSoTimeout(TIMEOUT_MS);
                    peer.setTcpNoDelay(true);
                    DataInputStream input = new DataInputStream(peer.getInputStream());
                    DataOutputStream output = new DataOutputStream(peer.getOutputStream());
                    while (!closed) {
                        int currentId = probeId++;
                        output.writeInt(currentId);
                        output.writeInt(PROBE_BYTES);
                        output.flush();
                        // Client starts its timer BEFORE READY; payload cannot be pre-buffered.
                        if (input.readInt() != currentId) throw new IOException("Wrong READY id");
                        // Match the data path's per-1435-byte service delay.
                        for (int offset = 0; offset < payload.length; offset += 1435) {
                            delay.pause();
                            output.write(payload, offset, Math.min(1435, payload.length - offset));
                        }
                        output.flush();
                        if (input.readInt() != currentId) throw new IOException("Wrong result id");
                        long elapsed = input.readLong();
                        if (elapsed <= 0) throw new IOException("Invalid probe duration");
                        double sample = PROBE_BYTES * 1_000_000_000.0 / elapsed;
                        double estimate = updateEstimate(sample);
                        log.write(String.format(Locale.ROOT, "%d,%d,%d,%.6f,%.6f%n",
                                currentId, PROBE_BYTES, elapsed, sample, estimate));
                        log.flush();
                        System.out.printf(Locale.ROOT,
                                "BW Path %d: sample %.3f Mbps, estimate %.3f Mbps%n",
                                id, sample * 8 / 1_000_000, estimate * 8 / 1_000_000);
                        Thread.sleep(INTERVAL_MS);
                    }
                } catch (IOException e) {
                    if (!closed) System.err.println("BW Path " + id + ": " + e.getMessage());
                    // A partial record is discarded by closing this connection, not resumed.
                } finally {
                    connection = null;
                }
            }
        } catch (IOException e) {
            if (!closed) System.err.println("BW log Path " + id + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void close() throws IOException {
        closed = true;
        worker.interrupt();
        try {
            listener.close();
        } finally {
            Socket peer = connection;
            if (peer != null) peer.close();
        }
    }
}

// Strategies receive one immutable snapshot per scheduling window.
class PathMetrics {
    final double rttMs;
    final double bandwidth;
    PathMetrics(double rttMs, double bandwidth) {
        this.rttMs = rttMs;
        this.bandwidth = bandwidth;
    }
    boolean hasBandwidth() { return Double.isFinite(bandwidth) && bandwidth > 0; }
}

interface Scheduler {
    String name();
    default boolean requiresBandwidth() { return false; }
    default boolean hasUsableMetrics(PathMetrics first, PathMetrics second) {
        return !requiresBandwidth() || (first.hasBandwidth() && second.hasBandwidth());
    }
    // Return the number of chunks assigned to path 0; the rest go to path 1.
    int chunksOnFirstPath(int count, PathMetrics first, PathMetrics second);
}

class MinRttScheduler implements Scheduler {
    public String name() { return "minrtt"; }
    public int chunksOnFirstPath(int count, PathMetrics first, PathMetrics second) {
        return first.rttMs <= second.rttMs ? count : 0;
    }
}

class BandwidthRatioScheduler implements Scheduler {
    public String name() { return "bwratio"; }
    public boolean requiresBandwidth() { return true; }
    public int chunksOnFirstPath(int count, PathMetrics first, PathMetrics second) {
        if (!hasUsableMetrics(first, second)) {
            return new MinRttScheduler().chunksOnFirstPath(count, first, second);
        }
        // Scaling avoids overflow when adding two large estimates.
        double scale = Math.max(first.bandwidth, second.bandwidth);
        double share = (first.bandwidth / scale)
                / (first.bandwidth / scale + second.bandwidth / scale);
        return Math.max(0, Math.min(count, (int) Math.round(count * share)));
    }
}

class BalancedCompletionScheduler implements Scheduler {
    public String name() { return "balanced"; }
    public boolean requiresBandwidth() { return true; }
    public boolean hasUsableMetrics(PathMetrics first, PathMetrics second) {
        return Scheduler.super.hasUsableMetrics(first, second)
                && Double.isFinite(first.rttMs) && first.rttMs >= 0
                && Double.isFinite(second.rttMs) && second.rttMs >= 0;
    }

    public int chunksOnFirstPath(int count, PathMetrics first, PathMetrics second) {
        if (count <= 0) return 0;
        if (!hasUsableMetrics(first, second)) {
            return new MinRttScheduler().chunksOnFirstPath(count, first, second);
        }
        // Solve RTT0/2 + n*L/BW0 = RTT1/2 + (N-n)*L/BW1.
        // BW is bytes/s, L is bytes/chunk, and RTT is converted from ms to s.
        double largest = Math.max(first.bandwidth, second.bandwidth);
        double smallest = Math.min(first.bandwidth, second.bandwidth);
        double w0 = first.bandwidth / largest;
        double w1 = second.bandwidth / largest;
        double share = w0 / (w0 + w1);
        // BW0*BW1/(BW0+BW1), avoiding overflow in the product/sum.
        double harmonicRate = smallest / (1 + smallest / largest);
        double latencyDifferenceSeconds = (second.rttMs - first.rttMs) / 2000.0;
        double n = count * share
                + latencyDifferenceSeconds * (harmonicRate / Server.CHUNK_BYTES);
        return (int) Math.round(Math.max(0, Math.min(count, n)));
    }
}

class Server {
    static final int CHUNK_BYTES = 1435;
    static final int WINDOW_CHUNKS = 256;
    private final ServerSocket listener1;
    private final ServerSocket listener2;
    private final Socket path1;
    private final Socket path2;
    private final RTTHandler[] rtt;
    private final BandwidthHandler[] bandwidth;
    private final AddDelay[] delay;
    private final Scheduler scheduler;

    Server(int port1, int port2, RTTHandler rtt1, RTTHandler rtt2,
           BandwidthHandler bw1, BandwidthHandler bw2, Scheduler scheduler,
           AddDelay delay1, AddDelay delay2) throws IOException {
        listener1 = new ServerSocket(port1);
        listener2 = new ServerSocket(port2);
        System.out.println("Waiting for data clients on " + port1 + " and " + port2);
        path1 = listener1.accept();
        System.out.println("Connected to Path1 on port: " + port1);
        path2 = listener2.accept();
        System.out.println("Connected to Path2 on port: " + port2);
        rtt = new RTTHandler[]{rtt1, rtt2};
        bandwidth = new BandwidthHandler[]{bw1, bw2};
        delay = new AddDelay[]{delay1, delay2};
        this.scheduler = scheduler;
    }

    private PathMetrics metrics(int path) {
        BandwidthHandler probe = bandwidth[path];
        long age = System.nanoTime() - probe.lastSampleNanos;
        double rate = probe.lastSampleNanos != 0 && age <= 10_000_000_000L
                ? probe.bandwidthEstimateBytesPerSecond : Double.NaN;
        return new PathMetrics(rtt[path].rttEstimate, rate);
    }

    // Spread each window's assigned chunks through the global sequence order.
    static List<List<Integer>> assign(int start, int count, int firstCount) {
        List<List<Integer>> paths = new ArrayList<>();
        paths.add(new ArrayList<>());
        paths.add(new ArrayList<>());
        int previous = 0;
        for (int i = 0; i < count; i++) {
            int cumulative = (int) ((long) (i + 1) * firstCount / count);
            int path = cumulative > previous ? 0 : 1;
            paths.get(path).add(start + i);
            previous = cumulative;
        }
        return paths;
    }

    private void sendChunks(DataOutputStream output, List<Integer> chunks,
                            byte[] file, AddDelay delayer) throws IOException, InterruptedException {
        for (int sequence : chunks) {
            int offset = sequence * CHUNK_BYTES;
            int length = Math.min(CHUNK_BYTES, file.length - offset);
            long sentTime = System.currentTimeMillis();
            delayer.pause();
            output.writeInt(sequence);
            output.writeInt(length);
            output.writeLong(sentTime);
            output.write(file, offset, length);
            output.flush();
        }
        output.flush();
    }

    private static void await(Future<?> result) throws IOException, InterruptedException {
        try { result.get(); }
        catch (ExecutionException e) { throw new IOException("Data-path worker failed", e.getCause()); }
    }

    void sendFile() throws IOException, InterruptedException {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (Socket first = path1; Socket second = path2;
             ServerSocket l1 = listener1; ServerSocket l2 = listener2;
             BufferedWriter log = Files.newBufferedWriter(Paths.get("Scheduler.csv"))) {
            Thread.sleep(2000); // RTT warm-up, excluded from sender duration below.
            if (scheduler.requiresBandwidth()) {
                long deadline = System.nanoTime() + 15_000_000_000L;
                while ((!metrics(0).hasBandwidth() || !metrics(1).hasBandwidth())
                        && System.nanoTime() < deadline) Thread.sleep(50);
            }
            byte[] file = Files.readAllBytes(Paths.get("video/CN_Lect_NPTEL.zip"));
            int total = (int) ((file.length + (long) CHUNK_BYTES - 1) / CHUNK_BYTES);
            first.setTcpNoDelay(true); second.setTcpNoDelay(true);
            DataOutputStream out1 = new DataOutputStream(new BufferedOutputStream(first.getOutputStream()));
            DataOutputStream out2 = new DataOutputStream(new BufferedOutputStream(second.getOutputStream()));
            log.write("window,start_sequence,chunks,bw0_Bps,bw1_Bps,rtt0_ms,rtt1_ms,path0_chunks,path1_chunks,mode\n");
            long startTime = System.nanoTime();
            out1.writeLong(file.length);
            out2.writeLong(file.length);
            long[] counts = new long[2];
            int window = 0;
            for (int start = 0; start < total; start += WINDOW_CHUNKS) {
                int count = Math.min(WINDOW_CHUNKS, total - start);
                PathMetrics m0 = metrics(0), m1 = metrics(1);
                int firstCount = scheduler.chunksOnFirstPath(count, m0, m1);
                String mode = scheduler.name();
                if (!scheduler.hasUsableMetrics(m0, m1))
                    mode = "minrtt-fallback";
                log.write(String.format(Locale.ROOT, "%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%d,%d,%s%n",
                        window++, start, count, m0.bandwidth, m1.bandwidth,
                        m0.rttMs, m1.rttMs, firstCount, count - firstCount, mode));
                log.flush();
                List<List<Integer>> chunks = assign(start, count, firstCount);
                Future<?> a = workers.submit(() -> { sendChunks(out1, chunks.get(0), file, delay[0]); return null; });
                Future<?> b = workers.submit(() -> { sendChunks(out2, chunks.get(1), file, delay[1]); return null; });
                // Bounded windows prevent an unbounded queue on the slower path.
                await(a); await(b);
                counts[0] += firstCount; counts[1] += count - firstCount;
            }
            out1.writeInt(-1); out2.writeInt(-1);
            out1.flush(); out2.flush();
            double seconds = (System.nanoTime() - startTime) / 1e9;
            System.out.printf(Locale.ROOT, "TRANSFER scheduler=%s bytes=%d chunks=%d path0=%d path1=%d sender_seconds=%.6f%n",
                    scheduler.name(), file.length, total, counts[0], counts[1], seconds);
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 0 && args.length != 1 && args.length != 5) {
            throw new IllegalArgumentException("Usage: Main [minrtt|bwratio|balanced [mean0 std0 mean1 std1]]");
        }
        String name = args.length == 0 ? "minrtt" : args[0];
        Scheduler scheduler;
        switch (name) {
            case "minrtt": scheduler = new MinRttScheduler(); break;
            case "bwratio": scheduler = new BandwidthRatioScheduler(); break;
            case "balanced": scheduler = new BalancedCompletionScheduler(); break;
            default: throw new IllegalArgumentException("Unknown scheduler: " + name);
        }
        double mean0 = args.length == 5 ? Double.parseDouble(args[1]) : 0;
        double std0 = args.length == 5 ? Double.parseDouble(args[2]) : 0;
        double mean1 = args.length == 5 ? Double.parseDouble(args[3]) : 0;
        double std1 = args.length == 5 ? Double.parseDouble(args[4]) : 0;
        AddDelay delay0 = new AddDelay(mean0, std0), delay1 = new AddDelay(mean1, std1);
        System.out.printf(Locale.ROOT, "CONFIG scheduler=%s window=%d mean0=%.3f std0=%.3f mean1=%.3f std1=%.3f (ms)%n",
                name, Server.WINDOW_CHUNKS, mean0, std0, mean1, std1);
        Status status = new Status(false);
        RTTHandler rtt0 = new RTTHandler(8001, 200, 0, 200, status, new AddDelay(mean0, std0));
        RTTHandler rtt1 = new RTTHandler(8002, 200, 1, 200, status, new AddDelay(mean1, std1));
        Thread rttThread0 = new Thread(rtt0), rttThread1 = new Thread(rtt1);
        try (BandwidthHandler bw0 = new BandwidthHandler(9001, 0, new AddDelay(mean0, std0));
             BandwidthHandler bw1 = new BandwidthHandler(9002, 1, new AddDelay(mean1, std1))) {
            bw0.start(); bw1.start(); rttThread0.start(); rttThread1.start();
            try {
                new Server(6001, 6002, rtt0, rtt1, bw0, bw1, scheduler, delay0, delay1).sendFile();
            } finally {
                status.status = true;
                bw0.close(); bw1.close();
                bw0.awaitStopped(); bw1.awaitStopped();
                rttThread0.join(); rttThread1.join();
            }
        }
    }
}
