package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Independent fixture oracles for null cursor order, snapshot visibility, and continuation. */
@Category(SequentialTest.class)
@RunWith(Parameterized.class)
public class SnapshotIsolationIndexesDirectionalNullTest extends SnapshotIsolationIndexesTestBase {

  @Parameterized.Parameters(name = "{0}")
  public static Object[] indexTypes() {
    return new Object[] {INDEX_TYPE.NOTUNIQUE, INDEX_TYPE.UNIQUE};
  }

  @Parameterized.Parameter
  public INDEX_TYPE indexType;

  private static final String INDEX = "DirectionalNull_key";
  private final List<RID> visible = new ArrayList<>();
  private RID nonNull;

  @Override
  @Before
  public void setUp() {
    var type = "ci".equals(System.getProperty("youtrackdb.test.env"))
        ? DatabaseType.DISK : DatabaseType.MEMORY;
    youTrackDB = DbTestBase.createYTDBManagerAndDb("test", type, getClass());
    db = openSession();
    var schema = db.createVertexClass("DirectionalNull");
    schema.createProperty("key", PropertyType.INTEGER);
    schema.createIndex(INDEX, indexType, "key");
    assertEquals(10, GlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE.getValueAsInteger());
    db.begin();
    // Even at the default 64 KiB page size, 8192 RID/version entries span several leaf pages.
    // A UNIQUE index has only one null slot. Alternate missing and explicit null properties.
    int count = indexType == INDEX_TYPE.UNIQUE ? 1 : 8192;
    for (int i = 0; i < count; i++) {
      var record = db.newVertex("DirectionalNull");
      if (i % 2 == 0) {
        record.setProperty("key", null);
      }
      visible.add(record.getIdentity());
    }
    var record = db.newVertex("DirectionalNull");
    record.setProperty("key", 1);
    nonNull = record.getIdentity();
    db.commit();
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

  @Test
  public void bothDirectionsAndLegacyReadMatchNumericFixtureOrderAcrossPages() {
    db.begin();
    assertRead(db, visible, true);
    assertRead(db, visible, false);
    try (var legacy = db.getIndex(INDEX).getRids(db, null)) {
      assertEquals(ordered(visible, true), legacy.toList());
    }
    db.commit();
  }

  @Test
  public void legacyReadsIgnoreLocalNullRemovalAndPreserveNonNullContent() {
    db.begin();
    var index = db.getIndex(INDEX);
    index.remove(db.getActiveTransaction(), null, visible.getFirst());
    // Legacy ignore-TX reads must still expose the stored ascending group, not the overlay.
    try (var nulls = index.getRidsIgnoreTx(db, null);
        var nonNulls = index.getRidsIgnoreTx(db, 1);
        var ordinary = index.getRids(db, 1)) {
      assertEquals(ordered(visible, true), nulls.toList());
      assertEquals(List.of(nonNull), nonNulls.toList());
      assertEquals(List.of(nonNull), ordinary.toList());
    }
    var local = new ArrayList<>(visible);
    local.removeFirst();
    assertRead(db, local, true);
    assertRead(db, local, false);
    db.rollback();
  }

  @Test
  public void ownerBoundStorageLegacyOverloadDefaultsToAscendingAndKeepsNonNullLookup()
      throws Exception {
    db.begin();
    var storage = (AbstractStorage) db.getStorage();
    var owner = storage.resolveIndexEngineByOwner(db.getIndex(INDEX).getIdentity());
    var operation = db.getActiveTransaction().getAtomicOperation();
    // The overload without direction remains an ascending read of stored values.
    try (var nulls = storage.getIndexValues(owner.engineIdentifier(), owner.engineReference(),
        null, operation);
        var nonNulls = storage.getIndexValues(owner.engineIdentifier(), owner.engineReference(),
            1, operation)) {
      assertEquals(ordered(visible, true), nulls.toList());
      assertEquals(List.of(nonNull), nonNulls.toList());
    }
    db.commit();
  }

  @Test
  public void emptyNullGroupIsEmptyInBothDirections() {
    var schema = db.createVertexClass("EmptyNull");
    schema.createProperty("key", PropertyType.INTEGER);
    schema.createIndex("EmptyNull_key", indexType, "key");
    db.begin();
    for (boolean ascending : new boolean[] {true, false}) {
      try (var stream = db.getIndex("EmptyNull_key").getNullKeyRids(db, ascending)) {
        assertTrue(stream.toList().isEmpty());
      }
    }
    db.commit();
  }

  @Test
  public void highestNullReplacedAfterAcquisitionRemainsSnapshotVisible() {
    var highest = ordered(visible, false).getFirst();
    try (var reader = openSession()) {
      reader.begin();
      try (var stream = reader.getIndex(INDEX).getNullKeyRids(reader, false)) {
        remove(highest);
        assertEquals(ordered(visible, false), stream.toList());
      }
      var current = new ArrayList<>(visible);
      current.remove(highest);
      assertFresh(current);
      reader.commit();
    }
  }

  @Test
  public void exactBatchLastReplacementAndUnreadDeletionPreserveSnapshotAcrossPages() {
    // UNIQUE has no ten-entry null batch. Its singleton replacement is covered above.
    if (indexType == INDEX_TYPE.UNIQUE) {
      return;
    }
    try (var reader = openSession()) {
      reader.begin();
      var expected = ordered(visible, false);
      try (var stream = reader.getIndex(INDEX).getNullKeyRids(reader, false)) {
        var iterator = stream.iterator();
        var actual = new ArrayList<RID>();
        for (int i = 0; i < 10; i++) {
          actual.add(iterator.next());
        }
        assertEquals(expected.subList(0, 10), actual);
        var boundary = expected.get(9);
        for (int i = 0; i < 3; i++) {
          remove(boundary);
          put(boundary);
        }
        // Change an unread entry too, then continue across the remaining leaf pages.
        var unread = expected.get(15);
        remove(unread);
        iterator.forEachRemaining(actual::add);
        assertEquals(expected, actual);
        var current = new ArrayList<>(visible);
        current.remove(unread);
        assertFresh(current);
      }
      reader.commit();
    }
  }

  @Test
  public void committedInsertAndMovesBetweenNullAndNonNullKeepEstablishedSnapshot() {
    try (var reader = openSession()) {
      reader.begin();
      try (var stream = reader.getIndex(INDEX).getNullKeyRids(reader, false);
          var writer = openSession()) {
        writer.begin();
        var index = writer.getIndex(INDEX);
        var moved = visible.getFirst();
        index.remove(writer.getActiveTransaction(), null, moved);
        index.put(writer.getActiveTransaction(), 2, moved);
        index.remove(writer.getActiveTransaction(), 1, nonNull);
        index.put(writer.getActiveTransaction(), null, nonNull);
        writer.commit();
        assertEquals(ordered(visible, false), stream.toList());
        var current = new ArrayList<>(visible);
        current.remove(moved);
        current.add(nonNull);
        if (indexType == INDEX_TYPE.NOTUNIQUE) {
          writer.begin();
          var inserted = writer.newVertex("DirectionalNull");
          writer.commit();
          current.add(inserted.getIdentity());
        }
        assertFresh(current);
      }
      reader.commit();
    }
  }

  @Test
  public void bufferedWriterRollbackDoesNotAffectReaderAndLocalRemovalOverlayStillApplies() {
    try (var reader = openSession(); var writer = openSession()) {
      reader.begin();
      writer.begin();
      writer.getIndex(INDEX).remove(writer.getActiveTransaction(), null, visible.getFirst());
      assertRead(reader, visible, false);
      writer.rollback();
      assertFresh(visible);
      reader.getIndex(INDEX).remove(reader.getActiveTransaction(), null, visible.getFirst());
      var local = new ArrayList<>(visible);
      local.removeFirst();
      assertRead(reader, local, false);
      reader.rollback();
    }
  }

  private DatabaseSessionEmbedded openSession() {
    return youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  private void remove(RID rid) {
    try (var writer = openSession()) {
      writer.begin();
      assertTrue(writer.getIndex(INDEX).remove(writer.getActiveTransaction(), null, rid));
      writer.commit();
    }
  }

  private void put(RID rid) {
    try (var writer = openSession()) {
      writer.begin();
      writer.getIndex(INDEX).put(writer.getActiveTransaction(), null, rid);
      writer.commit();
    }
  }

  private void assertFresh(List<RID> expected) {
    try (var fresh = openSession()) {
      fresh.begin();
      assertRead(fresh, expected, false);
      fresh.commit();
    }
  }

  private static void assertRead(DatabaseSessionEmbedded session, List<RID> expected,
      boolean ascending) {
    try (var stream = session.getIndex(INDEX).getNullKeyRids(session, ascending)) {
      assertEquals(ordered(expected, ascending), stream.toList());
    }
  }

  private static List<RID> ordered(List<RID> entries, boolean ascending) {
    return entries.stream().sorted(ascending
        ? Comparator.naturalOrder() : Comparator.reverseOrder()).toList();
  }
}
