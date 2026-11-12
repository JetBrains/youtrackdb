package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.comparator.ByteArrayComparator;
import com.jetbrains.youtrackdb.internal.common.comparator.ComparatorFactory;
import com.jetbrains.youtrackdb.internal.common.comparator.UnsafeByteArrayComparator;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.exception.TooBigIndexKeyException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(SequentialTest.class)
public class BigKeyIndexTest extends DbTestBase {

  private Index createIndex(boolean unique, PropertyType type) {
    return createIndex(unique, type, "One");
  }

  private Index createIndex(boolean unique, PropertyType type, String name) {
    var cl = session.createClass(name);
    cl.createProperty("two", type);
    cl.createIndex(name + ".two", unique ? INDEX_TYPE.UNIQUE : INDEX_TYPE.NOTUNIQUE, "two");
    return session.getSharedContext().getIndexManager().getIndex(name + ".two");
  }

  private int prefix(boolean unique, PropertyTypeInternal type) {
    return IndexKeyShortener.prefixLength(new PropertyTypeInternal[] {type}, unique,
        session.getStorage().getRecordedMaxIndexKeySize());
  }

  @Test
  public void oversizedStringsHaveTheSameKeyInWritesReadsRangesAndRemovals() {
    verifyKeyPaths(false, PropertyType.STRING);
  }

  @Test
  public void uniqueOversizedStringsUseTheSamePreparedKeyInTheTransactionAndStorage() {
    verifyKeyPaths(true, PropertyType.STRING);
  }

  @Test
  public void oversizedBinaryKeysUseTheSamePreparedKeyInTheTransactionAndStorage() {
    verifyKeyPaths(false, PropertyType.BINARY);
  }

  @Test
  public void uniqueBinaryKeysUseTheSamePreparedKeyInTheTransactionAndStorage() {
    verifyKeyPaths(true, PropertyType.BINARY);
  }

  private void verifyKeyPaths(boolean unique, PropertyType type) {
    var index = createIndex(unique, type);
    int p = prefix(unique, type == PropertyType.STRING
        ? PropertyTypeInternal.STRING : PropertyTypeInternal.BINARY);
    Object value = type == PropertyType.STRING ? "x".repeat(p + 100) : new byte[p + 100];
    Object prepared = index.getCollatingValue(value);
    session.begin();
    var doc = session.newInstance("One");
    doc.setProperty("two", value);
    var rid = doc.getIdentity();
    // Flush automatic index updates before checking the transaction view.
    session.getActiveTransaction().preProcessRecordsAndExecuteCallCallbacks();
    for (int phase = 0; phase < 2; phase++) {
      try (var rids = index.getRids(session, value)) {
        assertEquals(List.of(rid), rids.toList());
      }
      try (var entries = index.streamEntries(session, List.of(value, prepared), true)) {
        assertEquals(1, entries.count());
      }
      for (boolean asc : new boolean[] {true, false}) {
        try (var entries = index.streamEntriesBetween(session, value, false, value, true, asc)) {
          assertEquals(1, entries.count());
        }
        try (var entries = index.streamEntriesMajor(session, value, false, asc)) {
          assertEquals(1, entries.count());
        }
        try (var entries = index.streamEntriesMinor(session, value, true, asc)) {
          assertEquals(1, entries.count());
        }
        // Exclusive raw upper bounds must remain above the stored prefix.
        try (var entries = index.streamEntriesMinor(session, value, false, asc)) {
          assertEquals(List.of(rid), entries.map(e -> e.second()).toList());
        }
        try (var entries = index.streamEntriesBetween(session, value, false, value, false, asc)) {
          assertEquals(List.of(rid), entries.map(e -> e.second()).toList());
        }
      }
      if (phase == 0) {
        session.commit();
        session.begin();
      }
    }
    index.remove(session.getActiveTransaction(), value, rid);
    try (var rids = index.getRids(session, value)) {
      assertEquals(0, rids.count());
    }
    session.commit();
    session.begin();
    try (var rids = index.getRids(session, value)) {
      assertEquals(0, rids.count());
    }
    session.rollback();
  }

  @Test
  public void multibyteLowerBoundsKeepQualifyingUniqueKeysInBothViews() {
    verifyMultibyteLowerBounds(true);
  }

