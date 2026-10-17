import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

class Client {
    private Socket connectedSocket;
    int recvBufferSize = 1448;
    private String videoFileName = "video/CN_Lect_NPTEL.zip";

    public Client(String serverIP, int portToConnect) throws IOException {
        this.connectedSocket = new Socket(serverIP, portToConnect);
        System.out.println("Connected");
    }

    public void receiveFile() throws IOException {
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();

        while (true) {
            byte[] chunk = new byte[this.recvBufferSize];
            int len = this.connectedSocket.getInputStream().read(chunk);
//            System.out.println("Received " + len + " Bytes");
            if (len == -1) {
                try (OutputStream outputStream = new FileOutputStream(this.videoFileName)) {
                    fileBytes.writeTo(outputStream);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                fileBytes.reset();
                this.connectedSocket.close();
                break;
            } else {
                if (len == this.recvBufferSize) {
                    // Extracting timestamp
                    String recvTime = String.valueOf(System.currentTimeMillis());
                    byte[] sendingTimestamp = Arrays.copyOfRange(chunk, 0, recvTime.getBytes().length);
                    byte[] videoBytes = Arrays.copyOfRange(chunk, recvTime.getBytes().length, chunk.length);
                    String sendingTime = new String(sendingTimestamp);
                    try {
                        String s = "Network Lag: " + (Long.parseLong(recvTime) - Long.parseLong(sendingTime)) + "\n";
                        System.out.println(s);
                        Files.write(Paths.get("SinglePath.txt"), s.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } catch (NumberFormatException e){
                        ;
                    }

                    fileBytes.write(videoBytes);
                } else {
                    fileBytes.write(chunk);
                }
            }
        }
    }
}

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        // Clears old files
        Process p = Runtime.getRuntime().exec("bash clear.sh");
        p.waitFor();

        int portToConnect = 6000;
        String serverIP = "192.168.31.207";

        Client client = new Client(serverIP, portToConnect);
        client.receiveFile();
    }
}
