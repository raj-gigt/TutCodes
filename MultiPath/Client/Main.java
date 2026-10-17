import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

class Status{
    // Class to log status of current data transfer
    public volatile boolean status;
    public Status(boolean status){
        this.status = status;
    }
}

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

class RTTHandler implements Runnable {
    // This class handles the RTT control channel
    String serverIp;
    int serverPort;
    int timeout;
    int id;
    int recvBufSize;
    Status status;

    RTTHandler(String serverIp, int serverPort, int timeout, int id, Status status) throws IOException {
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.timeout = timeout;
        this.id = id;
        this.status = status;
    }

    public void connect() throws SocketException, UnknownHostException {
        // Send a "Hi" message to the server to create a separate thread for the client
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress ip_server = InetAddress.getByName(this.serverIp);

            recvBufSize = 20;
            byte[] hiBuf = "Hi".getBytes();
            byte[] recvBuf = new byte[recvBufSize];
            DatagramPacket dpSend = new DatagramPacket(hiBuf, hiBuf.length, ip_server, this.serverPort);
            DatagramPacket dpReceive;
            boolean doneHandshake = true;
            try {
                while(doneHandshake && !this.status.status){
                    socket.send(dpSend);
                    System.out.println(this.id + " Hi Sent");

                    dpReceive = new DatagramPacket(recvBuf, recvBuf.length);
                    socket.setSoTimeout(this.timeout);
                    try{
                        socket.receive(dpReceive);
                        doneHandshake = false;
                        recvBufSize = dpReceive.getLength();
    //                    System.out.println(this.id + " received bytes " + dpReceive.getLength() + " " + new String(recvBuf) + " Received HiAck");
                        System.out.println(this.id + " Received HiAck");
                    } catch (SocketTimeoutException e){
                        System.out.println(this.id + " Resending Hi");
                    }
                }
            } catch (
                    IOException e) {
                throw new RuntimeException(e);
            }

            // Receiving timestamps for RTT estimation
            while (!this.status.status) {
                recvBuf = new byte[recvBufSize];
                dpReceive = new DatagramPacket(recvBuf, recvBuf.length);
                socket.setSoTimeout(this.timeout);

                try {
                    socket.receive(dpReceive);
    //                long recvTimestamp = Long.parseLong(new String(recvBuf));
    //                System.out.println(this.id + " Received timestamp: " + recvTimestamp);

                    dpSend = new DatagramPacket(recvBuf, recvBuf.length, ip_server, this.serverPort);
                    socket.send(dpSend);
                    if (this.status.status){
                        System.out.println("Stopping controls channels");
                        break;
                    }
                } catch (SocketTimeoutException e) {
                    // Periodically check completion even after the server stops probing.
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
        }
    }
}

// One instance is shared by both TCP receivers; only this object writes the file.
class Reassembler implements Closeable {
    private final OutputStream output;
    private final Map<Integer, byte[]> pending = new HashMap<>();
    private long fileSize = -1;
    private long written = 0;
    private int nextExpected = 0;

    Reassembler(OutputStream output) {
        this.output = output;
    }

    synchronized void setFileSize(long size) throws IOException {
        if (size < 0 || size > Integer.MAX_VALUE || (fileSize != -1 && fileSize != size)) {
            throw new IOException("Invalid or inconsistent file size: " + size);
        }
        fileSize = size;
    }

    synchronized void accept(int sequence, byte[] payload) throws IOException {
        long offset = (long) sequence * 1435;
        if (fileSize < 0 || sequence < nextExpected || pending.containsKey(sequence)
                || offset >= fileSize || sequence < 0
                || payload.length != Math.min(1435L, fileSize - offset)) {
            throw new IOException("Invalid or duplicate chunk: " + sequence);
        }
        pending.put(sequence, payload);
        // A later chunk waits here until all preceding chunks have arrived.
        while (pending.containsKey(nextExpected)) {
            byte[] next = pending.remove(nextExpected);
            output.write(next);
            written += next.length;
            nextExpected++;
        }
    }

    synchronized void verifyComplete() throws IOException {
        if (fileSize < 0 || written != fileSize || !pending.isEmpty()) {
            throw new IOException("Incomplete file: wrote " + written + " of " + fileSize + " bytes");
        }
        output.flush();
    }

    public void close() throws IOException {
        output.close();
    }
}

class Client implements Runnable, Closeable {
    private final Socket connectedSocket;
    private final int id;
    private final Reassembler reassembler;
    private IOException failure;

    Client(String serverIP, int portToConnect, int id, Reassembler reassembler) throws IOException {
        this.connectedSocket = new Socket(serverIP, portToConnect);
        this.id = id;
        this.reassembler = reassembler;
        System.out.println("Connected : " + id);
    }

    public void receiveFile() throws IOException {
        DataInputStream input = new DataInputStream(connectedSocket.getInputStream());
        reassembler.setFileSize(input.readLong());
        while (true) {
            // readInt/readLong/readFully handle TCP fragmentation across arbitrary reads.
            int sequence = input.readInt();
            if (sequence == -1) {
                return;
            }
            int length = input.readInt();
            if (length <= 0 || length > 1435) {
                throw new IOException("Invalid payload length: " + length);
            }
            long sentTime = input.readLong();
            byte[] payload = new byte[length];
            input.readFully(payload);
            long lag = System.currentTimeMillis() - sentTime;
            reassembler.accept(sequence, payload);
            String log = "Network Lag of Path " + id + " : " + lag + "\n";
            Files.write(Paths.get("MultiPath.txt"), log.getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    public void run() {
        try {
            receiveFile();
        } catch (IOException e) {
            failure = e;
        } finally {
            try { close(); } catch (IOException e) { if (failure == null) failure = e; }
        }
    }

    void checkSuccess() throws IOException {
        if (failure != null) throw failure;
    }

    public void close() throws IOException {
        connectedSocket.close();
    }
}

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        Status done = new Status(false);
        String serverIP = args.length > 0 ? args[0] : "10.42.184.197";
        String helperIP = args.length > 1 ? args[1] : serverIP;
        Files.createDirectories(Paths.get("video"));

        // Open the output once, instead of letting each receiver overwrite it independently.
        try (Reassembler shared = new Reassembler(new BufferedOutputStream(
                    new FileOutputStream("video/CN_Lect_NPTEL.zip")));
             Client client1 = new Client(serverIP, 6001, 0, shared);
             Client client2 = new Client(helperIP, 6002, 1, shared)) {
            Thread probe1 = new Thread(new RTTHandler(serverIP, 8001, 200, 0, done));
            Thread probe2 = new Thread(new RTTHandler(helperIP, 8002, 200, 1, done));
            probe1.start();
            probe2.start();
            try {
                Thread receiver1 = new Thread(client1);
                Thread receiver2 = new Thread(client2);
                receiver1.start();
                receiver2.start();
                receiver1.join();
                receiver2.join();
                client1.checkSuccess();
                client2.checkSuccess();
                shared.verifyComplete();
                System.out.println("Complete file reconstructed in order.");
            } finally {
                // A single path finishing must not stop the other path's control channel.
                done.status = true;
                probe1.join();
                probe2.join();
            }
        }
    }
}
