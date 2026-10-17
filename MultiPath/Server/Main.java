import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;
import java.util.Locale;

class AddDelay{
    // This class adds delay to the paths
    private int meanDelay;
    private int stdDelay;
    private Random randomIntGenerator = new Random();

    public AddDelay(int meanDelay, int maxValueOfStdDelay){
        this.meanDelay = meanDelay;
        this.stdDelay = maxValueOfStdDelay;
    }

    public int addDelay(){
        return (this.meanDelay + randomIntGenerator.nextInt(this.stdDelay));
    }
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
                    float rtt = (currentTimeStamp - recvTimestamp) + Float.valueOf(delayer.addDelay());
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
    private final ServerSocket listener;
    private final Thread worker;
    private volatile Socket connection;
    private volatile boolean closed;
    // NaN means no valid sample yet; schedulers must not treat it as zero bandwidth.
    volatile double bandwidthEstimateBytesPerSecond = Double.NaN;
    volatile long lastSampleNanos;

    BandwidthHandler(int port, int id) throws IOException {
        this.id = id;
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
                        output.write(payload);
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

class Server{
    private ServerSocket serverSocketPath1;
    private ServerSocket serverSocketPath2;
    private Socket connectedSocketPath1;
    private Socket connectedSocketPath2;
    int sendBufferSize = 1435;
    RTTHandler rttHandlerPath1; RTTHandler rttHandlerPath2;
    private String videoFileName = "video/CN_Lect_NPTEL.zip";

    public Server(int path1Port, int path2Port, RTTHandler rttHandlerPath1, RTTHandler rttHandlerPath2) throws IOException {
        this.serverSocketPath1 = new ServerSocket(path1Port);
        this.serverSocketPath2 = new ServerSocket(path2Port);

        this.connectedSocketPath1 = serverSocketPath1.accept();
        System.out.println("Connected to Path1 on port: " + path1Port);

        this.connectedSocketPath2 = serverSocketPath2.accept();
        System.out.println("Connected to Path2 on port: " + path2Port);

        this.rttHandlerPath1 = rttHandlerPath1;
        this.rttHandlerPath2 = rttHandlerPath2;
    }

    public byte[] readFile(String filename) throws IOException {
        return Files.readAllBytes(Paths.get(filename));
    }

    public boolean sendFile() throws IOException, InterruptedException {
        try (Socket path1 = connectedSocketPath1;
             Socket path2 = connectedSocketPath2;
             ServerSocket listener1 = serverSocketPath1;
             ServerSocket listener2 = serverSocketPath2) {
            Thread.sleep(2000); // allow RTT estimates to warm up
            byte[] fileBytes = readFile(this.videoFileName);
            DataOutputStream out1 = new DataOutputStream(path1.getOutputStream());
            DataOutputStream out2 = new DataOutputStream(path2.getOutputStream());

            // Both paths describe the same file. Payload chunks are at most 1435 bytes.
            out1.writeLong(fileBytes.length);
            out2.writeLong(fileBytes.length);
            int sequence = 0;
            for (int offset = 0; offset < fileBytes.length;) {
                int length = Math.min(sendBufferSize, fileBytes.length - offset);
                DataOutputStream out = rttHandlerPath1.rttEstimate <= rttHandlerPath2.rttEstimate
                        ? out1 : out2;
                // Frame: global sequence (int), length (int), timestamp (long), payload.
                out.writeInt(sequence++);
                out.writeInt(length);
                out.writeLong(System.currentTimeMillis());
                out.write(fileBytes, offset, length);
                offset += length;
            }
            // Explicit end markers distinguish successful completion from truncated TCP data.
            out1.writeInt(-1);
            out2.writeInt(-1);
            out1.flush();
            out2.flush();
            return true;
        }
    }

}

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        int portForPath1 = 6001;
        int portForPath2 = 6002;
        int rttHandlerPortPath1 = 8001;
        int rttHandlerPortPath2 = 8002;
        int timeoutOfRttHi = 200;
        int timestampSendingInterval = 200;
        Status status = new Status(false);
        AddDelay delayerForPath1 = new AddDelay(10, 10);
        AddDelay delayerForPath2 = new AddDelay(10, 10);

        // For RTT Channel
        RTTHandler rttHandlerPath1 = new RTTHandler(rttHandlerPortPath1, timeoutOfRttHi, 0, timestampSendingInterval, status, delayerForPath1);
        RTTHandler rttHandlerPath2 = new RTTHandler(rttHandlerPortPath2, timeoutOfRttHi, 1, timestampSendingInterval, status, delayerForPath2);

        Thread rttPath1Thread = new Thread(rttHandlerPath1);
        Thread rttPath2Thread = new Thread(rttHandlerPath2);

        // Bind both bandwidth listeners before accepting the data connections.
        try (BandwidthHandler bandwidth1 = new BandwidthHandler(9001, 0);
             BandwidthHandler bandwidth2 = new BandwidthHandler(9002, 1)) {
            bandwidth1.start();
            bandwidth2.start();
            rttPath1Thread.start();
            rttPath2Thread.start();
            try {
                Server server = new Server(portForPath1, portForPath2, rttHandlerPath1, rttHandlerPath2);
                server.sendFile();
            } finally {
                status.status = true;
                bandwidth1.close();
                bandwidth2.close();
                bandwidth1.awaitStopped();
                bandwidth2.awaitStopped();
                rttPath1Thread.join();
                rttPath2Thread.join();
            }
        }
    }
}
