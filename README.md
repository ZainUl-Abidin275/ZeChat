# ZeChat

ZeChat is a Java terminal group chat built with TCP sockets. It has a plaintext mode for the Level 1 baseline and a TLS mode for Level 2. TLS is the default; plaintext must be explicitly selected with `--plain`.

## Build and Test

Use a JDK (Java 11 or newer) and confirm `java`, `javac`, and `keytool` resolve to the same JDK. On the development machine used for this project, the JDK is installed at `C:\JAVA FILE\jdk-23.0.1`; set it first in PowerShell (adjust the path if your JDK is installed elsewhere):

```powershell
$env:JAVA_HOME = 'C:\JAVA FILE\jdk-23.0.1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
javac -version
javac -Xlint:all -d out src\*.java tests\*.java
java -cp out ChatServerTest
```

The integration test starts real server processes, verifies two plaintext clients receive broadcasts and an over-rate message is rejected, creates a short-lived test certificate, then checks a hostname-validated TLS connection and a message sent through it.

## Level 1: Basic Chat Functionality

### Design and implementation

The project has a terminal client and a server. Each accepted TCP connection is handled on its own worker thread. After sending a display name, each client can post messages; the server broadcasts each message to all currently connected clients. Messages are not stored for later users, matching the brief's requirement that users only need messages from while connected. The interface is text-based, as permitted by the brief.

`ChatServer` tracks connected clients in a concurrent set. Each handler reads its own input while `broadcast` sends a message to every active client. `ChatClient` runs a receiver thread so incoming broadcasts remain visible while the user types. The client can type `/quit` to disconnect. Names are limited to 24 characters and messages to 500 characters.

Key broadcast logic:

```java
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
```

### Run and test

Start the Level 1 server in one terminal:

```powershell
java -cp out ChatServer --plain 5050
```

Run the client in two other terminals and enter a different name in each:

```powershell
java -cp out ChatClient --plain localhost 5050
```

Send a message in either client and verify that both clients see the sender name and message. Automated verification is `java -cp out ChatServerTest`; it checks that two clients join and both receive a broadcast.

## Level 2: Secure Communication (SSL/TLS)

### Implementation

The server uses Java's `SSLServerSocket` and the client uses `SSLSocket`. Both restrict enabled protocols to TLS 1.2 and TLS 1.3. The client verifies the certificate chain using its truststore and enables endpoint identification, so the certificate must match the host used to connect. The TLS handshake occurs before the client sends its name or any chat data. Thus names, join notifications, and messages are encrypted in transit in the secure mode.

The default mode is TLS. The `--plain` option is retained only for the Level 1 baseline and a later plaintext-versus-encrypted Wireshark capture. A self-signed certificate is suitable only for a local demonstration; the client trusts it only after it is deliberately imported into a local truststore. TLS protects transport, not stored data, and does not provide end-to-end encryption or user account authentication.

### Configure and test TLS

Create a local certificate with a `localhost` subject alternative name. `keytool` prompts for a keystore password; use the same password when prompted for the key password:

```powershell
keytool -genkeypair -alias zechat -keyalg RSA -keysize 2048 -validity 365 -dname "CN=localhost" -ext "SAN=dns:localhost,ip:127.0.0.1" -keystore server.p12 -storetype PKCS12
keytool -exportcert -alias zechat -keystore server.p12 -storetype PKCS12 -rfc -file server.cer
keytool -importcert -alias zechat -file server.cer -keystore client-trust.p12 -storetype PKCS12
```

For the import confirmation, verify the displayed certificate is the one you just created before answering `yes`. Set the password as a local environment variable so it is not written into the command itself:

```powershell
$env:ZECHAT_STORE_PASSWORD = Read-Host "Keystore password"
java "-Djavax.net.ssl.keyStore=server.p12" "-Djavax.net.ssl.keyStorePassword=$env:ZECHAT_STORE_PASSWORD" -Djavax.net.ssl.keyStoreType=PKCS12 -cp out ChatServer 5050
```

In a second terminal, set the same environment variable and connect with the trusted certificate:

```powershell
$env:ZECHAT_STORE_PASSWORD = Read-Host "Truststore password"
java "-Djavax.net.ssl.trustStore=client-trust.p12" "-Djavax.net.ssl.trustStorePassword=$env:ZECHAT_STORE_PASSWORD" -Djavax.net.ssl.trustStoreType=PKCS12 -cp out ChatClient localhost 5050
```

Confirm the client reports a TLS 1.2 or TLS 1.3 connection, then send a message. Run `java -cp out ChatServerTest` to repeat the verified TLS handshake and message test automatically. For the later Wireshark exercise, run the plaintext and TLS modes on separate ports or capture each run separately; the plaintext payload should be readable while TLS application data should not expose the chat text.

## Level 3: Additional Security Control

### Threat and mitigation

