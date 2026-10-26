import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.security.KeyStore;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

public final class ChatServerTest {
    public static void main(String[] args) throws Exception {
        verifyPlainChat();
        verifyTlsChat();
        System.out.println("PASS: multi-client broadcast, flood rate limit, and verified TLS chat.");
    }

    private static void verifyPlainChat() throws Exception {
        int port = availablePort();
        Process server = new ProcessBuilder(
            javaExecutable(), "-cp", "out", "ChatServer", "--plain", "--bind", "127.0.0.1",
            Integer.toString(port))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

        try (Socket alice = connect(port);
             DataInputStream aliceInput = new DataInputStream(alice.getInputStream());
             DataOutputStream aliceOutput = new DataOutputStream(alice.getOutputStream())) {
            alice.setSoTimeout(2000);
            aliceOutput.writeUTF("Alice");
            aliceOutput.flush();
            assertEquals("* Alice joined the chat", aliceInput.readUTF());

            try (Socket bob = connect(port);
                 DataInputStream bobInput = new DataInputStream(bob.getInputStream());
                 DataOutputStream bobOutput = new DataOutputStream(bob.getOutputStream())) {
                bob.setSoTimeout(2000);
                bobOutput.writeUTF("Bob");
                bobOutput.flush();
                assertEquals("* Bob joined the chat", aliceInput.readUTF());
                assertEquals("* Bob joined the chat", bobInput.readUTF());

                aliceOutput.writeUTF("hello everyone");
                aliceOutput.flush();
                assertEquals("Alice: hello everyone", aliceInput.readUTF());
                assertEquals("Alice: hello everyone", bobInput.readUTF());

                for (int messageNumber = 2; messageNumber <= 5; messageNumber++) {
                    String text = "message " + messageNumber;
                    aliceOutput.writeUTF(text);
                    aliceOutput.flush();
                    assertEquals("Alice: " + text, aliceInput.readUTF());
                    assertEquals("Alice: " + text, bobInput.readUTF());
                }

                bob.setSoTimeout(250);
                for (int warning = 1; warning <= 3; warning++) {
                    aliceOutput.writeUTF("flood attempt " + warning);
                    aliceOutput.flush();
                    assertEquals("Server: message rate exceeded; slow down.", aliceInput.readUTF());
                    if (warning < 3) {
                        try {
                            bobInput.readUTF();
                            throw new AssertionError("The over-limit message should not be broadcast.");
                        } catch (java.net.SocketTimeoutException expected) {
                        }
                    }
                }
                assertEquals("* Alice left the chat", bobInput.readUTF());
            }
        } finally {
            stop(server);
        }
    }

    private static void verifyTlsChat() throws Exception {
        Path directory = Files.createTempDirectory("zechat-tls-test");
        Path keyStorePath = directory.resolve("server.p12");
        Path certificatePath = directory.resolve("server.cer");
        Path trustStorePath = directory.resolve("client-trust.p12");
        String password = "zechat-test-only";
        Process server = null;

        try {
            runKeytool("-genkeypair", "-alias", "zechat", "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                    "-keystore", keyStorePath.toString(), "-storetype", "PKCS12", "-storepass", password,
                    "-keypass", password, "-noprompt");
            runKeytool("-exportcert", "-alias", "zechat", "-keystore", keyStorePath.toString(),
                    "-storetype", "PKCS12", "-storepass", password, "-file", certificatePath.toString());
            runKeytool("-importcert", "-alias", "zechat", "-file", certificatePath.toString(), "-keystore",
                    trustStorePath.toString(), "-storetype", "PKCS12", "-storepass", password, "-noprompt");

            int port = availablePort();
            server = new ProcessBuilder(javaExecutable(), "-Djavax.net.ssl.keyStore=" + keyStorePath,
                    "-Djavax.net.ssl.keyStorePassword=" + password, "-Djavax.net.ssl.keyStoreType=PKCS12",
                    "-cp", "out", "ChatServer", Integer.toString(port))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            waitForPort(port);

            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(trustStorePath)) {
                trustStore.load(input, password.toCharArray());
            }
            TrustManagerFactory trustManagers = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);

            try (SSLSocket client = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", port);
                 DataInputStream input = new DataInputStream(client.getInputStream());
                 DataOutputStream output = new DataOutputStream(client.getOutputStream())) {
                client.setSoTimeout(3000);
                SSLParameters parameters = client.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                client.setSSLParameters(parameters);
                client.startHandshake();
                String protocol = client.getSession().getProtocol();
                if (!protocol.equals("TLSv1.2") && !protocol.equals("TLSv1.3")) {
                    throw new AssertionError("Expected TLS 1.2 or newer, got " + protocol);
                }

                output.writeUTF("SecureUser");
                output.flush();
                assertEquals("* SecureUser joined the chat", input.readUTF());
                output.writeUTF("encrypted chat message");
                output.flush();
                assertEquals("SecureUser: encrypted chat message", input.readUTF());
            }
        } finally {
            if (server != null) {
                stop(server);
            }
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        }
    }

    private static void runKeytool(String... arguments) throws Exception {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "keytool.exe" : "keytool";
        String keytool = System.getProperty("java.home") + System.getProperty("file.separator") + "bin"
                + System.getProperty("file.separator") + executable;
        String[] command = new String[arguments.length + 1];
        command[0] = keytool;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("keytool timed out");
        }
        if (process.exitValue() != 0) {
            throw new IOException("keytool failed with exit code " + process.exitValue());
        }
    }

    private static void waitForPort(int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket probe = new Socket(InetAddress.getLoopbackAddress(), port)) {
                if (!probe.isConnected()) {
                    throw new IOException("Chat server connection was not established");
                }
                return;
            } catch (IOException exception) {
                Thread.sleep(50);
            }
        }
        throw new IOException("Chat server did not start listening");
    }

    private static void stop(Process server) throws InterruptedException {
        server.destroy();
        if (!server.waitFor(2, TimeUnit.SECONDS)) {
            server.destroyForcibly();
        }
    }

    private static int availablePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return System.getProperty("java.home") + System.getProperty("file.separator") + "bin"
                + System.getProperty("file.separator") + executable;
    }

    private static Socket connect(int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        IOException lastError = null;
        while (System.nanoTime() < deadline) {
            try {
                return new Socket(InetAddress.getLoopbackAddress(), port);
            } catch (IOException exception) {
                lastError = exception;
                Thread.sleep(50);
            }
        }
        throw new IOException("Chat server did not accept a connection", lastError);
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected '" + expected + "' but received '" + actual + "'.");
        }
    }
}