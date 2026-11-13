package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.BTreeBasedLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.EmbeddedLinkBag;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.IsolatedLinkBagBTree.BTreeReadEntry;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.Test;

/** Direct lookup and creation equivalence for link sets and map keys. */
public class CollectionDirectLookupTest extends DbTestBase {

  private final Map<String, Index> references = new HashMap<>();

  private Index index(String name, PropertyType type, boolean composite, boolean ignored,
      String mode) {
    // Separate nonunique reference indexes let fresh comparison owners stay alive even when
    // the index under test is unique. The UNIQUE matrix uses unshortened keys.
    references.put(name, createIndex(name + "Reference", type, composite, ignored, "NOTUNIQUE"));
    return createIndex(name, type, composite, ignored, mode);
  }

  private Index createIndex(String name, PropertyType type, boolean composite, boolean ignored,
      String mode) {
    return createIndex(name, type, composite, ignored, mode, PropertyType.STRING);
  }

  private Index createIndex(String name, PropertyType type, boolean composite, boolean ignored,
      String mode, PropertyType keyType) {
    var cls = session.createClass(name);
    if (type == PropertyType.EMBEDDEDMAP || type == PropertyType.EMBEDDEDSET
        || type == PropertyType.EMBEDDEDLIST) {
      cls.createProperty("items", type, keyType);
    } else {
      cls.createProperty("items", type);
    }
    cls.createProperty("tag", PropertyType.STRING);
    String field = type == PropertyType.LINKMAP || type == PropertyType.EMBEDDEDMAP
        ? "items by key" : "items";
    cls.createIndex(name, mode, null, Map.of("ignoreNullValues", ignored),
        composite ? new String[] {"tag", field} : new String[] {field});
    return session.getSharedContext().getIndexManager().getIndex(name);
  }

  private void callbacks() {
    session.getActiveTransaction().preProcessRecordsAndExecuteCallCallbacks();
  }

  private Set<Object> keys(Index index, RID rid) {
    var result = new java.util.TreeSet<Object>(
        com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator.INSTANCE);
    try (var entries = index.stream(session)) {
      entries.filter(e -> e.second().equals(rid)).forEach(e -> result.add(e.first()));
    }
    try (var entries = index.getRids(session, null)) {
      if (entries.anyMatch(rid::equals)) {
        result.add(null);
      }
    }
    return result;
  }

  private void compare(String name, Index index, EntityImpl doc) {
    compare(name, index, references.get(name), doc);
  }

  private void compare(String name, Index index, Index reference, EntityImpl doc) {
    if (index.getDefinition() instanceof CompositeIndexDefinition composite) {
      var collates = ((CompositeCollate) reference.getDefinition().getCollate()).getCollates();
      collates.clear();
      collates.addAll(((CompositeCollate) composite.getCollate()).getCollates());
    } else {
      reference.getDefinition().setCollate(index.getDefinition().getCollate());
    }
    var fresh = (EntityImpl) session.newEntity(name + "Reference");
    fresh.setProperty("tag", doc.getPropertyInternal("tag"));
    Object value = doc.getPropertyInternal("items");
    if (value instanceof EntityLinkSetImpl set) {
      fresh.newLinkSet("items", new HashSet<>(set));
      int count = 0;
      for (var ignored : set) {
        count++;
      }
      assertEquals(count, set.size());
      for (var link : set) {
        assertEquals(1, set.getDelegate().getCount(link.getIdentity()));
      }
    } else if (value instanceof com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml) {
      fresh.newLinkMap("items", new HashMap<>(doc.getLinkMap("items")));
    } else if (value instanceof EntityEmbeddedSetImpl<?> set) {
      fresh.newEmbeddedSet("items", new HashSet<>(set));
    } else if (value instanceof com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedListImpl) {
      fresh.newEmbeddedList("items", new java.util.ArrayList<>((Collection<?>) value));
    } else if (value instanceof List) {
      fresh.newLinkList("items", new java.util.ArrayList<>(doc.getLinkList("items")));
    } else {
      fresh.newEmbeddedMap("items", new HashMap<>(doc.getEmbeddedMap("items")));
    }
    callbacks();
    assertEquals(name, keys(reference, fresh.getIdentity()), keys(index, doc.getIdentity()));
  }

  /** Repeated normal callbacks cover empty, singleton, bulk, clear and identity transitions. */
  @Test
  public void updatesMatchCreation() {
    int sequence = 0;
    for (var mode : List.of("UNIQUE", "NOTUNIQUE")) {
      for (var type : List.of(PropertyType.LINKSET, PropertyType.LINKMAP,
          PropertyType.EMBEDDEDMAP)) {
        for (boolean composite : List.of(false, true)) {
          for (boolean ignored : List.of(false, true)) {
            for (int initial : new int[] {0, 1, 50}) {
              String name = "LookupDiff" + sequence++;
              var index = index(name, type, composite, ignored, mode);
              session.begin();
              var doc = (EntityImpl) session.newEntity(name);
              doc.setProperty("tag", "t");
              if (type == PropertyType.LINKSET) {
                var set = new EntityLinkSetImpl(session, initial == 50
                    ? new BTreeBasedLinkBag(session, 1) : new EmbeddedLinkBag(session, 1));
                doc.setProperty("items", set);
                for (int i = 0; i < initial; i++) {
                  set.add(session.newEntity().getIdentity());
                }
                callbacks();
                var target = session.newEntity().getIdentity();
                for (int round = 0; round < 4; round++) {
                  set.add(target);
                  set.add(target);
                  compare(name, index, doc);
                  set.remove(target);
                  compare(name, index, doc);
                }
                set.addAll(List.of(target, session.newEntity().getIdentity()));
                compare(name, index, doc);
                set.removeAll(List.of(target));
                compare(name, index, doc);
                set.retainAll(List.of());
                compare(name, index, doc);
                set.add(target);
                compare(name, index, doc);
                assertTrue(!set.isEmpty());
                set.clear();
              } else {
                Map<String, Object> map = type == PropertyType.LINKMAP
                    ? (Map) doc.newLinkMap("items") : doc.newEmbeddedMap("items");
                Object value =
                    type == PropertyType.LINKMAP ? session.newEntity().getIdentity() : "v";
                for (int i = 0; i < initial; i++) {
                  map.put("k" + i, value);
                }
                callbacks();
                for (int round = 0; round < 4; round++) {
                  map.replace("absent", value);
                  map.put("a", value);
                  compare(name, index, doc);
                  map.put("a",
                      type == PropertyType.LINKMAP ? session.newEntity().getIdentity() : "w");
                  compare(name, index, doc);
                  map.remove("a");
                  map.put("a", value);
                  compare(name, index, doc);
                }
                map.putAll(Map.of("b", value, "c", value));
                compare(name, index, doc);
                map.clear();
              }
              compare(name, index, doc);
              session.commit();
            }
          }
        }
      }
    }

  }

