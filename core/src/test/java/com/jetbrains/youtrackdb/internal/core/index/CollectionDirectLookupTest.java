package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
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
    var cls = session.createClass(name);
    if (type == PropertyType.EMBEDDEDMAP) {
      cls.createProperty("items", type, PropertyType.STRING);
    } else {
      cls.createProperty("items", type);
    }
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex(name, mode, null, Map.of("ignoreNullValues", ignored),
        composite ? new String[] {"tag", type == PropertyType.LINKSET
            || type == PropertyType.LINKLIST ? "items" : "items by key"}
            : new String[] {type == PropertyType.LINKSET
                || type == PropertyType.LINKLIST ? "items" : "items by key"});
    return session.getSharedContext().getIndexManager().getIndex(name);
  }

  private void callbacks() {
    session.getActiveTransaction().preProcessRecordsAndExecuteCallCallbacks();
  }

  private Set<Object> keys(Index index, RID rid) {
    var result = new HashSet<Object>();
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
    fresh.setProperty("tag", "t");
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
        .anyMatch(frame -> frame.getMethodName().equals("processIndexUpdateEvents"));
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
