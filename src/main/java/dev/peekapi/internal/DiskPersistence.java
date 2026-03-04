package dev.peekapi.internal;

import dev.peekapi.RequestEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles disk persistence of events in JSONL format. Events that fail to send are written to disk
 * and recovered on next startup.
 */
public final class DiskPersistence {

  private DiskPersistence() {}

  /**
   * Computes the default storage path for the given endpoint URL: {@code
   * <tmpdir>/peekapi-events-<hash>.jsonl}
   *
   * @param endpoint the ingest endpoint URL
   * @return the default file path
   */
  public static String defaultStoragePath(String endpoint) {
    // MD5 of endpoint, take first 8 hex chars
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
      byte[] digest = md.digest(endpoint.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (int i = 0; i < 4 && i < digest.length; i++) {
        hex.append(String.format("%02x", digest[i] & 0xFF));
      }
      String tmpDir = System.getProperty("java.io.tmpdir");
      return Paths.get(tmpDir, "peekapi-events-" + hex + ".jsonl").toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      String tmpDir = System.getProperty("java.io.tmpdir");
      return Paths.get(tmpDir, "peekapi-events.jsonl").toString();
    }
  }

  /**
   * Persists events to disk as a JSONL line (array of events per line).
   *
   * @param storagePath the file path to write to
   * @param events the events to persist
   * @param maxStorageBytes maximum file size allowed
   * @return true if events were written
   */
  public static boolean persistToDisk(
      String storagePath, List<RequestEvent> events, long maxStorageBytes) {
    if (events == null || events.isEmpty() || storagePath == null) {
      return false;
    }

    try {
      Path path = Paths.get(storagePath);

      // Check current file size
      if (Files.exists(path) && Files.size(path) >= maxStorageBytes) {
        return false; // File full
      }

      // Ensure parent directory exists
      Path parent = path.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }

      // Build JSONL line: JSON array of events
      StringBuilder line = new StringBuilder("[");
      for (int i = 0; i < events.size(); i++) {
        if (i > 0) line.append(",");
        line.append(events.get(i).toJson());
      }
      line.append("]\n");

      Files.writeString(
          path,
          line.toString(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Loads events from disk. Reads the JSONL file, parses events, and renames the file to {@code
   * .recovering} to prevent re-reading on crash.
   *
   * @param storagePath the file path to read from
   * @param maxEvents maximum number of events to load
   * @return list of recovered events (may be empty)
   */
  public static List<RequestEvent> loadFromDisk(String storagePath, int maxEvents) {
    List<RequestEvent> result = new ArrayList<>();
    if (storagePath == null) return result;

    Path path = Paths.get(storagePath);
    Path recoveringPath = Paths.get(storagePath + ".recovering");

    // Check for .recovering file first (leftover from previous crash)
    Path readPath = null;
    if (Files.exists(recoveringPath)) {
      readPath = recoveringPath;
    } else if (Files.exists(path)) {
      readPath = path;
    }

    if (readPath == null) return result;

    try (BufferedReader reader = Files.newBufferedReader(readPath, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null && result.size() < maxEvents) {
        line = line.trim();
        if (line.isEmpty()) continue;
        List<RequestEvent> events = parseJsonArray(line);
        for (RequestEvent event : events) {
          if (result.size() >= maxEvents) break;
          result.add(event);
        }
      }
    } catch (IOException e) {
      // Best effort — return what we have
    }

    // Rename to .recovering (or delete if we already read .recovering)
    try {
      if (readPath.equals(path) && Files.exists(path)) {
        Files.move(path, recoveringPath);
      }
    } catch (IOException e) {
      // Best effort
    }

    return result;
  }

  /**
   * Deletes the recovery file after a successful flush.
   *
   * @param storagePath the base storage path
   */
  public static void cleanupRecoveryFile(String storagePath) {
    if (storagePath == null) return;
    try {
      Files.deleteIfExists(Paths.get(storagePath + ".recovering"));
    } catch (IOException e) {
      // Best effort
    }
  }

  /**
   * Deletes both the main file and recovery file.
   *
   * @param storagePath the base storage path
   */
  public static void deleteAll(String storagePath) {
    if (storagePath == null) return;
    try {
      Files.deleteIfExists(Paths.get(storagePath));
      Files.deleteIfExists(Paths.get(storagePath + ".recovering"));
    } catch (IOException e) {
      // Best effort
    }
  }

  /** Parses a JSON array of RequestEvent objects from a string. Minimal parser. */
  public static List<RequestEvent> parseJsonArray(String json) {
    List<RequestEvent> events = new ArrayList<>();
    if (json == null || json.isEmpty()) return events;

    // Find the outer array brackets
    int start = json.indexOf('[');
    int end = json.lastIndexOf(']');
    if (start < 0 || end <= start) return events;

    // Split by top-level objects — find matching braces
    String inner = json.substring(start + 1, end);
    int depth = 0;
    int objStart = -1;
    for (int i = 0; i < inner.length(); i++) {
      char c = inner.charAt(i);
      if (c == '"') {
        // Skip string content
        i++;
        while (i < inner.length()) {
          if (inner.charAt(i) == '\\') {
            i++; // skip escaped char
          } else if (inner.charAt(i) == '"') {
            break;
          }
          i++;
        }
      } else if (c == '{') {
        if (depth == 0) objStart = i;
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0 && objStart >= 0) {
          String objJson = inner.substring(objStart, i + 1);
          try {
            events.add(RequestEvent.fromJson(objJson));
          } catch (Exception e) {
            // Skip malformed events
          }
          objStart = -1;
        }
      }
    }

    return events;
  }
}