The additional threat is message flooding by one connected client, which can consume server work and overwhelm the other participants' chat view. Each client handler now has its own token bucket: it permits a burst of five messages, then refills at one message per second. Messages over the limit are not broadcast and the sender receives a warning. Three consecutive over-limit attempts disconnect that client. The limit is enforced on the server, so bypassing the client interface does not bypass the control.

### Testing and effectiveness

The automated integration test sends five rapid messages from one client and confirms both connected clients receive them. It then sends a sixth rapid message and verifies the sender receives a rate-limit warning while the other client receives no flood message. Run `java -cp out ChatServerTest` to repeat this check together with the TLS tests.

This reduces the impact of a single abusive connection, but it does not prevent a distributed flood using many connections or protect against all resource-exhaustion attacks. The server has no account authentication or global connection cap, so those remain limitations for future work.

## Level 4: Cross-Machine Deployment

### Network setup

Run the TLS server on one laptop and a TLS client on the partner's laptop, both on the same trusted private network. Do not use `localhost` on the partner machine: it refers to that machine itself. On the server laptop, run `ipconfig` and record its active Wi-Fi/Ethernet IPv4 address. On both machines, check `Get-NetConnectionProfile`; use a trusted network with the profile set to `Private`. The server binds to all interfaces by default; `--bind` can limit it to the selected LAN address.

### Certificate for the server's LAN address

The TLS client checks that the server certificate names the address used to connect. Replace `192.168.1.20` below with the server laptop's actual IPv4 address. On the server machine, create a certificate whose Subject Alternative Name includes that IP:

```powershell
keytool -genkeypair -alias zechat -keyalg RSA -keysize 2048 -validity 365 -dname "CN=ZeChat server" -ext "SAN=ip:192.168.1.20" -keystore server.p12 -storetype PKCS12
keytool -exportcert -alias zechat -keystore server.p12 -storetype PKCS12 -rfc -file server.cer
```

Verify the exported certificate fingerprint with `keytool -printcert -file server.cer`, then transfer `server.cer` to the partner using a trusted channel. It contains the public certificate, not the private key; never transfer `server.p12` or share its password. On the partner machine, import that certificate into a client truststore and confirm the fingerprint matches:

```powershell
keytool -importcert -alias zechat-server -file server.cer -keystore client-trust.p12 -storetype PKCS12
```

### Restrict the firewall rule

On the server laptop, run PowerShell as Administrator. Replace `192.168.1.44` with the partner laptop's current private IPv4 address. This permits only inbound TCP port 5050 from that one address on the Private profile; the client needs no inbound rule:

```powershell
New-NetFirewallRule -DisplayName 'ZeChat TLS from partner' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5050 -RemoteAddress 192.168.1.44 -Profile Private
```

Do not create a broad all-ports rule or enable this rule for the Public profile. If the partner's address changes, update the `RemoteAddress`. Remove the rule after the demonstration if it is no longer needed:

```powershell
Remove-NetFirewallRule -DisplayName 'ZeChat TLS from partner'
```

### Start and verify the remote chat

On the server machine, compile the sources, set the keystore password for the current PowerShell session, and bind the server to its LAN IP:

```powershell
javac -Xlint:all -d out src\*.java
$env:ZECHAT_STORE_PASSWORD = Read-Host "Server keystore password"
java "-Djavax.net.ssl.keyStore=server.p12" "-Djavax.net.ssl.keyStorePassword=$env:ZECHAT_STORE_PASSWORD" -Djavax.net.ssl.keyStoreType=PKCS12 -cp out ChatServer --bind 192.168.1.20 5050
```

On the partner machine, compile the same Java sources and connect using the server IP and imported truststore:

```powershell
javac -Xlint:all -d out src\*.java
$env:ZECHAT_STORE_PASSWORD = Read-Host "Client truststore password"
Test-NetConnection 192.168.1.20 -Port 5050
java "-Djavax.net.ssl.trustStore=client-trust.p12" "-Djavax.net.ssl.trustStorePassword=$env:ZECHAT_STORE_PASSWORD" -Djavax.net.ssl.trustStoreType=PKCS12 -cp out ChatClient 192.168.1.20 5050
```

Confirm `TcpTestSucceeded` is `True`, then confirm the client reports TLS 1.2 or 1.3 and can exchange messages with a client running on the server laptop. Capture the server and partner IP addresses, the narrow firewall rule, successful `Test-NetConnection`, and a message exchange for the report/video. If TCP fails, check both machines are on the same private network, the server IP and port are correct, the server is listening, and the firewall rule's remote address matches the partner. If TCP succeeds but Java reports a certificate error, check the truststore and that the certificate SAN exactly matches the IP used in `ChatClient`.

The application and bind-address option are implemented and locally tested. A connection between two physically separate laptops, and the corresponding firewall change, must still be performed on your network; this workspace cannot verify that physical-machine requirement on its own.

## Coursework note

The assessment brief says AI-generated material must be critically revised, not submitted unchanged, and acknowledged in the report. Use these implementation and testing notes to understand the program, then write your own report explanation and include your group's actual observations and screenshots.