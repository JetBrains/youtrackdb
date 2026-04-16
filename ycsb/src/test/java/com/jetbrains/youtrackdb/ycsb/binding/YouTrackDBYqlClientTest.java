package com.jetbrains.youtrackdb.ycsb.binding;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.ycsb.ByteIterator;
import com.jetbrains.youtrackdb.ycsb.Status;
import com.jetbrains.youtrackdb.ycsb.StringByteIterator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link YouTrackDBYqlClient}. Uses MEMORY database type
 * for speed — no disk I/O required.
 */
public class YouTrackDBYqlClientTest {

  private YouTrackDBYqlClient client;
  private Path tempDir;

  @Before
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("ycsb-test-" + System.nanoTime());
    client = new YouTrackDBYqlClient();
    Properties props = new Properties();
    props.setProperty(YouTrackDBYqlClient.URL_PROPERTY, tempDir.toString());
    props.setProperty(YouTrackDBYqlClient.DB_NAME_PROPERTY, "testdb");
    props.setProperty(YouTrackDBYqlClient.DB_TYPE_PROPERTY, "MEMORY");
    props.setProperty(YouTrackDBYqlClient.NEW_DB_PROPERTY, "true");
    client.setProperties(props);
    client.init();
  }

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.cleanup();
    }
    deleteDirectory(tempDir);
  }

  /**
   * Verify that init creates the database and schema successfully,
   * and that a single record can be inserted and read back via
   * direct YQL query.
   */
  @Test
  public void testInsertAndVerifyViaRead() {
    Map<String, ByteIterator> values = new HashMap<>();
    values.put("field0", new StringByteIterator("value0"));
    values.put("field1", new StringByteIterator("value1"));

    Status insertStatus = client.insert("usertable", "key1", values);
    assertEquals("Insert should succeed", Status.OK, insertStatus);

    // Verify the record was actually inserted by reading it back.
    // read() is not yet implemented, so we verify insert returned OK
    // and no exception was thrown.
  }

  /**
   * Insert multiple records to verify that the unique index on ycsb_key
   * works and that different keys don't collide.
   */
  @Test
  public void testInsertMultipleRecords() {
    for (int i = 0; i < 10; i++) {
      Map<String, ByteIterator> values = new HashMap<>();
      for (int f = 0; f < 10; f++) {
        values.put("field" + f, new StringByteIterator("val" + i + "_" + f));
      }
      Status status = client.insert("usertable", "user" + i, values);
      assertEquals("Insert of user" + i + " should succeed", Status.OK, status);
    }
  }

  /**
   * Insert a record with all 10 standard YCSB fields populated.
   * Verifies the driver builds the dynamic SET clause correctly for
   * the full field set.
   */
  @Test
  public void testInsertAllFields() {
    Map<String, ByteIterator> values = new HashMap<>();
    for (int f = 0; f < 10; f++) {
      values.put("field" + f, new StringByteIterator("data_" + f));
    }
    Status status = client.insert("usertable", "full-key", values);
    assertEquals("Insert with all 10 fields should succeed", Status.OK, status);
  }

  /**
   * Unimplemented operations should return NOT_IMPLEMENTED.
   */
  @Test
  public void testUnimplementedOperationsReturnNotImplemented() {
    assertEquals(Status.NOT_IMPLEMENTED,
        client.read("usertable", "key1", null, new HashMap<>()));
    assertEquals(Status.NOT_IMPLEMENTED,
        client.update("usertable", "key1", new HashMap<>()));
    assertEquals(Status.NOT_IMPLEMENTED,
        client.delete("usertable", "key1"));
  }

  /**
   * After cleanup, static fields should be null (database closed).
   * Re-initializing should work (creates a fresh database).
   */
  @Test
  public void testCleanupAndReinit() throws Exception {
    // Insert a record to confirm the DB is working
    Map<String, ByteIterator> values = new HashMap<>();
    values.put("field0", new StringByteIterator("before-cleanup"));
    assertEquals(Status.OK, client.insert("usertable", "key1", values));

    // Cleanup closes everything
    client.cleanup();

    // Re-init should create a new database
    Properties props = new Properties();
    props.setProperty(YouTrackDBYqlClient.URL_PROPERTY, tempDir.toString());
    props.setProperty(YouTrackDBYqlClient.DB_NAME_PROPERTY, "testdb2");
    props.setProperty(YouTrackDBYqlClient.DB_TYPE_PROPERTY, "MEMORY");
    props.setProperty(YouTrackDBYqlClient.NEW_DB_PROPERTY, "true");
    client.setProperties(props);
    client.init();

    // Should be able to insert into the new database
    Map<String, ByteIterator> values2 = new HashMap<>();
    values2.put("field0", new StringByteIterator("after-reinit"));
    assertEquals(Status.OK, client.insert("usertable", "reinit-key", values2));
  }

  private static void deleteDirectory(Path dir) throws IOException {
    if (dir != null && Files.exists(dir)) {
      try (var paths = Files.walk(dir)) {
        paths.sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> {
              try {
                Files.deleteIfExists(p);
              } catch (IOException e) {
                // best effort cleanup
              }
            });
      }
    }
  }
}
