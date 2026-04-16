/**
 * Copyright (c) 2024 JetBrains s.r.o. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains.youtrackdb.ycsb.binding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.ycsb.ByteIterator;
import com.jetbrains.youtrackdb.ycsb.Status;
import com.jetbrains.youtrackdb.ycsb.StringByteIterator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
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
   * Insert a record with two fields and verify the data was actually
   * persisted by reading it back via a direct YQL query on the shared
   * traversal source.
   */
  @Test
  public void testInsertAndVerifyDataPersisted() {
    Map<String, ByteIterator> values = new HashMap<>();
    values.put("field0", new StringByteIterator("value0"));
    values.put("field1", new StringByteIterator("value1"));

    Status insertStatus = client.insert("usertable", "key1", values);
    assertEquals("Insert should succeed", Status.OK, insertStatus);

    // Read back via direct YQL to verify data was actually written
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<
        Map<String, Object>>) (List<?>) YouTrackDBYqlClient.getTraversalSource().computeInTx(tx -> {
          var g = (YTDBGraphTraversalSource) tx;
          return g.yql(
              "SELECT ycsb_key, field0, field1 FROM usertable WHERE ycsb_key = :key",
              "key", "key1").toList();
        });

    assertEquals("Exactly one record should be found", 1, results.size());
    assertEquals("value0", results.get(0).get("field0"));
    assertEquals("value1", results.get(0).get("field1"));
  }

  /**
   * Insert 10 records with distinct keys and all 10 fields each, then
   * verify the database contains exactly 10 records.
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

    // Verify the database actually contains 10 records
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> allRecords = (List<
        Map<String, Object>>) (List<?>) YouTrackDBYqlClient.getTraversalSource().computeInTx(tx -> {
          var g = (YTDBGraphTraversalSource) tx;
          return g.yql("SELECT count(*) as cnt FROM usertable").toList();
        });
    assertEquals("Database should contain exactly 10 records",
        10L, ((Number) allRecords.get(0).get("cnt")).longValue());
  }

  /**
   * Insert a record with all 10 standard YCSB fields and verify each
   * field value was stored correctly via direct YQL read-back.
   */
  @Test
  public void testInsertAllFieldsAndVerifyValues() {
    Map<String, ByteIterator> values = new HashMap<>();
    for (int f = 0; f < 10; f++) {
      values.put("field" + f, new StringByteIterator("data_" + f));
    }
    Status status = client.insert("usertable", "full-key", values);
    assertEquals("Insert with all 10 fields should succeed", Status.OK, status);

    // Read back and verify all field values using explicit projection
    // (SELECT FROM returns Vertex objects; SELECT <fields> returns Maps)
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<
        Map<String, Object>>) (List<?>) YouTrackDBYqlClient.getTraversalSource().computeInTx(tx -> {
          var g = (YTDBGraphTraversalSource) tx;
          return g.yql(
              "SELECT field0, field1, field2, field3, field4,"
                  + " field5, field6, field7, field8, field9"
                  + " FROM usertable WHERE ycsb_key = :key",
              "key", "full-key").toList();
        });

    assertEquals(1, results.size());
    Map<String, Object> record = results.get(0);
    for (int f = 0; f < 10; f++) {
      assertEquals("field" + f + " should have correct value",
          "data_" + f, record.get("field" + f));
    }
  }

  /**
   * Insert with an empty values map — the vertex is created with only the
   * ycsb_key property. This exercises the boundary where the dynamic SET
   * clause has zero field entries.
   */
  @Test
  public void testInsertEmptyValuesMap() {
    Map<String, ByteIterator> empty = new HashMap<>();
    Status status = client.insert("usertable", "empty-key", empty);
    assertEquals("Insert with empty values should succeed", Status.OK, status);

    // Verify the record exists with only the key
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<
        Map<String, Object>>) (List<?>) YouTrackDBYqlClient.getTraversalSource().computeInTx(tx -> {
          var g = (YTDBGraphTraversalSource) tx;
          return g.yql(
              "SELECT ycsb_key FROM usertable WHERE ycsb_key = :key",
              "key", "empty-key").toList();
        });
    assertEquals(1, results.size());
    assertEquals("empty-key", results.get(0).get("ycsb_key"));
  }

  /**
   * Insert a record then read it back via the driver's read() method with
   * fields=null (all fields). Verify all field values match.
   */
  @Test
  public void testReadAllFields() {
    Map<String, ByteIterator> values = new HashMap<>();
    for (int f = 0; f < 10; f++) {
      values.put("field" + f, new StringByteIterator("read_" + f));
    }
    assertEquals(Status.OK, client.insert("usertable", "rkey1", values));

    Map<String, ByteIterator> result = new HashMap<>();
    Status readStatus = client.read("usertable", "rkey1", null, result);
    assertEquals("Read should succeed", Status.OK, readStatus);

    // fields=null returns all properties including ycsb_key
    for (int f = 0; f < 10; f++) {
      assertEquals("read_" + f, result.get("field" + f).toString());
    }
    assertEquals("rkey1", result.get("ycsb_key").toString());
  }

  /**
   * Read with a specific field set — only the requested fields should
   * appear in the result map.
   */
  @Test
  public void testReadSpecificFields() {
    Map<String, ByteIterator> values = new HashMap<>();
    for (int f = 0; f < 10; f++) {
      values.put("field" + f, new StringByteIterator("sel_" + f));
    }
    assertEquals(Status.OK, client.insert("usertable", "rkey2", values));

    Set<String> fields = new HashSet<>();
    fields.add("field0");
    fields.add("field5");

    Map<String, ByteIterator> result = new HashMap<>();
    Status readStatus = client.read("usertable", "rkey2", fields, result);
    assertEquals(Status.OK, readStatus);
    assertEquals(2, result.size());
    assertEquals("sel_0", result.get("field0").toString());
    assertEquals("sel_5", result.get("field5").toString());
  }

  /**
   * Reading a non-existent key should return NOT_FOUND.
   */
  @Test
  public void testReadNonExistentKeyReturnsNotFound() {
    Map<String, ByteIterator> result = new HashMap<>();
    Status status = client.read("usertable", "no-such-key", null, result);
    assertEquals(Status.NOT_FOUND, status);
    assertEquals("Result map should be empty for NOT_FOUND", 0, result.size());
  }

  /**
   * Insert a record, update some fields, then read back and verify
   * the updated values while unchanged fields retain their original values.
   */
  @Test
  public void testUpdateAndReadBack() {
    // Insert with all 10 fields
    Map<String, ByteIterator> values = new HashMap<>();
    for (int f = 0; f < 10; f++) {
      values.put("field" + f, new StringByteIterator("orig_" + f));
    }
    assertEquals(Status.OK, client.insert("usertable", "ukey1", values));

    // Update only field0 and field1
    Map<String, ByteIterator> updates = new HashMap<>();
    updates.put("field0", new StringByteIterator("updated_0"));
    updates.put("field1", new StringByteIterator("updated_1"));
    Status updateStatus = client.update("usertable", "ukey1", updates);
    assertEquals("Update should succeed", Status.OK, updateStatus);

    // Read back all fields and verify
    Map<String, ByteIterator> result = new HashMap<>();
    assertEquals(Status.OK, client.read("usertable", "ukey1", null, result));
    assertEquals("updated_0", result.get("field0").toString());
    assertEquals("updated_1", result.get("field1").toString());
    // Unchanged fields should retain original values
    for (int f = 2; f < 10; f++) {
      assertEquals("orig_" + f, result.get("field" + f).toString());
    }
  }

  /**
   * Scan and delete are still unimplemented — verify they return
   * NOT_IMPLEMENTED.
   */
  @Test
  public void testUnimplementedOperationsReturnNotImplemented() {
    assertEquals(Status.NOT_IMPLEMENTED,
        client.scan("usertable", "key1", 10, null, new Vector<>()));
    assertEquals(Status.NOT_IMPLEMENTED,
        client.delete("usertable", "key1"));
  }

  /**
   * After cleanup, shared resources should be closed (static fields null).
   * Re-initializing with a new database name should create a fresh database
   * that accepts inserts.
   */
  @Test
  public void testCleanupAndReinit() throws Exception {
    // Insert a record to confirm the DB is working
    Map<String, ByteIterator> values = new HashMap<>();
    values.put("field0", new StringByteIterator("before-cleanup"));
    assertEquals(Status.OK, client.insert("usertable", "key1", values));

    // Cleanup closes everything — balances setUp()'s init()
    client.cleanup();

    // Verify shared resources are released
    assertNull("traversalSource should be null after last cleanup",
        YouTrackDBYqlClient.getTraversalSource());

    // Re-init with a different database name using a fresh client
    // so that tearDown()'s cleanup() is balanced with this init()
    client = new YouTrackDBYqlClient();
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
        paths.sorted(Comparator.reverseOrder())
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
