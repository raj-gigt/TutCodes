import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

class Server{
    private ServerSocket serverSocket;
    private Socket connectedSocket;
    int sendBufferSize = 1435;
    private String videoFileName = "video/CN_Lect_NPTEL.zip";

    public Server(int portToListenOn) throws IOException {
        this.serverSocket = new ServerSocket(portToListenOn);
        System.out.println("Listening on port: " + portToListenOn);
        this.connectedSocket = serverSocket.accept();
        System.out.println("Connected to: " + connectedSocket.getInetAddress());
    }

    public byte[] readFile(String filename) throws IOException {
        /*
        This function reads the whole video file,
        and returns the content as a byte array.
        */
        File file = new File(filename);
        if (file.exists()) {
            FileInputStream fl = new FileInputStream(file);
            byte[] arr = new byte[(int) file.length()];
            int res = fl.read(arr);
            if (res < 0) {
                System.out.println("Error in reading file");
                fl.close();
                return null;
            }
            fl.close();
            return arr;
        } else {
            return null;
        }
    }

    public void sendFile() throws IOException {
        /*
        Sends the video file to the client
        */
        byte[] fileBytes = readFile(this.videoFileName);
        int step = this.sendBufferSize;
        int sentSliceSize = 0;
        boolean isBreak = false;
        ByteBuffer byteBuffer;

        // Reading bytes from the read file and sending it over TCP socket
        while (!isBreak) {
            byte[] slice;
            // Bifurcating the whole file content into chunks
            if (sentSliceSize + this.sendBufferSize >= fileBytes.length) { // last chunk
                slice = Arrays.copyOfRange(fileBytes, sentSliceSize, fileBytes.length);
                isBreak = true;
            } else {
                slice = Arrays.copyOfRange(fileBytes, sentSliceSize, step);
                sentSliceSize = step;
                step += this.sendBufferSize;
            }

            // Attaching timestamp to the chunks
            byte[] currentTime = String.valueOf(System.currentTimeMillis()).getBytes();

            byteBuffer = ByteBuffer.allocate(sendBufferSize + currentTime.length);
            byteBuffer.put(currentTime);
            byteBuffer.put(slice);
            byteBuffer.flip();

            try {
                connectedSocket.getOutputStream().write(byteBuffer.array());
                System.out.println("Sent " + byteBuffer.capacity() + " Bytes");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        connectedSocket.close();
        serverSocket.close();
    }
}

public class Main {
    public static void main(String[] args) throws IOException {
        int portToListenOn = 6000;
        Server server = new Server(portToListenOn);

        server.sendFile();
    }
}