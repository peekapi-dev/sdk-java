package dev.peekapi;

import static org.junit.jupiter.api.Assertions.*;

import dev.peekapi.internal.DiskPersistence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskPersistenceTest {

  @TempDir Path tempDir;

  private String tmpStoragePath() {
    return tempDir.resolve("peekapi-test.jsonl").toString();
  }

  private RequestEvent testEvent(String method, String path) {
    return new RequestEvent(method, path, 200, 5.0, 0, 128, null, null);
  }

  // --- persistToDisk ---

  @Test
  void persistWritesJsonlLine() throws IOException {
    String storage = tmpStoragePath();
    List<RequestEvent> events = new ArrayList<>();
    events.add(testEvent("GET", "/users"));
    events.add(testEvent("POST", "/orders"));

    boolean result = DiskPersistence.persistToDisk(storage, events, 5_242_880);
    assertTrue(result);

    String content = Files.readString(Path.of(storage));
    assertTrue(content.endsWith("\n"));
    assertTrue(content.contains("/users"));
    assertTrue(content.contains("/orders"));
    assertTrue(content.startsWith("["));
  }

  @Test
  void persistAppendsToExistingFile() throws IOException {
    String storage = tmpStoragePath();
    List<RequestEvent> batch1 = List.of(testEvent("GET", "/first"));
    List<RequestEvent> batch2 = List.of(testEvent("GET", "/second"));

    DiskPersistence.persistToDisk(storage, batch1, 5_242_880);
    DiskPersistence.persistToDisk(storage, batch2, 5_242_880);

    String content = Files.readString(Path.of(storage));
    assertTrue(content.contains("/first"));
    assertTrue(content.contains("/second"));
    // Two lines
    assertEquals(2, content.split("\n").length);
  }

  @Test
  void persistRejectsWhenFileFull() throws IOException {
    String storage = tmpStoragePath();
    List<RequestEvent> events = List.of(testEvent("GET", "/test"));

    // Write until we exceed limit
    DiskPersistence.persistToDisk(storage, events, 5_242_880);
    long size = Files.size(Path.of(storage));

    // Now set max to current size — next write should be rejected
    boolean result = DiskPersistence.persistToDisk(storage, events, size);
    assertFalse(result);
  }

  @Test
  void persistReturnsfalseForEmptyEvents() {
    assertFalse(DiskPersistence.persistToDisk(tmpStoragePath(), new ArrayList<>(), 5_242_880));
    assertFalse(DiskPersistence.persistToDisk(tmpStoragePath(), null, 5_242_880));
  }

  @Test
  void persistReturnsFalseForNullPath() {
    assertFalse(DiskPersistence.persistToDisk(null, List.of(testEvent("GET", "/test")), 5_242_880));
  }

  // --- loadFromDisk ---

  @Test
  void loadRecoversPersistedEvents() {
    String storage = tmpStoragePath();
    List<RequestEvent> events = List.of(testEvent("GET", "/recovered"), testEvent("POST", "/also"));

    DiskPersistence.persistToDisk(storage, events, 5_242_880);
    List<RequestEvent> recovered = DiskPersistence.loadFromDisk(storage, 100);

    assertEquals(2, recovered.size());
    assertEquals("GET", recovered.get(0).getMethod());
    assertEquals("/recovered", recovered.get(0).getPath());
    assertEquals("POST", recovered.get(1).getMethod());
  }

  @Test
  void loadRespectsMaxEvents() {
    String storage = tmpStoragePath();
    List<RequestEvent> events = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      events.add(testEvent("GET", "/event-" + i));
    }

    DiskPersistence.persistToDisk(storage, events, 5_242_880);
    List<RequestEvent> recovered = DiskPersistence.loadFromDisk(storage, 3);

    assertEquals(3, recovered.size());
  }

  @Test
  void loadRenamesFileToRecovering() {
    String storage = tmpStoragePath();
    DiskPersistence.persistToDisk(storage, List.of(testEvent("GET", "/test")), 5_242_880);

    DiskPersistence.loadFromDisk(storage, 100);

    assertFalse(Files.exists(Path.of(storage)));
    assertTrue(Files.exists(Path.of(storage + ".recovering")));
  }

  @Test
  void loadReadsRecoveringFile() throws IOException {
    String storage = tmpStoragePath();
    // Simulate crash: write directly to .recovering
    Path recPath = Path.of(storage + ".recovering");
    List<RequestEvent> events = List.of(testEvent("GET", "/crashed"));
    StringBuilder line = new StringBuilder("[");
    for (int i = 0; i < events.size(); i++) {
      if (i > 0) line.append(",");
      line.append(events.get(i).toJson());
    }
    line.append("]\n");
    Files.writeString(recPath, line.toString());

    List<RequestEvent> recovered = DiskPersistence.loadFromDisk(storage, 100);
    assertEquals(1, recovered.size());
    assertEquals("/crashed", recovered.get(0).getPath());
  }

  @Test
  void loadReturnsEmptyForMissingFile() {
    List<RequestEvent> recovered = DiskPersistence.loadFromDisk(tmpStoragePath(), 100);
    assertTrue(recovered.isEmpty());
  }

  @Test
  void loadReturnsEmptyForNullPath() {
    assertTrue(DiskPersistence.loadFromDisk(null, 100).isEmpty());
  }

  // --- cleanupRecoveryFile ---

  @Test
  void cleanupDeletesRecoveryFile() throws IOException {
    String storage = tmpStoragePath();
    Path recPath = Path.of(storage + ".recovering");
    Files.writeString(recPath, "test data");

    DiskPersistence.cleanupRecoveryFile(storage);
    assertFalse(Files.exists(recPath));
  }

  @Test
  void cleanupHandlesNullPath() {
    assertDoesNotThrow(() -> DiskPersistence.cleanupRecoveryFile(null));
  }

  // --- deleteAll ---

  @Test
  void deleteAllRemovesBothFiles() throws IOException {
    String storage = tmpStoragePath();
    Files.writeString(Path.of(storage), "data");
    Files.writeString(Path.of(storage + ".recovering"), "data");

    DiskPersistence.deleteAll(storage);
    assertFalse(Files.exists(Path.of(storage)));
    assertFalse(Files.exists(Path.of(storage + ".recovering")));
  }

  // --- defaultStoragePath ---

  @Test
  void defaultStoragePathContainsTmpDir() {
    String path = DiskPersistence.defaultStoragePath("https://ingest.peekapi.dev/v1/events");
    assertNotNull(path);
    assertTrue(path.contains("peekapi-events-"));
    assertTrue(path.endsWith(".jsonl"));
  }

  @Test
  void defaultStoragePathDiffersPerEndpoint() {
    String path1 = DiskPersistence.defaultStoragePath("https://a.example.com/ingest");
    String path2 = DiskPersistence.defaultStoragePath("https://b.example.com/ingest");
    assertNotEquals(path1, path2);
  }

  // --- parseJsonArray ---

  @Test
  void parseJsonArrayHandlesEmptyInput() {
    assertTrue(DiskPersistence.parseJsonArray("").isEmpty());
    assertTrue(DiskPersistence.parseJsonArray(null).isEmpty());
    assertTrue(DiskPersistence.parseJsonArray("[]").isEmpty());
  }

  @Test
  void parseJsonArrayParsesMultipleEvents() {
    String json =
        "[{\"method\":\"GET\",\"path\":\"/a\",\"status_code\":200,"
            + "\"response_time_ms\":1.0,\"request_size\":0,\"response_size\":0,"
            + "\"timestamp\":\"2024-01-01T00:00:00.000000Z\"},"
            + "{\"method\":\"POST\",\"path\":\"/b\",\"status_code\":201,"
            + "\"response_time_ms\":2.5,\"request_size\":10,\"response_size\":20,"
            + "\"consumer_id\":\"test\","
            + "\"timestamp\":\"2024-01-01T00:00:01.000000Z\"}]";

    List<RequestEvent> events = DiskPersistence.parseJsonArray(json);
    assertEquals(2, events.size());
    assertEquals("GET", events.get(0).getMethod());
    assertEquals("/a", events.get(0).getPath());
    assertEquals("POST", events.get(1).getMethod());
    assertEquals("test", events.get(1).getConsumerId());
  }
}
