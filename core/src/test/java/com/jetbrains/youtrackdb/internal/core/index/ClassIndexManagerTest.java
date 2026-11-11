package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link ClassIndexManager} — the utility that hooks into record create/update/delete
 * events and synchronises the per-class indexes accordingly.
 *
 * <p>All paths are exercised via the public database API (insert/update/delete records through the
 * session), which internally invokes ClassIndexManager. Tests then query the index directly to
 * verify the expected state.
 *
 * <p>The class under test uses a UNIQUE index backed by {@code IndexUnique} (extends
 * {@code IndexOneValue}). {@code IndexOneValue.get()} returns the matching {@code RID} when a
 * key is present or {@code null} when it is absent, so presence is checked via {@code != null}
 * rather than {@code .iterator().hasNext()}.
 */
public class ClassIndexManagerTest extends DbTestBase {

  /** Raw mutations must not collate prepared keys again, for replacements or item events. */
  @Test
  public void collectionMutationsApplyNonIdempotentCollationOnce() {
    for (boolean events : List.of(false, true)) {
      String name = "NonIdempotent" + events;
      var cls = session.createClass(name);
      cls.createProperty("items", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
      cls.createIndex(name, SchemaClass.INDEX_TYPE.NOTUNIQUE, "items");
      var index = session.getSharedContext().getIndexManager().getIndex(name);
      index.getDefinition().setCollate(new Collate() {
        @Override
        public String getName() {
          return "test-prefix-reverse";
        }

        @Override
        public Object transform(Object value) {
          return value instanceof String text ? "x" + new StringBuilder(text).reverse() : value;
        }
      });
      session.begin();
      var doc = session.newEntity(name);
      doc.newEmbeddedList("items", List.of("az"));
      var rid = doc.getIdentity();
      session.commit();
      session.begin();
      var loaded = session.loadEntity(rid);
      if (events) {
        loaded.<String>getEmbeddedList("items").set(0, "ba");
      } else {
        loaded.newEmbeddedList("items", List.of("ba"));
      }
      session.commit();
      session.begin();
      assertKeyCount(index, "az", 0);
      assertKeyCount(index, "ba", 1);
      assertEquals(1, index.size(session));
      session.rollback();
    }
  }

  /** ADD, UPDATE and repeated events count folded duplicates, including composite keys. */
  @Test
  public void touchedKeysCountAddsUpdatesAndRepeatedEvents() {
    for (boolean composite : List.of(false, true)) {
      String name = "Touched" + composite;
      var cls = session.createClass(name);
      cls.createProperty("items", PropertyType.EMBEDDEDLIST, PropertyType.STRING).setCollate("ci");
      cls.createProperty("tag", PropertyType.STRING);
      cls.createIndex(name, SchemaClass.INDEX_TYPE.NOTUNIQUE,
          composite ? new String[] {"tag", "items"} : new String[] {"items"});
      var index = session.getSharedContext().getIndexManager().getIndex(name);
      session.begin();
      var doc = session.newEntity(name);
      doc.setProperty("tag", "t");
      doc.newEmbeddedList("items", List.of("X", "x"));
      var rid = doc.getIdentity();
      session.commit();
      session.begin();
      var items = session.loadEntity(rid).<String>getEmbeddedList("items");
      items.add("Y");
      items.set(0, "y");
      items.add("temporary");
      items.remove("temporary");
      session.commit();
      session.begin();
      Object x = composite ? new CompositeKey("t", "x") : "x";
      Object y = composite ? new CompositeKey("t", "y") : "y";
      assertKeyCount(index, x, 1);
      assertKeyCount(index, y, 1);
      assertEquals(2, index.size(session));
      items = session.loadEntity(rid).getEmbeddedList("items");
      items.remove("x");
      session.commit();
      session.begin();
      assertKeyCount(index, x, 0);
      assertKeyCount(index, y, 1);
      session.rollback();
    }
  }

  /** Map key UPDATE keeps the key, while value UPDATE keeps a shared folded value. */
  @Test
  public void mapEventsCountKeysAndValuesSeparately() {
    var cls = session.createClass("Maps");
    cls.createProperty("items", PropertyType.EMBEDDEDMAP, PropertyType.STRING).setCollate("ci");
    cls.createIndex("mapKeys", SchemaClass.INDEX_TYPE.NOTUNIQUE, "items by key");
    cls.createIndex("mapValues", SchemaClass.INDEX_TYPE.NOTUNIQUE, "items by value");
    var keys = session.getSharedContext().getIndexManager().getIndex("mapKeys");
    var values = session.getSharedContext().getIndexManager().getIndex("mapValues");
    session.begin();
    var doc = session.newEntity("Maps");
    doc.newEmbeddedMap("items", Map.of("a", "X", "b", "x"));
    var rid = doc.getIdentity();
    session.commit();
    session.begin();
    var map = session.loadEntity(rid).<String>getEmbeddedMap("items");
    map.put("a", "Y");
    map.put("c", "y");
    map.remove("b");
    session.commit();
    session.begin();
    assertKeyCount(keys, "a", 1);
    assertKeyCount(keys, "b", 0);
    assertKeyCount(keys, "c", 1);
    assertKeyCount(values, "x", 0);
    assertKeyCount(values, "y", 1);
    assertEquals(1, values.size(session));
    session.rollback();
  }

  /** Removing one duplicate link keeps the entry until the final occurrence disappears. */
  @Test
  public void linkListEventsKeepDuplicateLink() {
    var cls = session.createClass("Links");
    cls.createProperty("items", PropertyType.LINKLIST);
    cls.createIndex("links", SchemaClass.INDEX_TYPE.NOTUNIQUE, "items");
    var index = session.getSharedContext().getIndexManager().getIndex("links");
    session.begin();
    var target = session.newEntity();
    var doc = session.newEntity("Links");
    doc.newLinkList("items", List.of(target, target));
    var rid = doc.getIdentity();
    var targetRid = target.getIdentity();
    session.commit();
    session.begin();
    session.loadEntity(rid).getLinkList("items").remove(0);
    session.commit();
    session.begin();
    assertKeyCount(index, targetRid, 1);
    session.loadEntity(rid).getLinkList("items").remove(0);
    session.commit();
    session.begin();
    assertKeyCount(index, targetRid, 0);
    session.rollback();
  }

  /** Null and equal-content binary keys survive until their last occurrence is removed. */
  @Test
  public void binaryAndNullItemEventsKeepSharedKeys() {
    var cls = session.createClass("BinaryItems");
    cls.createProperty("items", PropertyType.EMBEDDEDLIST, PropertyType.BINARY);
    cls.createIndex("binaryItems", "NOTUNIQUE", null,
        Map.of("ignoreNullValues", false), new String[] {"items"});
    var index = session.getSharedContext().getIndexManager().getIndex("binaryItems");
    session.begin();
    var doc = session.newEntity("BinaryItems");
    doc.newEmbeddedList("items", Arrays.asList(new byte[] {1}, new byte[] {1}, null, null));
    var rid = doc.getIdentity();
    session.commit();
    session.begin();
    var items = session.loadEntity(rid).getEmbeddedList("items");
    items.remove(0);
    items.remove(1);
    session.commit();
    session.begin();
    assertKeyCount(index, new byte[] {1}, 1);
    assertKeyCount(index, null, 1);
    session.loadEntity(rid).getEmbeddedList("items").clear();
    session.commit();
    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  /** Edge-bag updates on existing vertices bypass public booked-name validation. */
  @Test
  public void vertexEdgeBagEventsAddAndRemoveIndexedEdges() {
    session.createEdgeClass("IndexedEdge");
    var cls = session.createVertexClass("IndexedVertex");
    cls.createProperty("out_IndexedEdge", PropertyType.LINKBAG);
    cls.createIndex("edgeBag", SchemaClass.INDEX_TYPE.NOTUNIQUE, "out_IndexedEdge");
    cls.createIndex("edgeTargets", SchemaClass.INDEX_TYPE.NOTUNIQUE, "out_IndexedEdge by value");
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex("compositeEdgeBag", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "tag", "out_IndexedEdge");
    var index = session.getSharedContext().getIndexManager().getIndex("edgeBag");
    var composite = session.getSharedContext().getIndexManager().getIndex("compositeEdgeBag");
    session.begin();
    var from = session.newVertex("IndexedVertex");
    from.setProperty("tag", "t");
    var to = session.newVertex();
    var fromRid = from.getIdentity();
    var toRid = to.getIdentity();
    from.addEdge(to, "IndexedEdge");
    session.commit();
    session.begin();
    var edge = session.loadVertex(fromRid).addEdge(session.loadVertex(toRid), "IndexedEdge");
    var edgeRid = edge.getIdentity();
    session.commit();
    session.begin();
    assertKeyCount(index, edgeRid, 1);
    assertKeyCount(composite, new CompositeKey("t", edgeRid), 1);
    session.loadEdge(edgeRid).delete();
    session.commit();
    session.begin();
    assertKeyCount(index, edgeRid, 0);
    assertKeyCount(composite, new CompositeKey("t", edgeRid), 0);
    assertEquals(1, index.size(session));
    assertEquals(1, composite.size(session));
    assertKeyCount(session.getSharedContext().getIndexManager().getIndex("edgeTargets"), toRid, 1);
    session.rollback();
  }

  /** Composite empty collections contribute null, including after several item events. */
  @Test
  public void compositeEventsMaintainEmptyCollectionNullKey() {
    var cls = session.createClass("EmptyComposite");
    cls.createProperty("items", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex("emptyComposite", "NOTUNIQUE", null,
        Map.of("ignoreNullValues", false), new String[] {"tag", "items"});
    var index = session.getSharedContext().getIndexManager().getIndex("emptyComposite");
    session.begin();
    var doc = session.newEntity("EmptyComposite");
    doc.setProperty("tag", "t");
    doc.newEmbeddedList("items");
    var rid = doc.getIdentity();
    session.commit();
    session.begin();
    session.loadEntity(rid).<String>getEmbeddedList("items").add("a");
    session.commit();
    session.begin();
    assertKeyCount(index, new CompositeKey("t", null), 0);
    assertKeyCount(index, new CompositeKey("t", "a"), 1);
    session.loadEntity(rid).getEmbeddedList("items").clear();
    session.commit();
    session.begin();
    assertKeyCount(index, new CompositeKey("t", null), 1);
    assertEquals(1, index.size(session));
    session.rollback();
  }

  /** A scalar change plus item events compares complete composite keys with old components. */
  @Test
  public void compositeConcurrentComponentChangesCompareFullKeys() {
    var cls = session.createClass("ConcurrentComponents");
    cls.createProperty("items", PropertyType.EMBEDDEDLIST, PropertyType.STRING).setCollate("ci");
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex("concurrentComponents", SchemaClass.INDEX_TYPE.NOTUNIQUE, "tag", "items");
    var index = session.getSharedContext().getIndexManager().getIndex("concurrentComponents");
    session.begin();
    var doc = session.newEntity("ConcurrentComponents");
    doc.setProperty("tag", "before");
    doc.newEmbeddedList("items", List.of("X", "x"));
    var rid = doc.getIdentity();
    session.commit();
    session.begin();
    var loaded = session.loadEntity(rid);
    loaded.setProperty("tag", "after");
    loaded.getEmbeddedList("items").remove(0);
    session.commit();
    session.begin();
    assertKeyCount(index, new CompositeKey("before", "x"), 0);
    assertKeyCount(index, new CompositeKey("after", "x"), 1);
    assertEquals(1, index.size(session));
    session.rollback();
  }

  /**
   * Every composite null-item and empty transition must match creation in both null modes,
   * including a null scalar component. Explicit assertions catch stale (t, null) entries.
   */
  @Test
  public void compositeNullAndEmptyEventsMatchCreation() {
    for (boolean ignored : List.of(false, true)) {
      for (String tag : Arrays.asList("t", null)) {
        String name = "NullComposite" + ignored + (tag == null ? "Null" : "Tag");
        var index = differentialIndex(name, "list", true, ignored);
        var rid = createDifferentialRecord(name, "list", tag, List.of("a"));
        mutateAndCompare(name, "list", tag, index, rid,
            doc -> doc.<String>getEmbeddedList("items").add(null));
        session.begin();
        assertKeyCount(index, new CompositeKey(tag, null), tag == null && ignored ? 0 : 1);
        session.rollback();
        mutateAndCompare(name, "list", tag, index, rid,
            doc -> doc.getEmbeddedList("items").remove(1));
        session.begin();
        assertKeyCount(index, new CompositeKey(tag, null), 0);
        session.rollback();
        mutateAndCompare(name, "list", tag, index, rid,
            doc -> doc.getEmbeddedList("items").clear());
        session.begin();
        assertKeyCount(index, new CompositeKey(tag, null), ignored ? 0 : 1);
        session.rollback();
        mutateAndCompare(name, "list", tag, index, rid,
            doc -> doc.<String>getEmbeddedList("items").add(null));
        mutateAndCompare(name, "list", tag, index, rid,
            doc -> doc.<String>getEmbeddedList("items").add("a"));
        mutateAndCompare(name, "list", tag, index, rid,
            doc -> doc.getEmbeddedList("items").remove(0));
        session.begin();
        session.loadEntity(rid).delete();
        session.commit();
        session.begin();
        assertEquals(0, index.size(session));
        session.rollback();
      }
    }
  }

  /** Single-field list, set and map events preserve folded duplicates and null/empty keys. */
  @Test
  public void singleCollectionEventsMatchCreation() {
    for (boolean ignored : List.of(false, true)) {
      for (String kind : List.of("list", "set", "mapKeys", "mapValues")) {
        String name = "Single" + kind + ignored;
        var index = differentialIndex(name, kind, false, ignored);
        var rid = createDifferentialRecord(name, kind, "t", List.of());
        for (int step = 0; step < 5; step++) {
          final int mutation = step;
          mutateAndCompare(name, kind, "t", index, rid, doc -> {
            if (kind.startsWith("map")) {
              var map = doc.<String>getEmbeddedMap("items");
              switch (mutation) {
                case 0 -> {
                  map.put("A", "X");
                  map.put("a", "x");
                  map.put("n", null);
                }
                case 1 -> map.remove("A");
                case 2 -> map.put("a", null);
                case 3 -> map.clear();
                default -> map.put("z", "z");
              }
            } else {
              var items = kind.equals("set") ? doc.<String>getEmbeddedSet("items")
                  : doc.<String>getEmbeddedList("items");
              switch (mutation) {
                case 0 -> {
                  items.add("A");
                  items.add("a");
                  items.add(null);
                  items.add(null);
                }
                case 1 -> items.remove("A");
                case 2 -> items.remove(null);
                case 3 -> items.clear();
                default -> items.add("z");
              }
            }
          });
        }
      }
    }
  }

  /** Composite item puts/removes use raw keys even when collation is not idempotent. */
  @Test
  public void compositeNonIdempotentEventsMatchCreation() {
    for (boolean ignored : List.of(false, true)) {
      String name = "CompositeCollation" + ignored;
      var index = differentialIndex(name, "list", true, ignored);
      var definition = (CompositeIndexDefinition) index.getDefinition();
      // CompositeCollate holds the component collations supplied when definitions are added.
      var component = definition.getIndexDefinitions().get(1);
      component.setCollate(new Collate() {
        @Override
        public String getName() {
          return "test-prefix-reverse";
        }

        @Override
        public Object transform(Object value) {
          return value instanceof String text ? "x" + new StringBuilder(text).reverse() : value;
        }
      });
      ((CompositeCollate) definition.getCollate()).getCollates().set(1, component.getCollate());
      var rid = createDifferentialRecord(name, "list", "t", List.of("az"));
      mutateAndCompare(name, "list", "t", index, rid,
          doc -> doc.<String>getEmbeddedList("items").add("ba"));
      mutateAndCompare(name, "list", "t", index, rid,
          doc -> doc.getEmbeddedList("items").remove("az"));
      mutateAndCompare(name, "list", "t", index, rid,
          doc -> doc.getEmbeddedList("items").remove("ba"));
    }
  }

  /**
   * Small fixed-seed batches cross 0/1/2 boundaries with folded and shortened duplicates.
   * Each committed batch must equal creation for list and map, single and composite indexes.
   */
  @Test
  public void randomizedCollectionEventsMatchCreation() {
    for (String kind : List.of("list", "mapValues")) {
      for (boolean composite : List.of(false, true)) {
        for (boolean ignored : List.of(false, true)) {
          String name = "Random" + kind + composite + ignored;
          var index = differentialIndex(name, kind, composite, ignored);
          var rid = createDifferentialRecord(name, kind, "t", List.of());
          var random = new Random(1543);
          // Composite keys retain the storage size limit. Shortening applies to single fields.
          String prefix = "long".repeat(composite ? 20 : 3000);
          var values = Arrays.asList("A", "a", "B", "b", null, prefix + "a", prefix + "b");
          for (int batch = 0; batch < 50; batch++) {
            mutateAndCompare(name, kind, "t", index, rid, doc -> {
              if (random.nextInt(5) == 0) {
                doc.setProperty("unrelated", random.nextInt());
              }
              for (int operation = 0, count = 1 + random.nextInt(6); operation < count;
                  operation++) {
                String value = values.get(random.nextInt(values.size()));
                int action = random.nextInt(4);
                if (kind.startsWith("map")) {
                  var map = doc.<String>getEmbeddedMap("items");
                  String key = "k" + random.nextInt(4);
                  switch (action) {
                    case 0, 1 -> map.put(key, value);
                    case 2 -> map.remove(key);
                    default -> map.clear();
                  }
                  // YTDB-1558: replace over a null old value and view removals are excluded.
                } else {
                  var items = doc.<String>getEmbeddedList("items");
                  if (items.isEmpty() || (action == 0 && items.size() < 4)) {
                    items.add(value);
                  } else if (action == 1) {
                    items.remove(random.nextInt(items.size()));
                  } else if (action == 3) {
                    items.clear();
                  } else {
                    int position = random.nextInt(items.size());
                    // YTDB-1558: set over null emits no event, so use tracked remove/add.
                    if (items.get(position) == null) {
                      items.remove(position);
                      items.add(position, value);
                    } else {
                      items.set(position, value);
                    }
                  }
                }
              }
              // YTDB-1558: no reversed views or sort with a dirty index component.
              // YTDB-1558: no in-place change followed by assignment of a new instance.
              // YTDB-1558: no non-collection assignment with ignored null items.
              // YTDB-1558: link-map entry mutation and bag secondary overwrite are not generated.
            });
          }
        }
      }
    }
  }

  /** Exhaustive size 0..3 creation oracle calls the production null rule for every definition. */
  @Test
  public void collectionNullRuleEqualsDefinitionCreation() {
    for (String kind : List.of("list", "set", "mapKeys", "mapValues", "bag", "secondaryBag")) {
      for (boolean ignored : List.of(false, true)) {
        for (int position : List.of(-1, 0, 1)) {
          String name = "Oracle" + kind + ignored + (position + 1);
          var cls = session.createClass(name);
          boolean bagKind = kind.endsWith("Bag") || kind.equals("bag");
          var type = bagKind ? PropertyType.LINKBAG : kind.startsWith("map")
              ? PropertyType.EMBEDDEDMAP : kind.equals("set")
                  ? PropertyType.EMBEDDEDSET : PropertyType.EMBEDDEDLIST;
          if (bagKind) {
            cls.createProperty("items", type);
          } else {
            cls.createProperty("items", type, PropertyType.STRING).setCollate("ci");
          }
          cls.createProperty("tag", PropertyType.STRING);
          String field = kind.equals("mapKeys") ? "items by key"
              : kind.equals("mapValues") || kind.equals("secondaryBag")
                  ? "items by value" : "items";
          cls.createIndex(name, "NOTUNIQUE", null, Map.of("ignoreNullValues", ignored),
              position < 0 ? new String[] {field} : position == 0
                  ? new String[] {field, "tag"} : new String[] {"tag", field});
          var index = session.getSharedContext().getIndexManager().getIndex(name);
          IndexDefinition definition = index.getDefinition();
          var component = (IndexDefinitionMultiValue) (position < 0 ? definition
              : ((CompositeIndexDefinition) definition).getIndexDefinitions().get(position));
          for (int size = 0; size <= 3; size++) {
            int combinations = (int) Math.pow(bagKind ? 3 : 4, size);
            for (int encoded = 0; encoded < combinations; encoded++) {
              var values = new ArrayList<Object>();
              int remaining = encoded;
              for (int i = 0; i < size; i++) {
                int digit = remaining % (bagKind ? 3 : 4);
                remaining /= bagKind ? 3 : 4;
                values.add(bagKind
                    ? com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal
                        .fromString("#1:" + (12 + digit), false)
                    : Arrays.asList(null, "a", "A", "b").get(digit));
              }
              if ((kind.equals("set") || kind.equals("mapKeys"))
                  && new HashSet<>(values).size() != size) {
                continue;
              }
              if (kind.equals("mapKeys") && values.contains(null)) {
                continue;
              }
              session.begin();
              var doc = (EntityImpl) session.newEntity();
              doc.setProperty("tag", "t");
              if (kind.startsWith("map")) {
                var map = new java.util.LinkedHashMap<String, Object>();
                for (int i = 0; i < size; i++) {
                  map.put(kind.equals("mapKeys") ? (String) values.get(i) : "k" + i,
                      values.get(i));
                }
                doc.newEmbeddedMap("items", map);
              } else if (bagKind) {
                var bag =
                    new com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag(session);
                for (var value : values) {
                  bag.add((RID) value, (RID) value);
                }
                doc.setProperty("items", bag);
              } else if (kind.equals("set")) {
                doc.newEmbeddedSet("items", new HashSet<>(values));
              } else {
                doc.newEmbeddedList("items", values);
              }
              var expected = new HashSet<Object>();
              int nullCount = 0;
              for (var value : values) {
                Object converted =
                    component.createSingleValue(session.getActiveTransaction(), value);
                if (converted == null) {
                  nullCount++;
                } else {
                  expected.add(index.getCollatingValue(oracleWrap(position, converted)));
                }
              }
              if (ClassIndexManager.collectionNullKeyPresent(position >= 0, ignored,
                  component instanceof PropertyMapIndexDefinition, size, nullCount)) {
                expected.add(index.getCollatingValue(oracleWrap(position, null)));
              }
              Object created =
                  definition.getDocumentValueToIndex(session.getActiveTransaction(), doc);
              var actual = new HashSet<Object>();
              var keys = created instanceof java.util.Collection<?> collection
                  ? collection : Collections.singleton(created);
              for (var key : keys) {
                if (key != null || !ignored) {
                  actual.add(index.getCollatingValue(key));
                }
              }
              assertEquals(name + " values " + values, actual, expected);
              session.rollback();
            }
          }
        }
      }
    }
  }

  private Object oracleWrap(int position, Object value) {
    return position < 0 ? value : position == 0
        ? new CompositeKey(value, "t") : new CompositeKey("t", value);
  }

  /** An unrelated dirty field must not route a sorted list through index-based rollback. */
  @Test
  public void unrelatedDirtyPropertyKeepsCollectionEventPath() {
    var index = differentialIndex("UnrelatedDirty", "list", true, false);
    var rid = createDifferentialRecord("UnrelatedDirty", "list", "t", List.of("c", "a", "b"));
    mutateAndCompare("UnrelatedDirty", "list", "t", index, rid, doc -> {
      var items = doc.<String>getEmbeddedList("items");
      items.add("d");
      items.sort(Collections.reverseOrder());
      doc.setProperty("unrelated", 1);
      // YTDB-1558: sort is safe here only because no other index component is dirty.
    });
  }

  /** Composite maps and sets cross every small-size boundary and retain creation parity. */
  @Test
  public void compositeMapAndSetBoundaryMatrix() {
    for (String kind : List.of("mapKeys", "mapValues", "set")) {
      for (boolean ignored : List.of(false, true)) {
        String name = "Boundary" + kind + ignored;
        var index = differentialIndex(name, kind, true, ignored);
        var rid = createDifferentialRecord(name, kind, "t", List.of());
        for (int step = 0; step < 9; step++) {
          int action = step;
          mutateAndCompare(name, kind, "t", index, rid, doc -> {
            doc.setProperty("unrelated", action);
            if (kind.startsWith("map")) {
              var map = doc.<String>getEmbeddedMap("items");
              switch (action) {
                case 0 -> map.put("a", null);
                case 1 -> map.put("b", "B");
                case 2 -> map.put("c", "b");
                case 3 -> map.remove("c");
                case 4 -> map.remove("b");
                case 5 -> map.put("a", "A");
                case 6 -> map.remove("a");
                case 7 -> {
                  map.put("x", "X");
                  map.put("y", null);
                }
                default -> map.clear();
              }
            } else {
              var set = doc.<String>getEmbeddedSet("items");
              switch (action) {
                case 0 -> set.add(null);
                case 1 -> set.add("B");
                case 2 -> set.add("b");
                case 3 -> set.remove("b");
                case 4 -> set.remove("B");
                case 5 -> {
                  set.remove(null);
                  set.add("A");
                }
                case 6 -> set.remove("A");
                case 7 -> {
                  set.add("X");
                  set.add(null);
                }
                default -> set.clear();
              }
            }
          });
        }
        mutateAndCompare(name, kind, "u", index, rid, doc -> {
          doc.setProperty("tag", "u");
          if (kind.startsWith("map")) {
            doc.<String>getEmbeddedMap("items").put("a", "A");
          } else {
            doc.<String>getEmbeddedSet("items").add("A");
          }
        });
        session.begin();
        var doc = session.loadEntity(rid);
        if (kind.startsWith("map")) {
          doc.getEmbeddedMap("items").clear();
        } else {
          doc.getEmbeddedSet("items").clear();
        }
        doc.delete();
        session.commit();
        session.begin();
        assertEquals(0, index.size(session));
        session.rollback();
        var committed = createDifferentialRecord(name, kind, "t", List.of());
        session.begin();
        session.loadEntity(committed).delete();
        session.commit();
        session.begin();
        assertEquals(0, index.size(session));
        session.rollback();
      }
    }
  }

  /** D1: a singleton map value changed to null has no composite key with ignored nulls. */
  @Test
  public void d1SingletonMapValueToNull() {
    mapRegression(1, Map.of("a", "a"), map -> map.put("a", null), true);
  }

  /** D2: growing a singleton null map restores the previously suppressed null key. */
  @Test
  public void d2SingletonNullMapGrows() {
    mapRegression(2, Collections.singletonMap("a", null), map -> map.put("b", "x"), true);
  }

  /** D3: shrinking to a singleton null map removes both old keys. */
  @Test
  public void d3MapShrinksToSingletonNull() {
    var initial = new java.util.HashMap<String, String>();
    initial.put("a", null);
    initial.put("b", "x");
    mapRegression(3, initial, map -> map.remove("b"), true);
  }

  /** D4: an empty map gains a null entry but still has no ignored-null composite key. */
  @Test
  public void d4EmptyMapGainsNull() {
    mapRegression(4, Map.of(), map -> map.put("a", null), true);
  }

  /** D5: two null entries shrink to one, suppressing the composite null key. */
  @Test
  public void d5TwoNullEntriesShrink() {
    var initial = new java.util.HashMap<String, String>();
    initial.put("a", null);
    initial.put("b", null);
    mapRegression(5, initial, map -> map.remove("b"), true);
  }

  /** D6: replace on an absent map key must not hide the final empty-collection key. */
  @Test
  public void d6PhantomMapAddThenRemove() {
    mapRegression(6, Map.of("a", "x"), map -> {
      map.replace("zz", "v");
      map.remove("a");
    }, false);
  }

  private void mapRegression(int number, Map<String, String> initial,
      Consumer<Map<String, String>> mutation, boolean ignored) {
    String name = "MapRegression" + number;
    var index = differentialIndex(name, "mapValues", true, ignored);
    var rid = createDifferentialRecord(name, "mapValues", "t", initial);
    mutateAndCompare(name, "mapValues", "t", index, rid,
        doc -> mutation.accept(doc.getEmbeddedMap("items")));
    session.begin();
    Set<Object> expected = number == 2
        ? Set.of(new CompositeKey("t", null), new CompositeKey("t", "x"))
        : number == 6 ? Set.of(new CompositeKey("t", null)) : Set.of();
    assertEquals(expected, keysFor(index, rid));
    session.rollback();
  }

  private Index differentialIndex(String name, String kind, boolean composite, boolean ignored) {
    var cls = session.createClass(name);
    var type = kind.startsWith("map") ? PropertyType.EMBEDDEDMAP
        : kind.equals("set") ? PropertyType.EMBEDDEDSET : PropertyType.EMBEDDEDLIST;
    cls.createProperty("items", type, PropertyType.STRING).setCollate("ci");
    cls.createProperty("tag", PropertyType.STRING);
    String field = kind.equals("mapKeys") ? "items by key"
        : kind.equals("mapValues") ? "items by value" : "items";
    cls.createIndex(name, "NOTUNIQUE", null, Map.of("ignoreNullValues", ignored),
        composite ? new String[] {"tag", field} : new String[] {field});
    return session.getSharedContext().getIndexManager().getIndex(name);
  }

  private RID createDifferentialRecord(String name, String kind, String tag, Object values) {
    session.begin();
    var doc = session.newEntity(name);
    doc.setProperty("tag", tag);
    if (kind.startsWith("map")) {
      @SuppressWarnings("unchecked")
      var map = values instanceof Map ? (Map<String, String>) values : Map.<String, String>of();
      doc.newEmbeddedMap("items", map);
    } else {
      @SuppressWarnings("unchecked")
      var items = (java.util.Collection<String>) values;
      if (kind.equals("set")) {
        doc.newEmbeddedSet("items", new HashSet<>(items));
      } else {
        doc.newEmbeddedList("items", items);
      }
    }
    var rid = doc.getIdentity();
    session.commit();
    return rid;
  }

  private void mutateAndCompare(String name, String kind, String tag, Index index, RID rid,
      Consumer<Entity> mutation) {
    session.begin();
    var doc = session.loadEntity(rid);
    mutation.accept(doc);
    Object values = kind.startsWith("map") ? new java.util.HashMap<>(doc.getEmbeddedMap("items"))
        : kind.equals("set") ? new HashSet<>(doc.getEmbeddedSet("items"))
            : new ArrayList<>(doc.getEmbeddedList("items"));
    session.commit();
    var fresh = createDifferentialRecord(name, kind, tag, values);
    session.begin();
    assertEquals(name + " final values " + values, keysFor(index, fresh), keysFor(index, rid));
    session.loadEntity(fresh).delete();
    session.commit();
  }

  private Set<Object> keysFor(Index index, RID rid) {
    var keys = new HashSet<Object>();
    try (var entries = index.stream(session)) {
      entries.filter(entry -> entry.second().equals(rid)).forEach(entry -> keys.add(entry.first()));
    }
    // Full index streams omit the null bucket.
    try (var nulls = index.getRids(session, null)) {
      if (nulls.anyMatch(rid::equals)) {
        keys.add(null);
      }
    }
    return keys;
  }

  private void assertKeyCount(Index index, Object key, long expected) {
    try (var rids = index.getRids(session, key)) {
      assertEquals(expected, rids.count());
    }
  }

  private static final String CLS = "CimTestCls";
  private static final String IDX = CLS + ".name";

  @Override
  @Before
  public void beforeTest() throws Exception {
    super.beforeTest();
    // Schema setup must happen outside an active transaction.
    var cls = session.getMetadata().getSchema().createClass(CLS);
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex(IDX, SchemaClass.INDEX_TYPE.UNIQUE, "name");
  }

  // -----------------------------------------------------------------------
  //  checkIndexesAfterCreate (called indirectly on record insert + commit)
  // -----------------------------------------------------------------------

  /**
   * Inserting a record with an indexed property and committing must add an index entry.
   * Exercises ClassIndexManager.checkIndexesAfterCreate → addIndexesEntries.
   */
  @Test
  public void checkIndexesAfterCreate_indexedPropertySet_entryAddedToIndex() {
    session.begin();
    var e = session.newEntity(CLS);
    e.setProperty("name", "Alice");
    session.commit();

    session.begin();
    var idx = session.getSharedContext().getIndexManager().getIndex(IDX);
    // IndexOneValue.get returns the RID when found, null otherwise.
    assertNotNull("Index must contain the newly inserted key",
        idx.get(session, "Alice"));
    session.rollback();
  }

  /**
   * Inserting a record whose indexed property is left null must not throw; the null-values
   * guard in ClassIndexManager skips the put when nullValuesIgnored is true (default).
   */
  @Test
  public void checkIndexesAfterCreate_nullProperty_doesNotThrow() {
    // No 'name' set → null key → default nullValuesIgnored=true skips the index put.
    session.begin();
    session.newEntity(CLS);
    session.commit(); // must complete without exception
  }

  // -----------------------------------------------------------------------
  //  checkIndexesAfterUpdate (called indirectly on record update + commit)
  // -----------------------------------------------------------------------

  /**
   * Updating an indexed property must replace the old key with the new key in the index.
   * Exercises ClassIndexManager.checkIndexesAfterUpdate → processIndexOnUpdate →
   * processSingleIndexUpdate → addPut + addRemove paths.
   */
  @Test
  public void checkIndexesAfterUpdate_indexedPropertyChanged_oldKeyRemovedNewKeyAdded() {
    session.begin();
    var e = session.newEntity(CLS);
    e.setProperty("name", "Before");
    session.commit();

    session.begin();
    session.execute("UPDATE " + CLS + " SET name = 'After' WHERE name = 'Before'").close();
    session.commit();

    session.begin();
    var idx = session.getSharedContext().getIndexManager().getIndex(IDX);
    assertNull("Old key 'Before' must be removed from the index after update",
        idx.get(session, "Before"));
    assertNotNull("New key 'After' must be present in the index after update",
        idx.get(session, "After"));
    session.rollback();
  }

  // -----------------------------------------------------------------------
  //  checkIndexesAfterDelete (called indirectly on record delete + commit)
  // -----------------------------------------------------------------------

  /**
   * Deleting a record that has an indexed property must remove that key from the index.
   * Exercises ClassIndexManager.checkIndexesAfterDelete → processIndexOnDelete.
   */
  @Test
  public void checkIndexesAfterDelete_indexedRecord_entryRemovedFromIndex() {
    session.begin();
    var e = session.newEntity(CLS);
    e.setProperty("name", "ToDelete");
    session.commit();

    session.begin();
    session.execute("DELETE FROM " + CLS + " WHERE name = 'ToDelete'").close();
    session.commit();

    session.begin();
    var idx = session.getSharedContext().getIndexManager().getIndex(IDX);
    assertNull("Deleted record's key must not remain in the index",
        idx.get(session, "ToDelete"));
    session.rollback();
  }

  // -----------------------------------------------------------------------
  //  reIndex
  // -----------------------------------------------------------------------

  /**
   * reIndex must re-add the entity's current indexed value to the given index. This is used when
   * rebuilding an index from existing records; the test verifies that calling reIndex inside a
   * transaction results in a queryable index entry after commit.
   */
  @Test
  public void reIndex_existingEntity_indexEntryRestoredAfterCommit() {
    session.begin();
    var e = (EntityImpl) session.newEntity(CLS);
    e.setProperty("name", "ReIndexMe");
    session.commit();

    var idx = session.getSharedContext().getIndexManager().getIndex(IDX);

    // Remove the entry manually inside a TX, then call reIndex to restore it, then commit.
    session.begin();
    var tx = session.getActiveTransaction(); // FrontendTransactionImpl
    e = tx.load(e); // reload to get the TX-attached instance
    idx.remove(tx, "ReIndexMe");
    // After removal, reIndex puts it back via ClassIndexManager.addIndexEntry.
    ClassIndexManager.reIndex(tx, e, idx);
    session.commit();

    session.begin();
    assertNotNull("reIndex must restore the index entry for the entity",
        idx.get(session, "ReIndexMe"));
    session.rollback();
  }

  // -----------------------------------------------------------------------
  //  addIndexesEntries (public static helper)
  // -----------------------------------------------------------------------

  /**
   * addIndexesEntries with a single-element list must add the entity's indexed property to the
   * given index within the current transaction, exercising the collection-iteration path.
   */
  @Test
  public void addIndexesEntries_singleEntity_entryAddedInTx() {
    session.begin();
    var e = (EntityImpl) session.newEntity(CLS);
    e.setProperty("name", "BatchAdd");
    session.commit();

    var idx = session.getSharedContext().getIndexManager().getIndex(IDX);

    session.begin();
    var tx = session.getActiveTransaction();
    e = tx.load(e);
    idx.remove(tx, "BatchAdd");
    ClassIndexManager.addIndexesEntries(tx, e, Collections.singletonList(idx));
    session.commit();

    session.begin();
    assertNotNull("addIndexesEntries must have added the key back to the index",
        idx.get(session, "BatchAdd"));
    session.rollback();
  }
}