  @Test
  public void multibyteLowerBoundsKeepQualifyingNonuniqueKeysInBothViews() {
    verifyMultibyteLowerBounds(false);
  }

  private void verifyMultibyteLowerBounds(boolean unique) {
    for (String point : List.of("é", "€", "😀")) {
      String name = "Cut" + point.length() + point.codePointAt(0);
      var index = createIndex(unique, PropertyType.STRING, name);
      int p = prefix(unique, PropertyTypeInternal.STRING);
      String common = "a".repeat(p - 1);
      String value = common + point;
      String bound = common + "b";
      assertEquals(common, index.getCollatingValue(value));
      session.begin();
      var doc = session.newInstance(name);
      doc.setProperty("two", value);
      var rid = doc.getIdentity();
      session.getActiveTransaction().preProcessRecordsAndExecuteCallCallbacks();
      for (int phase = 0; phase < 2; phase++) {
        for (boolean asc : new boolean[] {true, false}) {
          try (var entries = index.streamEntriesMajor(session, bound, false, asc)) {
            assertEquals(List.of(rid), entries.map(e -> e.second()).toList());
          }
          try (var entries = index.streamEntriesBetween(session, bound, false, value, true, asc)) {
            assertEquals(List.of(rid), entries.map(e -> e.second()).toList());
          }
        }
        if (phase == 0) {
          session.commit();
          session.begin();
        }
      }
      session.rollback();
    }
  }

  @Test
  public void binaryLowerBoundsFollowBothComparatorsInUniqueAndNonuniqueIndexes() {
    boolean original = GlobalConfiguration.MEMORY_USE_UNSAFE.getValueAsBoolean();
    try {
      for (boolean unsafe : new boolean[] {false, true}) {
        GlobalConfiguration.MEMORY_USE_UNSAFE.setValue(unsafe);
        assertSame(unsafe ? UnsafeByteArrayComparator.INSTANCE : ByteArrayComparator.INSTANCE,
            ComparatorFactory.INSTANCE.getComparator(byte[].class));
        for (boolean unique : new boolean[] {false, true}) {
          String name = "Binary" + unsafe + unique;
          var index = createIndex(unique, PropertyType.BINARY, name);
          int p = prefix(unique, PropertyTypeInternal.BINARY);
          byte[] bound = new byte[p + 1];
          Arrays.fill(bound, (byte) 0xff);
          byte[] value = new byte[p + 2];
          if (unsafe) {
            Arrays.fill(value, (byte) 0xff);
          }
          assertTrue(ComparatorFactory.INSTANCE.getComparator(byte[].class)
              .compare(value, bound) > 0);
          var prepared = ((IndexAbstract) index).prepareLowerBound(bound, false);
          byte[] expected = new byte[p];
          if (unsafe) {
            Arrays.fill(expected, (byte) 0xff);
          }
          org.junit.Assert.assertArrayEquals(expected, (byte[]) prepared.key());
          assertTrue(prepared.inclusive());
          session.begin();
          var doc = session.newInstance(name);
          doc.setProperty("two", value);
          var rid = doc.getIdentity();
          session.getActiveTransaction().preProcessRecordsAndExecuteCallCallbacks();
          for (int phase = 0; phase < 2; phase++) {
            for (boolean asc : new boolean[] {true, false}) {
              try (var entries = index.streamEntriesMajor(session, bound, false, asc)) {
                assertEquals(List.of(rid), entries.map(e -> e.second()).toList());
              }
              try (var entries = index.streamEntriesBetween(
                  session, bound, false, value, true, asc)) {
                assertEquals(List.of(rid), entries.map(e -> e.second()).toList());
              }
            }
            if (phase == 0) {
              session.commit();
              session.begin();
            }
          }
          session.rollback();
        }
      }
    } finally {
      GlobalConfiguration.MEMORY_USE_UNSAFE.setValue(original);
    }
  }

  @Test
  public void uniquePrefixesRejectDistinctOversizedValues() {
    var index = createIndex(true, PropertyType.STRING);
    String common = "x".repeat(prefix(true, PropertyTypeInternal.STRING));
    session.begin();
    session.newInstance("One").setProperty("two", common + "a");
    session.commit();
    session.begin();
    session.newInstance("One").setProperty("two", common + "b");
    assertThrows(RecordDuplicatedException.class, () -> session.commit());
  }

