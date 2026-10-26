import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class ChatClient {
    private static final int DEFAULT_PORT = 5050;
    private static final int MAX_NAME_LENGTH = 24;
    private static final int MAX_MESSAGE_LENGTH = 500;

    public static void main(String[] args) throws IOException, InterruptedException {
        boolean plain = args.length > 0 && args[0].equals("--plain");
        int hostIndex = plain ? 1 : 0;
        String host = args.length > hostIndex ? args[hostIndex] : "localhost";
        int port = args.length > hostIndex + 1 ? Integer.parseInt(args[hostIndex + 1]) : DEFAULT_PORT;

        BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.out.print("Name: ");
        String name = console.readLine();
        if (name == null || name.strip().isEmpty() || name.strip().length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Name must be 1-" + MAX_NAME_LENGTH + " characters.");
        }

        Socket connection;
        if (plain) {
            connection = new Socket(host, port);
        } else {
            SSLSocket secureSocket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
            secureSocket.setEnabledProtocols(TlsProtocols.supportedBy(secureSocket.getSupportedProtocols()));
            SSLParameters parameters = secureSocket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            secureSocket.setSSLParameters(parameters);
            secureSocket.startHandshake();
            System.out.println("Secure connection established using "
                    + secureSocket.getSession().getProtocol() + ".");
            connection = secureSocket;
        }

        try (Socket socket = connection;
             DataInputStream input = new DataInputStream(socket.getInputStream());
             DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
            output.writeUTF(name.strip());
            output.flush();
            System.out.println("Connected. Type /quit to leave.");

            Thread receiver = new Thread(() -> receiveMessages(input), "chat-receiver");
            receiver.setDaemon(true);
            receiver.start();

            String message;
            while ((message = console.readLine()) != null) {
                if (message.length() > MAX_MESSAGE_LENGTH) {
                    System.out.println("Message is limited to " + MAX_MESSAGE_LENGTH + " characters.");
                    continue;
                }
                output.writeUTF(message);
                output.flush();
                if (message.strip().equalsIgnoreCase("/quit")) {
                    break;
                }
            }
            socket.shutdownOutput();
            receiver.join(500);
        }
    }

    private static void receiveMessages(DataInputStream input) {
        try {
            while (true) {
                System.out.println(input.readUTF());
            }
        } catch (IOException exception) {
            System.out.println("Disconnected from chat server.");
        }
    }
}