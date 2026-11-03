package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Deterministic overlapping transactions at descending cursor acquisition and continuation.
 * The oracle uses fixture entries, never another index scan. Both engine types use the same checks.
 */
// Parameterized children share the database path supplied by the base fixture.
@Category(SequentialTest.class)
@RunWith(Parameterized.class)
public class SnapshotIsolationIndexesDescendingTest extends SnapshotIsolationIndexesTestBase {

  @Parameterized.Parameters(name = "{0}")
  public static Object[] indexTypes() {
    return new Object[] {INDEX_TYPE.NOTUNIQUE, INDEX_TYPE.UNIQUE};
  }

  @Parameterized.Parameter
  public INDEX_TYPE indexType;

  private static final String INDEX = "DescendingEntries_key";
  private static final int BATCH_SIZE = 10;
  private final List<RawPair<Object, RID>> visible = new ArrayList<>();

  @Override
  @Before
  public void setUp() {
    var storageType = "ci".equals(System.getProperty("youtrackdb.test.env"))
        ? DatabaseType.DISK : DatabaseType.MEMORY;
    youTrackDB = DbTestBase.createYTDBManagerAndDb("test", storageType, getClass());
    db = openSession();
    createFixture();
  }

  @Override
  @After
  public void tearDown() {
    db.close();
    try {
      youTrackDB.drop("test");
    } finally {
      youTrackDB.close();
    }
  }

  private void createFixture() {
    // BTree captures this setting in a static final field. Do not change it for these tests.
    assertEquals(BATCH_SIZE, GlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger());
    var schema = db.createVertexClass("DescendingEntries");
    schema.createProperty("key", PropertyType.INTEGER);
    schema.createIndex(INDEX, indexType, "key");
    db.begin();
    // Three full batches and a partial batch. Duplicate non-null keys exercise RID tie-breaking.
    for (int i = 0; i < 3 * BATCH_SIZE + 3; i++) {
      var record = db.newVertex("DescendingEntries");
      int key = indexType == INDEX_TYPE.UNIQUE ? i : i / 3;
      record.setProperty("key", key);
      visible.add(new RawPair<>(key, record.getIdentity()));
    }
    var nullRecord = db.newVertex("DescendingEntries");
    visible.add(new RawPair<>(null, nullRecord.getIdentity()));
    db.commit();
  }

  @Test
  public void emptyAndNullOnlyIndexesHaveNoDescendingNonNullEntries() {
    var schema = db.createVertexClass("EmptyDescending");
    schema.createProperty("key", PropertyType.INTEGER);
    schema.createIndex("EmptyDescending_key", indexType, "key");
    db.begin();
    try (var stream = db.getIndex("EmptyDescending_key").descStream(db)) {
      assertTrue(stream.toList().isEmpty());
    }
    db.newVertex("EmptyDescending");
    db.commit();
    db.begin();
    try (var stream = db.getIndex("EmptyDescending_key").descStream(db)) {
      assertTrue(stream.toList().isEmpty());
    }
    db.commit();
  }

  @Test
  public void highestEntryRemovedAfterAcquisitionRemainsVisibleExactlyOnce() {
    // A tombstone replaces the highest physical key with a higher-version key before first pull.
    var highest = ordered(visible).getFirst();
    try (var reader = openSession()) {
      reader.begin();
      try (var stream = reader.getIndex(INDEX).descStream(reader)) {
        remove(highest);
        assertSequence(visible, stream.toList());
      }
      assertFresh(without(highest));
      reader.commit();
    }
  }

  @Test
  public void exactBatchLastEntryRepeatedlyRemovedAndReinsertedDoesNotRepeatOnContinuation() {
    try (var reader = openSession()) {
      reader.begin();
      var expected = ordered(visible);
      try (var stream = reader.getIndex(INDEX).descStream(reader)) {
        var iterator = stream.iterator();
        var actual = new ArrayList<RawPair<Object, RID>>();
        // Each physical entry is visible, so ten pulls exhaust exactly the first storage batch.
        for (int i = 0; i < BATCH_SIZE; i++) {
          assertTrue(iterator.hasNext());
          actual.add(iterator.next());
        }
        assertEquals(expected.subList(0, BATCH_SIZE), actual);
        var boundary = expected.get(BATCH_SIZE - 1);
        for (int i = 0; i < 3; i++) {
          remove(boundary);
          put(boundary);
        }
        iterator.forEachRemaining(actual::add);
        assertSequence(visible, actual);
      }
      assertFresh(visible);
      reader.commit();
    }
  }

