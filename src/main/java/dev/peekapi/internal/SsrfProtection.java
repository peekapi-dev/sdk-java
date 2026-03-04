package dev.peekapi.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Validates that endpoint hosts are not private/internal IPs (SSRF protection). */
public final class SsrfProtection {

    private SsrfProtection() {}

    /**
     * Returns true if the given host is a localhost address (allowed for local development).
     *
     * @param host the hostname to check
     * @return true if localhost
     */
    public static boolean isLocalhost(String host) {
        if (host == null) return false;
        String lower = host.toLowerCase();
        return lower.equals("localhost") || lower.equals("127.0.0.1") || lower.equals("::1");
    }

    /**
     * Validates that the given host does not resolve to a private IP address. Throws
     * IllegalArgumentException if the host is a private IP (SSRF risk). Localhost is always allowed.
     *
     * @param host the hostname to validate
     * @throws IllegalArgumentException if the host is a private/internal IP
     */
    public static void validateHost(String host) {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Endpoint host cannot be empty");
        }

        if (isLocalhost(host)) {
            return; // Localhost always allowed for local development
        }

        // Try to resolve and check if it's a private IP
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (isPrivateAddress(addr)) {
                    throw new IllegalArgumentException(
                            "Endpoint resolves to private IP "
                                    + addr.getHostAddress()
                                    + " (SSRF protection)");
                }
            }
        } catch (UnknownHostException e) {
            // If we can't resolve, let it through — the HTTP client will fail later
        }
    }

    /**
     * Checks if the given IP address is in a private/reserved range.
     *
     * @param addr the address to check
     * @return true if the address is private
     */
    public static boolean isPrivateAddress(InetAddress addr) {
        byte[] bytes = addr.getAddress();

        // IPv4 (4 bytes)
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;

            // 0.0.0.0/8 — current network
            if (b0 == 0) return true;
            // 10.0.0.0/8 — RFC 1918
            if (b0 == 10) return true;
            // 100.64.0.0/10 — CGNAT (RFC 6598)
            if (b0 == 100 && (b1 & 0xC0) == 64) return true;
            // 127.0.0.0/8 — loopback
            if (b0 == 127) return true;
            // 169.254.0.0/16 — link-local
            if (b0 == 169 && b1 == 254) return true;
            // 172.16.0.0/12 — RFC 1918
            if (b0 == 172 && (b1 & 0xF0) == 16) return true;
            // 192.168.0.0/16 — RFC 1918
            if (b0 == 192 && b1 == 168) return true;

            return false;
        }

        // IPv6 (16 bytes)
        if (bytes.length == 16) {
            // ::1 — loopback
            if (addr.isLoopbackAddress()) return true;
            // fc00::/7 — unique local address
            if ((bytes[0] & 0xFE) == 0xFC) return true;
            // fe80::/10 — link-local
            if ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xC0) == 0x80) return true;

            // ::ffff:x.x.x.x — IPv4-mapped IPv6: check the embedded IPv4
            boolean isV4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    isV4Mapped = false;
                    break;
                }
            }
            if (isV4Mapped && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF) {
                byte[] v4 = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
                try {
                    return isPrivateAddress(InetAddress.getByAddress(v4));
                } catch (UnknownHostException e) {
                    return false;
                }
            }

            return false;
        }

        return false;
    }
}
