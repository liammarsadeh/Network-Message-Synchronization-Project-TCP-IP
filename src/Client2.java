import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Client2 {
    public static void main(String[] args) {
        try (Socket socket = new Socket("localhost", 8000);
             DataInputStream in = new DataInputStream(socket.getInputStream());
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             Scanner scanner = new Scanner(System.in)) {

            // Thread to handle server messages
            new Thread(() -> {
                try {
                    while (true) {
                        System.out.println(in.readUTF());
                    }
                } catch (IOException e) {
                    System.out.println("Disconnected from server");
                }
            }).start();

            // Main input loop
            while (true) {
                String input = scanner.nextLine();
                out.writeUTF(input);
                if ("exit".equalsIgnoreCase(input)) break;
            }

        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }
}