  @Test
  public void exactBoundaryValuesStayFullAndMultibyteLowerBoundsRemainInclusive() {
    var index = createIndex(false, PropertyType.STRING);
    int p = prefix(false, PropertyTypeInternal.STRING);
    for (int gap = 0; gap <= 3; gap++) {
      String shortValue = "a".repeat(p - gap);
      assertSame(shortValue, index.getCollatingValue(shortValue));
      String longValue = shortValue + "😀".repeat(10);
      assertEquals(shortValue, index.getCollatingValue(longValue));
      session.begin();
      session.newInstance("One").setProperty("two", longValue);
      session.commit();
      session.begin();
      try (var entries = index.streamEntriesMajor(session, shortValue, false, true)) {
        assertTrue(entries.anyMatch(e -> e.first().equals(shortValue)));
      }
      session.rollback();
    }
  }

  @Test
  public void aSmallerGlobalLimitStillRejectsKeysPreparedWithTheRecordedLimit() {
    int global = GlobalConfiguration.BTREE_MAX_KEY_SIZE.getValueAsInteger();
    try {
      GlobalConfiguration.BTREE_MAX_KEY_SIZE.setValue(100);
      createIndex(false, PropertyType.STRING);
      session.begin();
      session.newInstance("One").setProperty("two", "x".repeat(10000));
      assertThrows(TooBigIndexKeyException.class, () -> session.commit());
    } finally {
      GlobalConfiguration.BTREE_MAX_KEY_SIZE.setValue(global);
    }
  }