  /** Every collection traversal entry point rejects access during the index update alone. */
  @Test
  public void eligibleUpdatesNeverScan() throws Exception {
    int sequence = 0;
    for (var type : List.of(PropertyType.LINKSET, PropertyType.LINKMAP,
        PropertyType.EMBEDDEDMAP)) {
      for (boolean composite : List.of(false, true)) {
        String name = "LookupGuard" + sequence++;
        var index = index(name, type, composite, false, "NOTUNIQUE");
        session.begin();
        var doc = (EntityImpl) session.newEntity(name);
        doc.setProperty("tag", "t");
        GuardBag bag = null;
        GuardMap backing = null;
        if (type == PropertyType.LINKSET) {
          bag = new GuardBag();
          var set = new EntityLinkSetImpl(session, bag);
          doc.setProperty("items", set);
          for (int i = 0; i < 50; i++) {
            set.add(session.newEntity().getIdentity());
          }
          callbacks();
          set.remove(set.iterator().next());
          set.add(session.newEntity().getIdentity());
        } else {
          Map map = type == PropertyType.LINKMAP
              ? doc.newLinkMap("items") : doc.newEmbeddedMap("items");
          map.put("old", type == PropertyType.LINKMAP ? session.newEntity().getIdentity() : "v");
          callbacks();
          map.remove("old");
          map.put("new", type == PropertyType.LINKMAP ? session.newEntity().getIdentity() : "v");
          var field = map.getClass().getDeclaredField("map");
          field.setAccessible(true);
          backing = new GuardMap();
          backing.putAll((Map) field.get(map));
          field.set(map, backing);
        }
        try {
          if (bag != null) {
            bag.guard = true;
          } else {
            backing.guard = true;
          }
          ClassIndexManager.processIndexUpdate(session.getActiveTransaction(), doc,
              Set.of("items"), index);
          assertTrue(bag != null ? bag.lookups >= 2 && bag.sizes > 0
              : backing.lookups >= 2 && backing.sizes > 0);
        } finally {
          if (bag != null) {
            bag.guard = false;
          } else {
            backing.guard = false;
          }
        }
        // Do not run callbacks after manually processing this event.
        session.rollback();
      }
    }
  }

  /** Unsafe UTF-16 bounds must scan even when a short key is also a stored long-key prefix. */
  @Test
  public void shorteningAndCollationFallbacksMatchCreation() throws Exception {
    var field = AbstractStorage.class.getDeclaredField("recordedMaxIndexKeySize");
    field.setAccessible(true);
    var storage = session.getStorage();
    int original = field.getInt(storage);
    int sequence = 0;
    try {
      for (int limit : new int[] {124, 1, 0}) {
        for (String collate : List.of("default", "ci")) {
          String name = "MapBoundary" + sequence++;
          var index = index(name, PropertyType.EMBEDDEDMAP, false, false, "NOTUNIQUE");
          index.getDefinition().setCollate(collate.equals("ci")
              ? new com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate()
              : new com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate());
          field.setInt(storage, limit);
          assertEquals(limit == 124 ? 100 : limit == 1 ? -23 : -1, index.keyPrefixLength());
          session.begin();
          var doc = (EntityImpl) session.newEntity(name);
          doc.setProperty("tag", "t");
          Map<String, String> map = doc.newEmbeddedMap("items");
          String emoji = "😀".repeat(25);
          map.put(emoji + "😀", "v");
          map.put(emoji + "x", "v");
          map.put("a".repeat(110), "v");
          map.put("a".repeat(100), "v");
          callbacks();
          for (String key : List.of(emoji + "😀", emoji + "x", "a".repeat(100),
              "a".repeat(110), "a".repeat(97), "a".repeat(98), "a".repeat(99),
              emoji + "b", "short")) {
            map.remove(key);
            compare(name, index, doc);
            map.put(key, "v");
            compare(name, index, doc);
          }
          session.rollback();
          field.setInt(storage, original);
        }
      }
    } finally {
      field.setInt(storage, original);
    }
  }

