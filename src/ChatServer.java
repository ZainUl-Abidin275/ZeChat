import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ChatServer {
    private static final int DEFAULT_PORT = 5050;
    private static final int MAX_NAME_LENGTH = 24;
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final int MESSAGE_BURST_LIMIT = 5;
    private static final long MESSAGE_REFILL_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final int MAX_RATE_LIMIT_WARNINGS = 3;

    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private final Object broadcastLock = new Object();
    private final ExecutorService clientPool = Executors.newCachedThreadPool();

    private void start(int port, boolean plain, String bindHost) throws IOException {
        InetAddress bindAddress = InetAddress.getByName(bindHost);
        ServerSocket serverSocket;
        if (plain) {
            serverSocket = new ServerSocket(port, 50, bindAddress);
        } else {
            SSLServerSocket secureServer = (SSLServerSocket) SSLServerSocketFactory
                    .getDefault().createServerSocket(port, 50, bindAddress);
            secureServer.setEnabledProtocols(TlsProtocols.supportedBy(secureServer.getSupportedProtocols()));
            serverSocket = secureServer;
        }

        try (ServerSocket server = serverSocket) {
            System.out.println((plain ? "Plain" : "TLS") + " chat server listening on "
                    + server.getInetAddress().getHostAddress() + ":" + port);
            while (true) {
                Socket socket = server.accept();
                clientPool.execute(() -> handle(socket));
            }
        } finally {
            clientPool.shutdownNow();
        }
    }

    private void handle(Socket socket) {
        ClientHandler client = null;
        try (socket;
             DataInputStream input = new DataInputStream(socket.getInputStream());
             DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
            if (socket instanceof SSLSocket secureSocket) {
                secureSocket.startHandshake();
            }
            String name = input.readUTF().strip();
            if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
                output.writeUTF("Server: name must be 1-" + MAX_NAME_LENGTH + " characters.");
                return;
            }

            client = new ClientHandler(name, output);
            clients.add(client);
            System.out.println(name + " joined from " + socket.getRemoteSocketAddress());
            broadcast("* " + name + " joined the chat");

            int rateLimitWarnings = 0;
            while (true) {
                String message = input.readUTF().strip();
                if (message.equalsIgnoreCase("/quit")) {
                    break;
                }
                if (message.isEmpty()) {
                    continue;
                }
                if (message.length() > MAX_MESSAGE_LENGTH) {
                    client.send("Server: messages are limited to " + MAX_MESSAGE_LENGTH + " characters.");
                    continue;
                }
                if (!client.tryConsumeMessage()) {
                    client.send("Server: message rate exceeded; slow down.");
                    rateLimitWarnings++;
                    if (rateLimitWarnings >= MAX_RATE_LIMIT_WARNINGS) {
                        break;
                    }
                    continue;
                }
                rateLimitWarnings = 0;
                broadcast(name + ": " + message);
            }
        } catch (IOException exception) {
            if (client == null) {
                System.out.println("A client disconnected before joining.");
            }
        } finally {
            if (client != null && clients.remove(client)) {
                broadcast("* " + client.name + " left the chat");
                System.out.println(client.name + " disconnected.");
            }
        }
    }

    private void broadcast(String message) {
        synchronized (broadcastLock) {
            for (ClientHandler client : clients) {
                try {
                    client.send(message);
                } catch (IOException exception) {
                    clients.remove(client);
                }
            }
        }
    }

    private static final class ClientHandler {
        private final String name;
        private final DataOutputStream output;
        private double availableMessageTokens = MESSAGE_BURST_LIMIT;
        private long lastTokenUpdate = System.nanoTime();

        private ClientHandler(String name, DataOutputStream output) {
            this.name = name;
            this.output = output;
        }

        private synchronized void send(String message) throws IOException {
            output.writeUTF(message);
            output.flush();
        }

        private synchronized boolean tryConsumeMessage() {
            long now = System.nanoTime();
            long elapsed = now - lastTokenUpdate;
            availableMessageTokens = Math.min(MESSAGE_BURST_LIMIT,
                    availableMessageTokens + (double) elapsed / MESSAGE_REFILL_INTERVAL_NANOS);
            lastTokenUpdate = now;
            if (availableMessageTokens < 1) {
                return false;
            }
            availableMessageTokens -= 1;
            return true;
        }
    }

    public static void main(String[] args) throws IOException {
        boolean plain = false;
        boolean portProvided = false;
        String bindHost = "0.0.0.0";
        int port = DEFAULT_PORT;
        for (int index = 0; index < args.length; index++) {
            if (args[index].equals("--plain")) {
                plain = true;
            } else if (args[index].equals("--bind")) {
                if (++index >= args.length) {
                    throw new IllegalArgumentException("--bind requires an IP address or hostname.");
                }
                bindHost = args[index];
            } else if (!portProvided) {
                port = Integer.parseInt(args[index]);
                portProvided = true;
            } else {
                throw new IllegalArgumentException("Usage: ChatServer [--plain] [--bind address] [port]");
            }
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535.");
        }
        new ChatServer().start(port, plain, bindHost);
    }
}