  @Test
  public void committedInsertDeleteAndKeyMovesIncludingNullPreserveEstablishedSnapshot() {
    try (var reader = openSession()) {
      reader.begin();
      try (var stream = reader.getIndex(INDEX).descStream(reader)) {
        var descending = ordered(visible);
        var deleted = descending.get(4);
        var moved = descending.get(8);
        var toNull = descending.get(12);
        var fromNull = visible.getLast();
        var movedEntry = new RawPair<Object, RID>(100, moved.second());
        var fromNullEntry = new RawPair<Object, RID>(101, fromNull.second());
        var toNullEntry = new RawPair<Object, RID>(null, toNull.second());
        RawPair<Object, RID> inserted;
        try (var writer = openSession()) {
          writer.begin();
          var index = writer.getIndex(INDEX);
          for (var entry : List.of(deleted, moved, toNull, fromNull)) {
            index.remove(writer.getActiveTransaction(), entry.first(), entry.second());
          }
          // Free the unique null slot before moving another entry into it.
          for (var entry : List.of(movedEntry, fromNullEntry, toNullEntry)) {
            index.put(writer.getActiveTransaction(), entry.first(), entry.second());
          }
          var record = writer.newVertex("DescendingEntries");
          record.setProperty("key", 102);
          writer.commit();
          inserted = new RawPair<>(102, record.getIdentity());
        }
        assertSequence(visible, stream.toList());
        var current = new ArrayList<>(visible);
        current.removeAll(List.of(deleted, moved, toNull, fromNull));
        current.addAll(List.of(movedEntry, fromNullEntry, toNullEntry, inserted));
        assertFresh(current);
      }
      reader.commit();
    }
  }

  @Test
  public void uncommittedWriterAndRollbackDoNotChangeEstablishedOrFreshReader() {
    var highest = ordered(visible).getFirst();
    try (var reader = openSession(); var writer = openSession()) {
      reader.begin();
      try (var stream = reader.getIndex(INDEX).descStream(reader)) {
        writer.begin();
        writer.getIndex(INDEX).remove(
            writer.getActiveTransaction(), highest.first(), highest.second());
        var inserted = writer.newVertex("DescendingEntries");
        inserted.setProperty("key", 200);
        // The writer transaction remains open while both readers consume the index.
        assertSequence(visible, stream.toList());
        assertFresh(visible);
        writer.rollback();
      }
      try (var stream = reader.getIndex(INDEX).descStream(reader)) {
        assertSequence(visible, stream.toList());
      }
      assertFresh(visible);
      reader.commit();
    }
  }

  private DatabaseSessionEmbedded openSession() {
    return youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  private void remove(RawPair<Object, RID> entry) {
    try (var writer = openSession()) {
      writer.begin();
      assertTrue(writer.getIndex(INDEX).remove(
          writer.getActiveTransaction(), entry.first(), entry.second()));
      writer.commit();
    }
  }

  private void put(RawPair<Object, RID> entry) {
    try (var writer = openSession()) {
      writer.begin();
      writer.getIndex(INDEX).put(writer.getActiveTransaction(), entry.first(), entry.second());
      writer.commit();
    }
  }

  private List<RawPair<Object, RID>> without(RawPair<Object, RID> entry) {
    var result = new ArrayList<>(visible);
    result.remove(entry);
    return result;
  }

  private void assertFresh(List<RawPair<Object, RID>> expected) {
    try (var fresh = openSession()) {
      fresh.begin();
      try (var stream = fresh.getIndex(INDEX).descStream(fresh)) {
        assertSequence(expected, stream.toList());
      }
      fresh.commit();
    }
  }

  private static List<RawPair<Object, RID>> ordered(List<RawPair<Object, RID>> entries) {
    Comparator<RawPair<Object, RID>> comparator = Comparator
        .comparingInt((RawPair<Object, RID> entry) -> (Integer) entry.first())
        .thenComparing(RawPair::second);
    return entries.stream().filter(entry -> entry.first() != null)
        .sorted(comparator.reversed()).toList();
  }

  private static void assertSequence(List<RawPair<Object, RID>> expected,
      List<RawPair<Object, RID>> actual) {
    // RID.compareTo uses numeric collection ID and position, not printed RID strings.
    var expectedRids = ordered(expected).stream().map(RawPair::second).toList();
    var actualRids = actual.stream().map(RawPair::second).toList();
    assertEquals("Complete ordered snapshot RID sequence", expectedRids, actualRids);
    assertEquals("Every RID occurs exactly once", actualRids.size(),
        new HashSet<>(actualRids).size());
    assertEquals("Keys and RID tie-break must both match", ordered(expected), actual);
  }
}