  /** Equal component definitions must not select the first component's collation. */
  @Test
  public void componentCollationUsesIdentityAndRejectsMissingComponent() throws Exception {
    var first = new PropertyListIndexDefinition("C", "items",
        com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal.LINK);
    var second = new PropertyListIndexDefinition("C", "items",
        com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal.LINK);
    second.setCollate(new DefaultSubclass());
    assertEquals(first, second);
    var composite = new CompositeIndexDefinition("C", List.of(first, second));
    var index = org.mockito.Mockito.mock(Index.class);
    org.mockito.Mockito.when(index.getDefinition()).thenReturn(composite);
    var method = ClassIndexManager.class.getDeclaredMethod("componentCollateClass",
        Index.class, IndexDefinitionMultiValue.class);
    method.setAccessible(true);
    assertEquals(DefaultSubclass.class, method.invoke(null, index, second));
    assertEquals(com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate.class,
        method.invoke(null, index, first));
    var absent = new PropertyListIndexDefinition("C", "absent",
        com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal.LINK);
    var error = org.junit.Assert.assertThrows(java.lang.reflect.InvocationTargetException.class,
        () -> method.invoke(null, index, absent));
    assertTrue(error.getCause() instanceof IllegalStateException);
  }

  private static final class DefaultSubclass
      extends com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate {
  }

  private static final class IdentityCollate
      implements com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate {
    @Override
    public String getName() {
      return "identityTest";
    }

    @Override
    public Object transform(Object value) {
      return value;
    }
  }

  /** Exact collation classes and exact collection classes determine fallback eligibility. */
  @Test
  public void explicitFallbacksScanAndMatchCreation() throws Exception {
    int sequence = 0;
    for (var collate : List.of(
        new com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate(),
        new DefaultSubclass(), new IdentityCollate())) {
      for (var type : List.of(PropertyType.LINKSET, PropertyType.LINKMAP,
          PropertyType.EMBEDDEDMAP)) {
        for (boolean composite : List.of(false, true)) {
          String name = "Fallback" + sequence++;
          var index = index(name, type, composite, false, "NOTUNIQUE");
          if (composite) {
            ((CompositeCollate) index.getDefinition().getCollate()).getCollates().set(1, collate);
          } else {
            index.getDefinition().setCollate(collate);
          }
          session.begin();
          var doc = (EntityImpl) session.newEntity(name);
          doc.setProperty("tag", "t");
          if (type == PropertyType.LINKSET) {
            var bag = new GuardBag();
            var set = new EntityLinkSetImpl(session, bag);
            doc.setProperty("items", set);
            set.add(session.newEntity().getIdentity());
            callbacks();
            bag.observe = true;
            set.add(session.newEntity().getIdentity());
            compare(name, index, doc);
            assertEquals(collate.getClass()
                != com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate.class,
                bag.scans > 0);
          } else {
            Map map = type == PropertyType.LINKMAP
                ? doc.newLinkMap("items") : doc.newEmbeddedMap("items");
            Object value = type == PropertyType.LINKMAP ? session.newEntity().getIdentity() : "v";
            map.put("A", value);
            callbacks();
            var backing = observe(map);
            map.put("b", value);
            compare(name, index, doc);
            assertTrue(backing.scans > 0);
            assertEquals(1, backing.lookups);
            backing.scans = 0;
            map.remove("A");
            compare(name, index, doc);
            assertTrue(backing.scans > 0);
          }
          session.rollback();
        }
      }

    }

    String name = "SubclassMap";
    var index = index(name, PropertyType.LINKMAP, false, false, "NOTUNIQUE");
    session.begin();
    var doc = (EntityImpl) session.newEntity(name);
    var map = new com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml(doc) {
    };
    doc.setProperty("items", map);
    assertTrue(doc.getPropertyInternal("items") == map);
    map.put("a", session.newEntity().getIdentity());
    callbacks();
    var backing = observe(map);
    map.put("b", session.newEntity().getIdentity());
    compare(name, index, doc);
    assertTrue(backing.scans > 0);
    session.rollback();
  }

  /** A LINKLIST shares the set definition but must iterate instead of reading set counts. */
  @Test
  public void linkListFallbackMatchesCreation() throws Exception {
    var index = index("ListFallback", PropertyType.LINKLIST, false, false, "NOTUNIQUE");
    session.begin();
    var doc = (EntityImpl) session.newEntity("ListFallback");
    var list = doc.newLinkList("items");
    list.add(session.newEntity().getIdentity());
    callbacks();
    var field = com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkListImpl.class
        .getDeclaredField("list");
    field.setAccessible(true);
    int[] reads = {0};
    var backing = new java.util.ArrayList<RID>((List<RID>) field.get(list)) {
      @Override
      public void forEach(java.util.function.Consumer<? super RID> action) {
        boolean update = inIndexUpdate();
        super.forEach(rid -> {
          if (update) {
            reads[0]++;
          }
          action.accept(rid);
        });
      }
    };
    field.set(list, backing);
    list.add(session.newEntity().getIdentity());
    compare("ListFallback", index, doc);
    assertEquals(list.size(), reads[0]);
    session.rollback();
  }

