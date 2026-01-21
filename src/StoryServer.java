import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class StoryServer {
    private static final int PORT = 8000;
    private static Map<String, String> stories = new ConcurrentHashMap<>();
    private static Map<String, LinkedBlockingQueue<ClientHandler>> storyQueues = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println(" Story Server running on port " + PORT);
            ExecutorService pool = Executors.newCachedThreadPool();

            while (true) {
                Socket clientSocket = serverSocket.accept();
                pool.execute(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    static class ClientHandler implements Runnable {
        private final Socket socket;
        private DataInputStream in;
        private DataOutputStream out;
        private String username;
        private String currentStory;
        private boolean active = true;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());

                // Get username
                out.writeUTF("Welcome to Collaborative Story Writing!\nEnter your username:");
                username = in.readUTF();
                System.out.println(username + " connected");

                try {
                    mainMenu();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

            } catch (IOException e) {
                System.err.println("Client error: " + username + " - " + e.getMessage());
            } finally {
                disconnect();
            }
        }

        private void mainMenu() throws IOException, InterruptedException {
            while (active) {
                out.writeUTF("\nMAIN MENU\n1. List stories\n2. Create story\n3. Join story\n4. Exit\nChoose:");
                String choice = in.readUTF();

                switch (choice) {
                    case "1": listStories(); break;
                    case "2": createStory(); break;
                    case "3": joinStory(); break;
                    case "4":
                        out.writeUTF("Goodbye!");
                        active = false;
                        break;
                    default:
                        out.writeUTF("Invalid choice");
                }
            }
        }

        private void listStories() throws IOException {
            if (stories.isEmpty()) {
                out.writeUTF("No stories available yet");
            } else {
                StringBuilder sb = new StringBuilder("Available Stories:\n");
                for (String title : stories.keySet()) {
                    sb.append("- ").append(title).append("\n");
                }
                out.writeUTF(sb.toString());
            }
        }

        private void createStory() throws IOException, InterruptedException {
            out.writeUTF("Enter new story title:");
            String title = in.readUTF();

            if (stories.containsKey(title)) {
                out.writeUTF("Story already exists!");
                return;
            }

            stories.put(title, "");
            storyQueues.put(title, new LinkedBlockingQueue<>());
            out.writeUTF("Story '" + title + "' created!");
            joinStory(title);
        }

        private void joinStory() throws IOException, InterruptedException {
            if (stories.isEmpty()) {
                out.writeUTF("No stories available to join");
                return;
            }

            out.writeUTF("Enter story title to join:");
            String title = in.readUTF();

            if (!stories.containsKey(title)) {
                out.writeUTF("Story not found");
                return;
            }

            joinStory(title);
        }

        private void joinStory(String title) throws IOException, InterruptedException {
            currentStory = title;
            LinkedBlockingQueue<ClientHandler> queue = storyQueues.get(title);

            // Add client to the queue
            queue.put(this);
            out.writeUTF("\nYou joined: " + title + "\nCurrent story:\n" + stories.get(title));

            boolean waitingNotified = false; // Track if we've already told the user

            // Main story participation loop
            while (active && currentStory != null) {
                if (queue.peek() != this) {
                    if (!waitingNotified) {
                        int position = new ArrayList<>(queue).indexOf(this);
                        out.writeUTF("Waiting your turn... " + position + " user(s) ahead");
                        waitingNotified = true; // Only notify once
                    }
                    Thread.sleep(1000);
                    continue;
                }

                // It's your turn
                waitingNotified = false; // Reset for next round
                promptForWord();
                String input = in.readUTF();

                if ("exit".equalsIgnoreCase(input)) {
                    leaveStory();
                    return;
                }

                // Update story
                synchronized (stories) {
                    stories.put(title, stories.get(title) + input + " ");
                }

                System.out.println(username + " added to '" + title + "': " + input);
                broadcastUpdate(title, input);

                // Move to next turn
                queue.poll(); // Remove self
                queue.put(this); // Put back at end of queue
            }
        }


        private synchronized void promptForWord() throws IOException {
            out.writeUTF("\nYOUR TURN TO WRITE!\nCurrent story:\n" +
                    stories.get(currentStory) +
                    "\nEnter your word (or 'exit' to leave):");
        }

        private synchronized void broadcastUpdate(String title, String newWord) throws IOException {
            String message = "\nSTORY UPDATE:\n" + username + " added: " + newWord +
                    "\nCurrent story:\n" + stories.get(title);

            for (ClientHandler client : storyQueues.get(title)) {
                if (client != this) {
                    client.out.writeUTF(message);
                }
            }
        }

        private void leaveStory() {
            if (currentStory != null) {
                LinkedBlockingQueue<ClientHandler> queue = storyQueues.get(currentStory);
                try {
                    queue.remove(this);
                    System.out.println(username + " left story: " + currentStory);

                    // If leaving during turn, pass to next user
                    if (!queue.isEmpty() && queue.peek() == this) {
                        queue.poll();
                        if (!queue.isEmpty()) {
                            queue.peek().promptForWord();
                        }
                    }

                    currentStory = null;
                } catch (Exception e) {
                    System.err.println("Error leaving story: " + e.getMessage());
                }
            }
        }

        private void disconnect() {
            active = false;
            leaveStory();
            try {
                if (socket != null) socket.close();
            } catch (IOException e) {
                System.err.println("Error closing socket: " + e.getMessage());
            }
        }
    }
}