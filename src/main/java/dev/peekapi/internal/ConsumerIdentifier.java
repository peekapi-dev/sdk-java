package dev.peekapi.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Identifies API consumers from HTTP request headers. Uses x-api-key as-is, or SHA-256 hashes the
 * Authorization header.
 */
public final class ConsumerIdentifier {

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private ConsumerIdentifier() {}

  /**
   * Default consumer identification strategy.
   *
   * @param apiKeyHeader value of x-api-key header (may be null)
   * @param authHeader value of Authorization header (may be null)
   * @return consumer ID or null
   */
  public static String identify(String apiKeyHeader, String authHeader) {
    // Priority 1: x-api-key header used as-is
    if (apiKeyHeader != null && !apiKeyHeader.isEmpty()) {
      return apiKeyHeader;
    }
    // Priority 2: Authorization header hashed with SHA-256
    if (authHeader != null && !authHeader.isEmpty()) {
      return hashConsumerId(authHeader);
    }
    return null;
  }

  /**
   * Hashes a raw identifier string using SHA-256 and returns "hash_" + first 12 hex chars.
   *
   * @param raw the raw identifier to hash
   * @return "hash_" + first 12 hex chars of SHA-256 digest
   */
  public static String hashConsumerId(String raw) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
      // Take first 6 bytes = 12 hex chars
      StringBuilder sb = new StringBuilder("hash_");
      for (int i = 0; i < 6 && i < digest.length; i++) {
        sb.append(HEX[(digest[i] >> 4) & 0x0F]);
        sb.append(HEX[digest[i] & 0x0F]);
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed to be available in every JVM
      throw new AssertionError("SHA-256 not available", e);
    }
  }
}