  /** BY KEY does not scan while the sibling BY VALUE index scans once and both match creation. */
  @Test
  public void twoMapIndexesRouteIndependently() throws Exception {
    for (var type : List.of(PropertyType.LINKMAP, PropertyType.EMBEDDEDMAP)) {
      String name = "TwoIndexes" + type;
      var key = index(name, type, false, false, "NOTUNIQUE");
      var cls = session.getMetadata().getSchema().getClass(name);
      cls.createIndex(name + "Values", "NOTUNIQUE", "items by value");
      var referenceClass = session.getMetadata().getSchema().getClass(name + "Reference");
      referenceClass.createIndex(name + "ReferenceValues", "NOTUNIQUE", "items by value");
      var manager = session.getSharedContext().getIndexManager();
      var valueIndex = manager.getIndex(name + "Values");
      var valueReference = manager.getIndex(name + "ReferenceValues");
      session.begin();
      var doc = (EntityImpl) session.newEntity(name);
      Map map = type == PropertyType.LINKMAP
          ? doc.newLinkMap("items") : doc.newEmbeddedMap("items");
      map.put("a", type == PropertyType.LINKMAP ? session.newEntity().getIdentity() : "v");
      callbacks();
      var backing = observe(map);
      map.put("b", type == PropertyType.LINKMAP ? session.newEntity().getIdentity() : "w");
      compare(name, key, doc);
      assertEquals(0, backing.keyScans);
      // Link-map AbstractMap.values() traverses entrySet(), embedded-map values()
      // delegates directly. Both must enter exactly one backing traversal.
      assertEquals(1, backing.scans);
      assertEquals(2, backing.lookups);
      compare(name, valueIndex, valueReference, doc);
      map.put("c", type == PropertyType.LINKMAP ? session.newEntity().getIdentity() : "x");
      backing.guard = true;
      try {
        ClassIndexManager.processIndexUpdate(session.getActiveTransaction(), doc,
            Set.of("items"), key);
      } finally {
        backing.guard = false;
      }
      backing.scans = 0;
      backing.lookups = 0;
      ClassIndexManager.processIndexUpdate(session.getActiveTransaction(), doc,
          Set.of("items"), valueIndex);
      assertEquals(1, backing.scans);
      assertEquals(1, backing.lookups);
      // No callback follows manual event processing.
      session.rollback();
    }
  }

  private GuardMap observe(Map map) throws Exception {
    var field = com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml.class
        .isInstance(map)
            ? com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml.class
                .getDeclaredField("map")
            : map.getClass().getDeclaredField("map");
    field.setAccessible(true);
    var backing = new GuardMap();
    backing.putAll((Map) field.get(map));
    field.set(map, backing);
    backing.observe = true;
    return backing;
  }