  @Test
  public void collectionItemsAndMapKeysAndValuesUseTheSamePrefixRule() {
    var cl = session.createClass("Items");
    cl.createProperty("items", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    cl.createProperty("mapping", PropertyType.EMBEDDEDMAP, PropertyType.STRING);
    cl.createIndex("items", INDEX_TYPE.NOTUNIQUE, "items");
    cl.createIndex("mapKeys", INDEX_TYPE.NOTUNIQUE, "mapping by key");
    cl.createIndex("mapValues", INDEX_TYPE.NOTUNIQUE, "mapping by value");
    String longValue = "a".repeat(10000);
    session.begin();
    var doc = session.newInstance("Items");
    doc.getOrCreateEmbeddedList("items").add(longValue);
    doc.getOrCreateEmbeddedMap("mapping").put(longValue, longValue);
    session.commit();
    session.begin();
    for (String name : List.of("items", "mapKeys", "mapValues")) {
      var index = session.getSharedContext().getIndexManager().getIndex(name);
      try (var rids = index.getRids(session, longValue)) {
        assertEquals(1, rids.count());
      }
    }
    session.rollback();
  }

  @Test
  public void sqlRangeRechecksRecordsSharingAPrefix() {
    createIndex(false, PropertyType.STRING);
    String prefix = "a".repeat(prefix(false, PropertyTypeInternal.STRING));
    session.begin();
    session.newInstance("One").setProperty("two", prefix + "a");
    session.newInstance("One").setProperty("two", prefix + "z");
    session.commit();
    session.begin();
    try (var results = session.query("select from One where two > ?", prefix + "m")) {
      assertEquals(1, results.stream().count());
    }
    session.rollback();
  }

  @Test
  public void nonpositiveRecordedLimitsLeaveOversizedKeysForTheTreeToReject() throws Exception {
    var index = createIndex(false, PropertyType.STRING);
    var storage = session.getStorage();
    // Set the persisted property and publish it as a configuration load would do.
    // Reflection keeps this fixture from adding a production write API.
    var field = AbstractStorage.class.getDeclaredField("configuration");
    field.setAccessible(true);
    var configuration = (CollectionBasedStorageConfiguration) field.get(storage);
    int original = storage.getRecordedMaxIndexKeySize();
    try {
      for (int limit : new int[] {-1, 0}) {
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(
            op -> {
              configuration.setMaxKeySize(op, limit);
              refreshRecordedLimit(storage, op);
            });
        String value = "x".repeat(10000);
        assertSame(value, index.getCollatingValue(value));
        session.begin();
        session.newInstance("One").setProperty("two", value);
        assertThrows(TooBigIndexKeyException.class, () -> session.commit());
      }
    } finally {
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(
          op -> {
            configuration.setMaxKeySize(op, original);
            refreshRecordedLimit(storage, op);
          });
    }
  }

  @Test
  public void nonpositiveRecordedLimitsRejectKeysInsideTheEightByteMargin() throws Exception {
    var storage = session.getStorage();
    var field = AbstractStorage.class.getDeclaredField("configuration");
    field.setAccessible(true);
    var configuration = (CollectionBasedStorageConfiguration) field.get(storage);
    int recorded = storage.getRecordedMaxIndexKeySize();
    int global = GlobalConfiguration.BTREE_MAX_KEY_SIZE.getValueAsInteger();
    try {
      GlobalConfiguration.BTREE_MAX_KEY_SIZE.setValue(100);
      var index = createIndex(false, PropertyType.STRING);
      for (int limit : new int[] {-1, 0, 100}) {
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(
            op -> {
              configuration.setMaxKeySize(op, limit);
              refreshRecordedLimit(storage, op);
            });
        session.begin();
        // 77 ASCII bytes and RID (1,1) serialize to 102 bytes, inside the RID allowance.
        var rid = new RecordId(1, 1);
        String value = "x".repeat(77);
        index.put(session.getActiveTransaction(), value, rid);
        if (limit <= 0) {
          assertThrows(TooBigIndexKeyException.class, () -> session.commit());
        } else {
          session.commit();
          session.begin();
          try (var rids = index.getRids(session, value)) {
            assertEquals(List.of(rid), rids.toList());
          }
          session.rollback();
        }
      }
    } finally {
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(
          op -> {
            configuration.setMaxKeySize(op, recorded);
            refreshRecordedLimit(storage, op);
          });
      GlobalConfiguration.BTREE_MAX_KEY_SIZE.setValue(global);
    }
  }

  @Test
  public void commitTimeBuildPreparesScalarAndCollectionKeysIncludingTheRidMargin() {
    var cl = session.createClass("Build");
    cl.createProperty("text", PropertyType.STRING).setCollate("ci");
    cl.createProperty("binary", PropertyType.BINARY);
    cl.createProperty("items", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    int p = prefix(false, PropertyTypeInternal.STRING);
    String text = "X".repeat(p + 1);
    byte[] binary = new byte[p + 1];
    session.begin();
    for (String field : List.of("text", "binary", "items")) {
      cl.createIndex("build." + field, INDEX_TYPE.NOTUNIQUE, field);
    }
    var doc = session.newInstance("Build");
    doc.setProperty("text", text);
    doc.setProperty("binary", binary);
    doc.newEmbeddedList("items", List.of(text));
    var rid = doc.getIdentity();
    session.commit();
    session.begin();
    for (String field : List.of("text", "binary", "items")) {
      var index = session.getSharedContext().getIndexManager().getIndex("build." + field);
      try (var rids = index.getRids(session, field.equals("binary") ? binary : text)) {
        assertEquals(List.of(rid), rids.toList());
      }
    }
    session.loadEntity(rid).delete();
    session.commit();
    session.begin();
    for (String field : List.of("text", "binary", "items")) {
      var index = session.getSharedContext().getIndexManager().getIndex("build." + field);
      try (var rids = index.getRids(session, field.equals("binary") ? binary : text)) {
        assertEquals(0, rids.count());
      }
    }
    session.rollback();
  }

  @Test
  public void commitTimeUniqueBuildRejectsSharedPrefixesEvenWithMergeKeysMetadata() {
    var cl = session.createClass("Build");
    cl.createProperty("text", PropertyType.STRING);
    String common = "x".repeat(prefix(true, PropertyTypeInternal.STRING));
    session.begin();
    cl.createIndex("build.text", INDEX_TYPE.UNIQUE.name(), null,
        Map.of("mergeKeys", true), new String[] {"text"});
    session.newInstance("Build").setProperty("text", common + "a");
    session.newInstance("Build").setProperty("text", common + "b");
    assertThrows(RecordDuplicatedException.class, () -> session.commit());
  }

  @Test
  public void collectionAssignmentsKeepSharedPreparedKeysUntilTheLastItemIsRemoved() {
    verifyCollectionUpdates(false);
  }

  @Test
  public void collectionEventsKeepSharedPreparedKeysUntilTheLastItemIsRemoved() {
    verifyCollectionUpdates(true);
  }

  @Test
  public void collectionReplacementAddsNewPreparedKeysAndRemovesOnlyMissingKeys() {
    var cl = session.createClass("Replacement");
    cl.createProperty("items", PropertyType.EMBEDDEDLIST, PropertyType.STRING).setCollate("ci");
    cl.createIndex("replacement", INDEX_TYPE.NOTUNIQUE, "items");
    String first = "A".repeat(10000);
    String second = "B".repeat(10000);
    session.begin();
    var doc = session.newInstance("Replacement");
    doc.newEmbeddedList("items", List.of(first, "X", "x"));
    var rid = doc.getIdentity();
    session.commit();
    session.begin();
    session.loadEntity(rid).newEmbeddedList("items", List.of(second, "x"));
    session.commit();
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex("replacement");
    try (var old = index.getRids(session, first);
        var added = index.getRids(session, second);
        var retained = index.getRids(session, "X")) {
      assertEquals(0, old.count());
      assertEquals(List.of(rid), added.toList());
      assertEquals(List.of(rid), retained.toList());
    }
    session.rollback();
  }

  @Test
  public void compositeCollectionUpdatesKeepTheEntryForTheRemainingCollatedItem() {
    for (boolean events : new boolean[] {false, true}) {
      String name = "Composite" + events;
      var cl = session.createClass(name);
      cl.createProperty("items", PropertyType.EMBEDDEDLIST, PropertyType.STRING).setCollate("ci");
      cl.createProperty("group", PropertyType.STRING);
      cl.createIndex(name, INDEX_TYPE.NOTUNIQUE, "group", "items");
      session.begin();
      var doc = session.newInstance(name);
      doc.setProperty("group", "group");
      doc.newEmbeddedList("items", List.of("X", "x"));
      var rid = doc.getIdentity();
      session.commit();
      var index = session.getSharedContext().getIndexManager().getIndex(name);
      for (int remaining = 1; remaining >= 0; remaining--) {
        session.begin();
        var loaded = session.loadEntity(rid);
        if (events) {
          loaded.getEmbeddedList("items").remove(0);
        } else {
          loaded.newEmbeddedList("items", remaining == 1 ? List.of("x") : List.of());
        }
        session.commit();
        session.begin();
        try (var rids = index.getRids(session, new CompositeKey(List.of("group", "x")))) {
          assertEquals(remaining, rids.count());
        }
        session.rollback();
      }
    }
  }

  private void verifyCollectionUpdates(boolean events) {
    // Lists, sets, map keys and map values must all diff complete prepared key sets.
    for (String kind : List.of("list", "set", "mapKeys", "mapValues", "binary")) {
      for (boolean longKeys : new boolean[] {false, true}) {
        String name = kind + longKeys;
        var cl = session.createClass(name);
        boolean binary = kind.equals("binary");
        var type = kind.startsWith("map") ? PropertyType.EMBEDDEDMAP
            : kind.equals("set") ? PropertyType.EMBEDDEDSET : PropertyType.EMBEDDEDLIST;
        var property = cl.createProperty("items", type,
            binary ? PropertyType.BINARY : PropertyType.STRING);
        if (!binary) {
          property.setCollate("ci");
        }
        cl.createIndex(name, INDEX_TYPE.NOTUNIQUE, kind.equals("mapKeys")
            ? "items by key" : kind.equals("mapValues") ? "items by value" : "items");
        Object first = binary ? new byte[longKeys ? 10000 : 2]
            : longKeys ? "X".repeat(10000) + "a" : "X";
        Object second = binary ? new byte[longKeys ? 10000 : 2]
            : longKeys ? "x".repeat(10000) + "b" : "x";
        session.begin();
        Entity doc = session.newInstance(name);
        if (type == PropertyType.EMBEDDEDMAP) {
          var values = new HashMap<String, Object>();
          values.put(kind.equals("mapKeys") ? (String) first : "a", first);
          values.put(kind.equals("mapKeys") ? (String) second : "b", second);
          doc.newEmbeddedMap("items", values);
        } else {
          if (kind.equals("set")) {
            doc.newEmbeddedSet("items", new HashSet<>(List.of(first, second)));
          } else {
            doc.newEmbeddedList("items", List.of(first, second));
          }
        }
        var rid = doc.getIdentity();
        session.commit();
        var index = session.getSharedContext().getIndexManager().getIndex(name);
        for (int remaining = 1; remaining >= 0; remaining--) {
          session.begin();
          doc = session.loadEntity(rid);
          if (events) {
            if (type == PropertyType.EMBEDDEDMAP) {
              var values = doc.getEmbeddedMap("items");
              values.remove(kind.equals("mapKeys") ? (String) (remaining == 1 ? first : second)
                  : remaining == 1 ? "a" : "b");
            } else if (kind.equals("set")) {
              doc.getEmbeddedSet("items").remove(remaining == 1 ? first : second);
            } else {
              doc.getEmbeddedList("items").remove(0);
            }
          } else if (type == PropertyType.EMBEDDEDMAP) {
            doc.newEmbeddedMap("items", remaining == 1
                ? Map.of(kind.equals("mapKeys") ? (String) second : "b", second) : Map.of());
          } else if (kind.equals("set")) {
            doc.newEmbeddedSet("items",
                remaining == 1 ? new HashSet<>(List.of(second)) : new HashSet<>());
          } else {
            doc.newEmbeddedList("items", remaining == 1 ? List.of(second) : List.of());
          }
          session.getActiveTransaction().preProcessRecordsAndExecuteCallCallbacks();
          try (var rids = index.getRids(session, second)) {
            assertEquals(name + " transaction", remaining, rids.count());
          }
          session.commit();
          session.begin();
          try (var rids = index.getRids(session, second)) {
            assertEquals(name + " committed", remaining, rids.count());
          }
          session.rollback();
        }
      }
    }
  }

  /** ADD and UPDATE events retain a shared prefix and remove it only at zero occurrences. */
  @Test
  public void longCollectionAddsAndUpdatesCountSharedPrefixes() {
    var cls = session.createClass("LongEvents");
    cls.createProperty("items", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    cls.createIndex("longEvents", INDEX_TYPE.NOTUNIQUE, "items");
    var index = session.getSharedContext().getIndexManager().getIndex("longEvents");
    String common = "x".repeat(prefix(false, PropertyTypeInternal.STRING));
    session.begin();
    var doc = session.newEntity("LongEvents");
    doc.newEmbeddedList("items", List.of(common + "a"));
    var rid = doc.getIdentity();
    session.commit();
    session.begin();
    var items = session.loadEntity(rid).<String>getEmbeddedList("items");
    items.add(common + "b");
    items.set(0, "other");
    session.commit();
    session.begin();
    try (var rids = index.getRids(session, common + "b")) {
      assertEquals(List.of(rid), rids.toList());
    }
    assertEquals(2, index.size(session));
    session.loadEntity(rid).<String>getEmbeddedList("items").set(1, "other");
    session.commit();
    session.begin();
    try (var rids = index.getRids(session, common + "a")) {
      assertEquals(0, rids.count());
    }
    assertEquals(1, index.size(session));
    session.rollback();
  }

  private static void refreshRecordedLimit(AbstractStorage storage,
      AtomicOperation op)
      throws Exception {
    var refresh = AbstractStorage.class.getDeclaredMethod("refreshRecordedMaxIndexKeySize",
        AtomicOperation.class);
    refresh.setAccessible(true);
    refresh.invoke(storage, op);
  }

  @Test
  public void compositeKeysAreNotShortenedAndStillRejectOversizedValues() {
    var cl = session.createClass("One");
    cl.createProperty("two", PropertyType.STRING);
    cl.createProperty("three", PropertyType.STRING);
    cl.createIndex("One.composite", INDEX_TYPE.NOTUNIQUE, "two", "three");
    session.begin();
    var doc = session.newInstance("One");
    doc.setProperty("two", "x".repeat(10000));
    doc.setProperty("three", "y");
    assertThrows(TooBigIndexKeyException.class, () -> session.commit());
  }
}
