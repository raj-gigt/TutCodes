import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.*;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;

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

            // Getting Hi message
            try {
                dpReceive = new DatagramPacket(buf, buf.length);
                socket.receive(dpReceive);
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

        rttPath1Thread.start();
        rttPath2Thread.start();

        // Sending the video file
        Server server = new Server(portForPath1, portForPath2, rttHandlerPath1, rttHandlerPath2);

        try {
            server.sendFile();
        } finally {
            status.status = true;
        }

        rttPath1Thread.join();
        rttPath2Thread.join();

    }
}