  /** Byte boundaries, colliding prefixes and mixed safe/unsafe events match creation. */
  @Test
  public void mapBoundariesObserveRouting() throws Exception {
    var field = AbstractStorage.class.getDeclaredField("recordedMaxIndexKeySize");
    field.setAccessible(true);
    var storage = session.getStorage();
    int original = field.getInt(storage);
    int sequence = 0;
    try {
      for (var type : List.of(PropertyType.LINKMAP, PropertyType.EMBEDDEDMAP)) {
        for (int p : new int[] {type == PropertyType.LINKMAP ? 40 : 100, -23}) {
          for (boolean reverse : List.of(false, true)) {
            String name = "ObservedBoundary" + sequence++;
            var index = index(name, type, false, false, "NOTUNIQUE");
            field.setInt(storage, p + 24);
            assertEquals(p, index.keyPrefixLength());
            session.begin();
            var doc = (EntityImpl) session.newEntity(name);
            Map map = type == PropertyType.LINKMAP
                ? doc.newLinkMap("items") : doc.newEmbeddedMap("items");
            Object value = type == PropertyType.LINKMAP ? session.newEntity().getIdentity() : "v";
            callbacks();
            var backing = observe(map);
            int budget = Math.max(40, p);
            var changes = new java.util.ArrayList<String>();
            for (int width = budget - 3; width <= budget; width++) {
              changes.add("a".repeat(width));
              String fourByte = "😀".repeat(width / 4) + "b".repeat(width % 4);
              assertEquals(width,
                  fourByte.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
              changes.add(fourByte);
            }
            // The kept prefix and the long key coexist, in both mutation orders.
            changes.add("z".repeat(budget));
            changes.add("z".repeat(budget + 4));
            changes.add("😀".repeat(budget / 4 + 1));
            changes.add("😀".repeat(budget / 4) + "x");
            if (reverse) {
              java.util.Collections.reverse(changes);
            }
            String longKey = "z".repeat(budget + 4);
            String prefix = "z".repeat(budget);
            String emojiLong = "😀".repeat(budget / 4 + 1);
            String emojiShort = "😀".repeat(budget / 4) + "x";
            for (var pair : List.of(List.of(longKey, prefix), List.of(emojiLong, emojiShort))) {
              var order = new java.util.ArrayList<>(pair);
              if (reverse) {
                java.util.Collections.reverse(order);
              }
              for (String key : order) {
                map.put(key, value);
                compare(name, index, doc);
              }
              for (String key : order) {
                map.remove(key);
                compare(name, index, doc);
              }
            }
            for (String key : changes) {
              for (boolean add : List.of(true, false)) {
                backing.scans = 0;
                var order = reverse ? List.of(key, "short") : List.of("short", key);
                for (String changed : order) {
                  if (add) {
                    map.put(changed, value);
                  } else {
                    map.remove(changed);
                  }
                }
                compare(name, index, doc);
                assertEquals("routing P=" + p + " key=" + key,
                    p >= 0, backing.scans > 0);
              }
            }
            session.rollback();
            field.setInt(storage, original);
          }
        }
      }
    } finally {
      field.setInt(storage, original);
    }
  }

  /** Three-byte prefixes in the safety margin collide with a following four-byte code point. */
  @Test
  public void marginSensitiveCollisionsScanAndMatchCreation() throws Exception {
    var field = AbstractStorage.class.getDeclaredField("recordedMaxIndexKeySize");
    field.setAccessible(true);
    var storage = session.getStorage();
    int original = field.getInt(storage);
    int sequence = 0;
    try {
      for (var type : List.of(PropertyType.LINKMAP, PropertyType.EMBEDDEDMAP)) {
        int p = type == PropertyType.LINKMAP ? 40 : 100;
        String shortKey = "\u0800".repeat(p / 3);
        String longKey = shortKey + "😀";
        assertEquals(p - 1, shortKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(3L * shortKey.length() < p);
        assertTrue(3L * shortKey.length() >= p - 3);
        for (boolean reverse : List.of(false, true)) {
          String name = "MarginCollision" + sequence++;
          var index = index(name, type, false, false, "NOTUNIQUE");
          field.setInt(storage, p + 24);
          assertEquals(p, index.keyPrefixLength());
          assertEquals(index.getCollatingValue(shortKey), index.getCollatingValue(longKey));
          session.begin();
          var doc = (EntityImpl) session.newEntity(name);
          Map map = type == PropertyType.LINKMAP
              ? doc.newLinkMap("items") : doc.newEmbeddedMap("items");
          Object value = type == PropertyType.LINKMAP ? session.newEntity().getIdentity() : "v";
          callbacks();
          var backing = observe(map);
          var order = reverse ? List.of(shortKey, longKey) : List.of(longKey, shortKey);
          for (boolean add : List.of(true, false)) {
            for (String key : order) {
              backing.scans = 0;
              if (add) {
                map.put(key, value);
              } else {
                map.remove(key);
              }
              compare(name, index, doc);
              assertTrue("margin collision must scan: " + type + " add=" + add,
                  backing.scans > 0);
            }
          }
          session.rollback();
          field.setInt(storage, original);
        }
      }
    } finally {
      field.setInt(storage, original);
    }
  }

  /** Persisted B-tree size equals actual iterator work before and after identity remapping. */
  @Test
  public void persistedSetSizeAndFurtherUpdatesMatchCreation() {
    var index = index("PersistedSet", PropertyType.LINKSET, false, false, "NOTUNIQUE");
    session.begin();
    var doc = (EntityImpl) session.newEntity("PersistedSet");
    var set = new EntityLinkSetImpl(session, new BTreeBasedLinkBag(session, 1));
    doc.setProperty("items", set);
    for (int i = 0; i < 50; i++) {
      set.add(session.newEntity().getIdentity());
    }
    assertTrue(doc.getIdentity().isNew());
    compare("PersistedSet", index, doc);
    var rid = doc.getIdentity();
    session.commit();
    session.begin();
    doc = session.getActiveTransaction().load(rid);
    set = (EntityLinkSetImpl) doc.getPropertyInternal("items");
    assertTrue(set.getDelegate() instanceof BTreeBasedLinkBag);
    compare("PersistedSet", index, doc);
    var removed = set.iterator().next();
    set.remove(removed);
    compare("PersistedSet", index, doc);
    set.add(removed);
    set.add(session.newEntity().getIdentity());
    compare("PersistedSet", index, doc);
    session.commit();
    session.begin();
    doc = session.getActiveTransaction().load(rid);
    compare("PersistedSet", index, doc);
    session.commit();
  }

  /** Link-map null keys are rejected, including when a subclass inherits the implementation. */
  @Test
  public void linkMapNullKeysAreRejected() {
    session.begin();
    for (var map : List.of(
        new com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml(session),
        new com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml(session) {
        })) {
      org.junit.Assert.assertThrows(IllegalArgumentException.class,
          () -> map.put(null, session.newEntity().getIdentity()));
      assertTrue(map.isEmpty());
    }
    session.rollback();
  }

  private Index embeddedIndex(String name, PropertyType keyType, boolean composite,
      boolean ignored, String mode) {
    references.put(name, createIndex(name + "Reference", PropertyType.EMBEDDEDSET,
        composite, ignored, "NOTUNIQUE", keyType));
    return createIndex(name, PropertyType.EMBEDDEDSET, composite, ignored, mode, keyType);
  }

  private GuardSet guard(EntityEmbeddedSetImpl<?> set) throws Exception {
    var backing = new GuardSet();
    backing.addAll(set);
    var field = EntityEmbeddedSetImpl.class.getDeclaredField("set");
    field.setAccessible(true);
    field.set(set, backing);
    return backing;
  }

  private void compareEmbedded(String name, Index index, EntityImpl doc, GuardSet backing,
      boolean direct) {
    backing.scans = 0;
    backing.lookups = 0;
    backing.sizes = 0;
    backing.guard = direct;
    try {
      compare(name, index, doc);
    } finally {
      backing.guard = false;
    }
    if (direct) {
      assertEquals(0, backing.scans);
      assertTrue("membership lookup required", backing.lookups > 0);
      assertTrue("size lookup required", backing.sizes > 0);
    } else {
      assertTrue("fallback must scan", backing.scans > 0);
    }
  }

  /** Exact built-in types use only membership and size across the complete index matrix. */
  @Test
  public void embeddedEligibleUpdatesNeverScan() throws Exception {
    var types = List.of(PropertyType.BOOLEAN, PropertyType.BYTE, PropertyType.SHORT,
        PropertyType.INTEGER, PropertyType.LONG, PropertyType.FLOAT, PropertyType.DOUBLE,
        PropertyType.STRING);
    var values = List.of(List.of(false, true), List.of((byte) 1, (byte) 2),
        List.of((short) 1, (short) 2), List.of(1, 2), List.of(1L, 2L),
        List.of(0.0f, -0.0f, Float.NaN, 2.0f), List.of(0.0d, -0.0d, Double.NaN, 2.0d),
        List.of("a", "b", "c", "d"));
    int sequence = 0;
    for (int t = 0; t < types.size(); t++) {
      for (boolean composite : List.of(false, true)) {
        for (boolean ignored : List.of(false, true)) {
          for (String mode : List.of("UNIQUE", "NOTUNIQUE")) {
            for (int initial : new int[] {0, 1, 40}) {
              String name = "EmbeddedExact" + sequence++;
              var index = embeddedIndex(name, types.get(t), composite, ignored, mode);
              session.begin();
              var doc = (EntityImpl) session.newEntity(name);
              doc.setProperty("tag", "t");
              var set = (EntityEmbeddedSetImpl<Object>) doc.<Object>newEmbeddedSet("items");
              for (int i = 0; i < initial; i++) {
                Object item = switch (types.get(t)) {
                  case BYTE -> (byte) i;
                  case SHORT -> (short) i;
                  case INTEGER -> i;
                  case LONG -> (long) i;
                  case FLOAT -> (float) i;
                  case DOUBLE -> (double) i;
                  case STRING -> "initial" + i;
                  default -> i % 2 == 0;
                };
                set.add(item);
              }
              callbacks();
              var backing = guard(set);
              for (var value : values.get(t)) {
                if (set.contains(value)) {
                  set.remove(value);
                } else {
                  set.add(value);
                }
                compareEmbedded(name, index, doc, backing, true);
              }
              set.add(null);
              compareEmbedded(name, index, doc, backing, true);
              set.remove(null);
              compareEmbedded(name, index, doc, backing, true);
              // Boolean populations can be empty after toggling both values.
              if (set.add(values.get(t).getFirst())) {
                compareEmbedded(name, index, doc, backing, true);
              }
              set.clear();
              compareEmbedded(name, index, doc, backing, true);
              session.rollback();
            }
          }
        }
      }
    }
  }

  /** Mixed raw values, conversion and sticky mixed history must retain the scan. */
  @Test
  public void embeddedConversionsAndMixedHistoryMatchCreation() throws Exception {
    int sequence = 0;
    for (var type : List.of(PropertyType.INTEGER, PropertyType.LONG, PropertyType.STRING,
        PropertyType.DOUBLE)) {
      String name = "EmbeddedMixed" + sequence++;
      var index = embeddedIndex(name, type, false, false, "NOTUNIQUE");
      session.begin();
      var doc = (EntityImpl) session.newEntity(name);
      var set = (EntityEmbeddedSetImpl<Object>) doc.<Object>newEmbeddedSet("items");
      Object first = type == PropertyType.DOUBLE ? (Object) Float.valueOf(1)
          : type == PropertyType.STRING ? (Object) Integer.valueOf(1) : Long.valueOf(1);
      set.add(first);
      callbacks();
      var backing = guard(set);
      set.add(type == PropertyType.STRING ? "1" : Integer.valueOf(1));
      compareEmbedded(name, index, doc, backing, false);
      set.remove(first);
      compareEmbedded(name, index, doc, backing, false);
      set.clear();
      // A new homogeneous population restores direct eligibility.
      Object exact = switch (type) {
        case LONG -> Long.valueOf(2);
        case DOUBLE -> Double.valueOf(2);
        case STRING -> "2";
        default -> Integer.valueOf(2);
      };
      set.add(exact);
      compareEmbedded(name, index, doc, backing, true);
      session.rollback();
    }
    for (boolean clear : List.of(false, true)) {
      String name = "EmbeddedConvertedLookup" + sequence++;
      var index = embeddedIndex(name, PropertyType.INTEGER, false, false, "NOTUNIQUE");
      session.begin();
      var doc = (EntityImpl) session.newEntity(name);
      var set = (EntityEmbeddedSetImpl<Object>) doc.<Object>newEmbeddedSet("items");
      callbacks();
      var backing = guard(set);
      set.add(1L);
      if (clear) {
        set.clear();
      } else {
        set.remove(1L);
      }
      set.add(1);
      compareEmbedded(name, index, doc, backing, true);
      session.rollback();
    }
    for (var type : List.of(PropertyType.LONG, PropertyType.DOUBLE)) {
      String name = "EmbeddedUniformConversion" + sequence++;
      var index = embeddedIndex(name, type, false, false, "NOTUNIQUE");
      session.begin();
      var doc = (EntityImpl) session.newEntity(name);
      var set = (EntityEmbeddedSetImpl<Object>) doc.<Object>newEmbeddedSet("items");
      set.add(type == PropertyType.LONG ? (Object) Integer.valueOf(1) : Float.valueOf(1));
      callbacks();
      var backing = guard(set);
      set.add(type == PropertyType.LONG ? (Object) Integer.valueOf(2) : Float.valueOf(2));
      compareEmbedded(name, index, doc, backing, false);
      session.rollback();
    }
  }

  /** Reload rebuilds exact type evidence, while a later different numeric class poisons it. */
  @Test
  public void embeddedReloadAndFurtherUpdates() throws Exception {
    String name = "EmbeddedReload";
    var index = embeddedIndex(name, PropertyType.LONG, true, false, "NOTUNIQUE");
    session.begin();
    var doc = (EntityImpl) session.newEntity(name);
    doc.setProperty("tag", "t");
    doc.newEmbeddedSet("items", Set.of(1L, 2L));
    var rid = doc.getIdentity();
    session.commit();
    session.begin();
    doc = session.getActiveTransaction().load(rid);
    var set = (EntityEmbeddedSetImpl<Object>) doc.<Object>getEmbeddedSet("items");
    assertTrue(set.hasExactItemClass(Long.class));
    var backing = guard(set);
    set.add(3L);
    compareEmbedded(name, index, doc, backing, true);
    set.add(1);
    compareEmbedded(name, index, doc, backing, false);
    session.commit();
  }

  /** Collation and unsupported key types route to the scan, not raw membership. */
  @Test
  public void embeddedIneligibleTypesAndCollationsScan() throws Exception {
    int sequence = 0;
    for (var type : List.of(PropertyType.DECIMAL, PropertyType.BINARY, PropertyType.DATETIME,
        PropertyType.STRING, PropertyType.STRING, PropertyType.INTEGER)) {
      String name = "EmbeddedFallback" + sequence++;
      var index = embeddedIndex(name, type, false, false, "NOTUNIQUE");
      Object a = switch (type) {
        case DECIMAL -> new java.math.BigDecimal("1");
        case BINARY -> new byte[] {1};
        case DATETIME -> new java.util.Date(1000);
        case INTEGER -> 1;
        default -> "a";
      };
      Object b = switch (type) {
        case DECIMAL -> new java.math.BigDecimal("2");
        case BINARY -> new byte[] {2};
        case DATETIME -> new java.util.Date(2000);
        case INTEGER -> 2;
        default -> "A";
      };
      if (sequence == 4 || sequence == 6) {
        index.getDefinition().setCollate(
            new com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate());
      } else if (sequence == 5) {
        index.getDefinition().setCollate(
            new com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate() {
            });
      }
      session.begin();
      var doc = (EntityImpl) session.newEntity(name);
      var set = (EntityEmbeddedSetImpl<Object>) doc.<Object>newEmbeddedSet("items");
      set.add(a);
      callbacks();
      var backing = guard(set);
      set.add(b);
      compareEmbedded(name, index, doc, backing, sequence == 6);
      session.rollback();
    }
  }

  /** The conservative length threshold and its three-byte margin govern both event orders. */
  @Test
  public void embeddedStringBoundariesAndCollisions() throws Exception {
    var field = AbstractStorage.class.getDeclaredField("recordedMaxIndexKeySize");
    field.setAccessible(true);
    int original = field.getInt(session.getStorage());
    int sequence = 0;
    try {
      for (int p : new int[] {100, -23}) {
        for (boolean reverse : List.of(false, true)) {
          String name = "EmbeddedBoundary" + sequence++;
          var index = embeddedIndex(name, PropertyType.STRING, false, false, "NOTUNIQUE");
          field.setInt(session.getStorage(), p + 24);
          assertEquals(p, index.keyPrefixLength());
          session.begin();
          var doc = (EntityImpl) session.newEntity(name);
          var set = (EntityEmbeddedSetImpl<Object>) doc.<Object>newEmbeddedSet("items");
          callbacks();
          var backing = guard(set);
          int budget = Math.max(100, p);
          var changes = new java.util.ArrayList<String>();
          for (int length : new int[] {31, 32, 33, budget - 3, budget - 2, budget - 1,
              budget, budget + 1}) {
            changes.add("x".repeat(length));
            changes.add("😀".repeat(length / 4) + "b".repeat(length % 4));
          }
          String shortKey = "\u0800".repeat(33);
          String longKey = shortKey + "😀";
          if (p >= 0) {
            assertEquals(index.getCollatingValue(shortKey), index.getCollatingValue(longKey));
          }
          changes.add(shortKey);
          changes.add(longKey);
          if (reverse) {
            java.util.Collections.reverse(changes);
          }
          for (String value : changes) {
            set.add(value);
            compareEmbedded(name, index, doc, backing,
                p < 0 || 3L * value.length() < p - 3);
          }
          for (String value : changes) {
            set.remove(value);
            compareEmbedded(name, index, doc, backing,
                p < 0 || 3L * value.length() < p - 3);
          }
          session.rollback();
          field.setInt(session.getStorage(), original);
        }
      }
    } finally {
      field.setInt(session.getStorage(), original);
    }
  }

  /** Shared list definitions do not admit embedded lists to the membership branch. */
  @Test
  public void embeddedListRetainsScan() throws Exception {
    String name = "EmbeddedListFallback";
    references.put(name, createIndex(name + "Reference", PropertyType.EMBEDDEDLIST,
        false, false, "NOTUNIQUE", PropertyType.INTEGER));
    var index = createIndex(name, PropertyType.EMBEDDEDLIST, false, false, "NOTUNIQUE",
        PropertyType.INTEGER);
    session.begin();
    var doc = (EntityImpl) session.newEntity(name);
    var list = doc.<Object>newEmbeddedList("items");
    list.add(1);
    callbacks();
    var backing = new java.util.ArrayList<Object>(list) {
      int scans;

      @Override
      public void forEach(java.util.function.Consumer<? super Object> consumer) {
        if (inIndexUpdate()) {
          scans++;
        }
        super.forEach(consumer);
      }
    };
    var field = list.getClass().getDeclaredField("list");
    field.setAccessible(true);
    field.set(list, backing);
    list.add(1);
    compare(name, index, doc);
    assertTrue(backing.scans > 0);
    backing.scans = 0;
    list.remove(0);
    compare(name, index, doc);
    assertTrue(backing.scans > 0);
    // Duplicate preservation distinguishes the scan from a 0/1 membership result.
    assertEquals(Set.of(1), keys(index, doc.getIdentity()));
    session.rollback();
  }

  /** Independent indexes on one set choose their own collation-dependent route. */
  @Test
  public void embeddedTwoIndexesAndUnknownState() throws Exception {
    String name = "EmbeddedTwo";
    var index = embeddedIndex(name, PropertyType.STRING, false, false, "NOTUNIQUE");
    session.getMetadata().getSchema().getClass(name)
        .createIndex(name + "Second", "UNIQUE", "items");
    var second = session.getSharedContext().getIndexManager().getIndex(name + "Second");
    second.getDefinition().setCollate(
        new com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate());
    session.begin();
    var doc = (EntityImpl) session.newEntity(name);
    var set = (EntityEmbeddedSetImpl<Object>) doc.<Object>newEmbeddedSet("items");
    set.add("a");
    callbacks();
    var backing = guard(set);
    set.add("b");
    compare(name, index, doc);
    assertTrue(backing.lookups > 0);
    assertTrue(backing.scans > 0);
    assertEquals(Set.of("a", "b"), keys(second, doc.getIdentity()));
    var state = EntityEmbeddedSetImpl.class.getDeclaredField("itemClass");
    state.setAccessible(true);
    state.set(set, null);
    set.add("c");
    compareEmbedded(name, index, doc, backing, false);
    assertEquals(Set.of("a", "b", "c"), keys(second, doc.getIdentity()));
    session.rollback();
  }

  /** Changing a second composite component retains field-assignment comparison. */
  @Test
  public void embeddedCompositeSecondComponentChangeScans() throws Exception {
    String name = "EmbeddedCompositeAssignment";
    var index = embeddedIndex(name, PropertyType.INTEGER, true, false, "NOTUNIQUE");
    session.begin();
    var doc = (EntityImpl) session.newEntity(name);
    doc.setProperty("tag", "t");
    var set = (EntityEmbeddedSetImpl<Object>) doc.<Object>newEmbeddedSet("items");
    set.add(1);
    callbacks();
    var backing = guard(set);
    set.add(2);
    doc.setProperty("tag", "u");
    compareEmbedded(name, index, doc, backing, false);
    session.rollback();
  }

  /** Embedded records and subclass membership implementations cannot use exact lookup. */
  @Test
  public void embeddedRecordsAndSubclassScan() throws Exception {
    for (boolean subclass : List.of(false, true)) {
      String name = subclass ? "EmbeddedSubclass" : "EmbeddedRecords";
      var index = embeddedIndex(name, PropertyType.STRING, false, false, "NOTUNIQUE");
      session.begin();
      var doc = (EntityImpl) session.newEntity(name);
      EntityEmbeddedSetImpl<Object> set;
      if (subclass) {
        set = new EntityEmbeddedSetImpl<>(doc) {
        };
        doc.setProperty("items", set);
        set.add("a");
      } else {
        set = (EntityEmbeddedSetImpl<Object>) doc.<Object>newEmbeddedSet("items");
        var item = session.newEmbeddedEntity();
        item.setProperty("value", "a");
        set.add(item);
      }
      callbacks();
      var backing = guard(set);
      if (subclass) {
        set.add("b");
      } else {
        var item = session.newEmbeddedEntity();
        item.setProperty("value", "b");
        set.add(item);
      }
      compareEmbedded(name, index, doc, backing, false);
      session.rollback();
    }
  }

  private static final class GuardSet extends HashSet<Object> {
    boolean guard;
    int scans;
    int lookups;
    int sizes;

    private void scan() {
      if (inIndexUpdate()) {
        scans++;
        if (guard) {
          throw new AssertionError("Index update scanned embedded set");
        }
      }
    }

    @Override
    public boolean contains(Object value) {
      if (inIndexUpdate()) {
        lookups++;
      }
      return super.contains(value);
    }

    @Override
    public int size() {
      if (inIndexUpdate()) {
        sizes++;
      }
      return super.size();
    }

    @Override
    public Iterator<Object> iterator() {
      scan();
      return super.iterator();
    }

    @Override
    public Spliterator<Object> spliterator() {
      scan();
      return super.spliterator();
    }

    @Override
    public Stream<Object> stream() {
      scan();
      return super.stream();
    }

    @Override
    public void forEach(java.util.function.Consumer<? super Object> action) {
      scan();
      super.forEach(action);
    }

    @Override
    public Object[] toArray() {
      scan();
      return super.toArray();
    }

    @Override
    public <T> T[] toArray(T[] array) {
      scan();
      return super.toArray(array);
    }
  }

  private final class GuardBag extends BTreeBasedLinkBag {
    boolean guard;
    boolean observe;
    int scans;
    int lookups;
    int sizes;

    GuardBag() {
      super(CollectionDirectLookupTest.this.session, 1);
    }

    private void scan() {
      if (observe && inIndexUpdate()) {
        scans++;
      }
      if (guard) {
        throw new AssertionError("Index update scanned set");
      }
    }

    @Override
    public int getCount(RID rid) {
      if (guard && rid != null) {
        lookups++;
      }
      return super.getCount(rid);
    }

    @Override
    public int size() {
      if (guard) {
        sizes++;
      }
      return super.size();
    }

    @Override
    public Iterator<RidPair> iterator() {
      scan();
      return super.iterator();
    }

    @Override
    public Spliterator<RidPair> spliterator() {
      scan();
      return super.spliterator();
    }

    @Override
    public Stream<RidPair> stream() {
      scan();
      return super.stream();
    }

    @Override
    protected Spliterator<BTreeReadEntry<RID>> btreeSpliterator(AtomicOperation op) {
      scan();
      return super.btreeSpliterator(op);
    }
  }

  private static boolean inIndexUpdate() {
    return java.util.Arrays.stream(Thread.currentThread().getStackTrace())
        .anyMatch(frame -> frame.getMethodName().equals("processIndexUpdateEvents")
            || frame.getMethodName().equals("processIndexUpdate"));
  }

  private static final class GuardMap extends HashMap {
    boolean guard;
    boolean observe;
    int scans;
    int keyScans;
    int valueScans;
    int lookups;
    int sizes;

    private void scan() {
      if (observe && inIndexUpdate()) {
        scans++;
      }
      if (guard) {
        throw new AssertionError("Index update scanned map");
      }
    }

    @Override
    public boolean containsKey(Object key) {
      if (guard || observe && inIndexUpdate()) {
        lookups++;
      }
      return super.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
      scan();
      return super.containsValue(value);
    }

    @Override
    public int size() {
      if (guard) {
        sizes++;
      }
      return super.size();
    }

    @Override
    public Set keySet() {
      if (observe && inIndexUpdate()) {
        keyScans++;
      }
      scan();
      return super.keySet();
    }

    @Override
    public Collection values() {
      if (observe && inIndexUpdate()) {
        valueScans++;
      }
      scan();
      return super.values();
    }

    @Override
    public Set entrySet() {
      scan();
      return super.entrySet();
    }

    @Override
    public void forEach(BiConsumer action) {
      scan();
      super.forEach(action);
    }
  }
}
