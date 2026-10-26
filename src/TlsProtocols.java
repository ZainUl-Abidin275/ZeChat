import java.util.Arrays;

public final class TlsProtocols {
    private TlsProtocols() {
    }

    public static String[] supportedBy(String[] protocols) {
        String[] secureProtocols = Arrays.stream(protocols)
                .filter(protocol -> protocol.equals("TLSv1.3") || protocol.equals("TLSv1.2"))
                .toArray(String[]::new);
        if (secureProtocols.length == 0) {
            throw new IllegalStateException("This Java runtime does not support TLS 1.2 or newer.");
        }
        return secureProtocols;
    }
}