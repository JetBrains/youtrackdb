package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Counts entries for distinct requests in stored, pending and cleared index views. */
@RunWith(Parameterized.class)
@Category(SequentialTest.class)
public class RequestedIndexKeysTest extends DbTestBase {

  @Parameterized.Parameters(name = "{0}_{1}")
  public static Object[][] parameters() {
    return new Object[][] {
        {SchemaClass.INDEX_TYPE.UNIQUE, "stored"},
        {SchemaClass.INDEX_TYPE.UNIQUE, "pending"},
        {SchemaClass.INDEX_TYPE.UNIQUE, "cleared"},
        {SchemaClass.INDEX_TYPE.NOTUNIQUE, "stored"},
        {SchemaClass.INDEX_TYPE.NOTUNIQUE, "pending"},
        {SchemaClass.INDEX_TYPE.NOTUNIQUE, "cleared"}
    };
  }

  private final SchemaClass.INDEX_TYPE type;
  private final String state;

  public RequestedIndexKeysTest(SchemaClass.INDEX_TYPE type, String state) {
    this.type = type;
    this.state = state;
  }

  /** Repeated scalar requests include each RID once, with no global RID deduplication. */
  @Test
  public void repeatedStrings() {
    check(PropertyType.STRING, false, List.of("a", "b"),
        List.of("b", "a", "a", "b"), null);
  }

  /** Case-folded requests count as one key and preserve folded key order. */
  @Test
  public void caseInsensitiveStrings() {
    check(PropertyType.STRING, true, List.of("Ada", "Zoe"),
        List.of("ZOE", "Ada", "ada", "zoe"), null);
  }

  /** Separate binary objects with equal contents are the same index key. */
  @Test
  public void equalBinaryContents() {
    check(PropertyType.BINARY, false, List.of(new byte[] {1}, new byte[] {2}),
        List.of(new byte[] {2}, new byte[] {1}, new byte[] {1}, new byte[] {2}), null);
  }

  /** Decimal scale differences do not repeat an index key. */
  @Test
  public void equalDecimals() {
    check(PropertyType.DECIMAL, false, List.of(new BigDecimal("1.0"), new BigDecimal("2.0")),
        List.of(new BigDecimal("2.00"), new BigDecimal("1.00"),
            new BigDecimal("1.0"), new BigDecimal("2.0")),
        null);
  }

  /** Null requests form one key and remain ordered before non-null keys. */
  @Test
  public void repeatedNull() {
    for (boolean ascending : new boolean[] {true, false}) {
      var keys = Arrays.asList(null, new CompositeKey((Object) null), null,
          new CompositeKey((Object) null));
      assertEquals("wrapped null must remain a separate request", 2,
          RequestedIndexKeys.distinctSorted(keys, key -> key, ascending).size());
    }
    check(PropertyType.STRING, false, Arrays.asList(null, "a"),
        Arrays.asList("a", null, null, "a"), null);
  }

  /** Repeated null shapes match distinct single-key lookups in both directions. */
  @Test
  public void repeatedWrappedNullUnique() {
    assumeTrue(type == SchemaClass.INDEX_TYPE.UNIQUE);
    var clazz = session.createClass("RequestedNullKeys");
    clazz.createProperty("p", PropertyType.STRING);
    clazz.createIndex("RequestedNullKeys.idx", type, "p");
    session.createClass("LookupRecord");
    var index = session.getSharedContext().getIndexManager().getIndex("RequestedNullKeys.idx");
    assertTrue("exercise the single-value stream implementation", index instanceof IndexOneValue);
    session.begin();
    var rid = session.newEntity("LookupRecord").getIdentity();
    session.commit();
    if (!state.equals("pending")) {
      session.begin();
      index.put(session.getTransactionInternal(), null, rid);
      session.commit();
    }
    session.begin();
    if (!state.equals("stored")) {
      var tx = session.getTransactionInternal();
      if (state.equals("cleared")) {
        tx.addIndexEntry(index, index.getName(), OPERATION.CLEAR, null, null);
      }
      rid = session.newEntity("LookupRecord").getIdentity();
      // Keep every pending key null to avoid the mixed-type map failure in YTDB-1469.
      index.put(tx, null, rid);
    }
    var wrapped = new CompositeKey((Object) null);
    var requests = List.of(Arrays.asList(null, null), Arrays.asList(wrapped, wrapped),
        Arrays.asList(null, wrapped, null, wrapped));
    // YTDB-1469 is a non-goal. Pending lookups may distinguish null from wrapped null.
    // Sum distinct single-key results for pending R2/R3 without pinning that distinction.
    // The pending fixture leaves null absent from storage to avoid a stale stored RID.
    var storedCounts = List.of(1L, 1L, 2L);
    for (boolean ascending : new boolean[] {true, false}) {
      for (int i = 0; i < requests.size(); i++) {
        var context = state + ", request " + (i + 1) + ", ascending=" + ascending;
        var expected = new HashMap<RID, Long>();
        if (state.equals("stored") || i == 0) {
          expected.put(rid, state.equals("stored") ? storedCounts.get(i) : 1L);
        } else {
          var shapes = i == 1 ? Collections.singletonList(wrapped) : Arrays.asList(null, wrapped);
          long naiveCount = 0;
          for (var shape : shapes) {
            try (var single = index.streamEntries(session,
                Collections.singletonList(shape), ascending)) {
              var entries = single.toList();
              entries.forEach(entry -> expected.merge(entry.second(), 1L, Long::sum));
              naiveCount += (long) entries.size() * Collections.frequency(requests.get(i), shape);
            }
          }
          long oracleCount = expected.values().stream().mapToLong(Long::longValue).sum();
          if (oracleCount > 0) {
            assertTrue(context + " oracle must detect repeated requests", oracleCount < naiveCount);
          }
          // Empty single-key results cannot expose repeats. Stored R2 covers that shape exactly.
        }
        try (var stream = index.streamEntries(session, requests.get(i), ascending)) {
          var entries = stream.toList();
          assertEquals(context + " total entry count",
              expected.values().stream().mapToLong(Long::longValue).sum(), entries.size());
          var actual = new HashMap<RID, Long>();
          entries.forEach(entry -> actual.merge(entry.second(), 1L, Long::sum));
          assertEquals(context + " count for every requested key RID", expected, actual);
        }
      }
    }
    session.rollback();
  }

  /** Equal-length composite requests compare their folded parts rather than Java equality. */
  @Test
  public void caseInsensitiveCompositeParts() {
    check(PropertyType.STRING, true,
        List.of(new CompositeKey("Ada", "One"), new CompositeKey("Zoe", "Two")),
        List.of(new CompositeKey("ZOE", "TWO"), new CompositeKey("Ada", "One"),
            new CompositeKey("ada", "one")),
        null);
  }

  /** A longer composite between prefix copies must not hide a repeated prefix request. */
  @Test
  public void interleavedCompositePrefixes() {
    if (type == SchemaClass.INDEX_TYPE.UNIQUE) {
      // YTDB-1469: unique pending prefix lookup disagrees with storage and is a non-goal.
      assumeTrue(state.equals("stored"));
      // Single-value storage lookups require a full composite key, not a prefix.
      check(PropertyType.STRING, false, List.of(new CompositeKey("x", "y")),
          List.of(new CompositeKey("x", "y"), new CompositeKey("x"),
              new CompositeKey("x", "y")),
          List.of(1));
      return;
    }
    var prefix = new CompositeKey("x");
    // YTDB-1468: pending prefix reads support only one matching pending entry.
    // Stored-only and mixed views still exercise two stored entries under the prefix.
    var stored = state.equals("cleared")
        ? List.<Object>of(new CompositeKey("x", "y"))
        : List.<Object>of(new CompositeKey("x", "y"), new CompositeKey("x", "z"));
    check(PropertyType.STRING, false, stored,
        List.of(prefix, new CompositeKey("x", "y"), new CompositeKey("x")),
        state.equals("cleared") ? List.of(2) : List.of(2, 1));
  }

  /** Both streams use a single collation pass even when collation is not idempotent. */
  @Test
  public void nonIdempotentCollation() {
    var collate = new CountingCollate();
    check(PropertyType.STRING, false, List.of("az", "ba"),
        List.of("az", "ba", "az"), collate);
  }

  /** Repeated full composite keys are removed in both unique and non-unique indexes. */
  @Test
  public void repeatedFullCompositeKeys() {
    check(PropertyType.STRING, false,
        List.of(new CompositeKey("a", "b"), new CompositeKey("c", "d")),
        List.of(new CompositeKey("a", "b"), new CompositeKey("c", "d"),
            new CompositeKey("a", "b")),
        null);
  }

  private void check(PropertyType propertyType, boolean ci, List<Object> stored,
      List<Object> requests, Object special) {
    if (type == SchemaClass.INDEX_TYPE.NOTUNIQUE && !(special instanceof List<?>)) {
      var multipleValues = new ArrayList<>(stored);
      multipleValues.addAll(stored);
      stored = multipleValues;
    }
    var clazz = session.createClass("RequestedKeys");
    boolean composite = stored.getFirst() instanceof CompositeKey;
    var property = clazz.createProperty("p", propertyType);
    if (ci) {
      property.setCollate("ci");
    }
    if (composite) {
      var second = clazz.createProperty("q", propertyType);
      if (ci) {
        second.setCollate("ci");
      }
    }
    clazz.createIndex("RequestedKeys.idx", type,
        composite ? new String[] {"p", "q"} : new String[] {"p"});
    session.createClass("LookupRecord");
    var index = session.getSharedContext().getIndexManager().getIndex("RequestedKeys.idx");
    if (special instanceof CountingCollate collate) {
      index.getDefinition().setCollate(collate);
    }
    var rids = new ArrayList<RID>();
    session.begin();
    // Values have no indexed properties. Direct puts isolate requested-key behavior.
    for (int i = 0; i < stored.size(); i++) {
      rids.add(session.newEntity("LookupRecord").getIdentity());
    }
    session.commit();
    session.begin();
    for (int i = 0; i < stored.size(); i++) {
      index.put(session.getTransactionInternal(), stored.get(i), rids.get(i));
    }
    session.commit();
    session.begin();
    if (!state.equals("stored")) {
      var tx = session.getTransactionInternal();
      if (state.equals("cleared")) {
        tx.addIndexEntry(index, index.getName(), OPERATION.CLEAR, null, null);
        rids.clear();
        for (var key : stored) {
          var rid = session.newEntity("LookupRecord").getIdentity();
          rids.add(rid);
          index.put(tx, key, rid);
        }
      } else {
        // Replace one RID and retain the others to exercise both sides of the merge.
        // No pending put repeats a committed RID under the same key.
        index.remove(tx, stored.getFirst(), rids.getFirst());
        var replacement = session.newEntity("LookupRecord").getIdentity();
        rids.set(0, replacement);
        index.put(tx, stored.getFirst(), replacement);
      }
    }
    var expected = new HashMap<RID, Long>();
    for (int i = 0; i < rids.size(); i++) {
      int count = special instanceof List<?> counts ? (Integer) counts.get(i) : 1;
      if (count > 0) {
        expected.put(rids.get(i), (long) count);
      }
    }
    for (boolean ascending : new boolean[] {true, false}) {
      if (special instanceof CountingCollate collate) {
        collate.calls = 0;
      }
      try (var stream = index.streamEntries(session, requests, ascending)) {
        var entries = stream.toList();
        assertEquals("total entry count",
            expected.values().stream().mapToLong(Long::longValue).sum(),
            entries.size());
        var actual = new HashMap<RID, Long>();
        entries.forEach(entry -> actual.merge(entry.second(), 1L, Long::sum));
        assertEquals("count for every requested key RID", expected, actual);
        var ordered = new ArrayList<>(entries.stream().map(RawPair::first).toList());
        ordered.sort(ascending ? DefaultComparator.INSTANCE
            : Collections.reverseOrder(DefaultComparator.INSTANCE));
        assertEquals("key order", ordered, entries.stream().map(RawPair::first).toList());
      }
      if (special instanceof CountingCollate collate) {
        assertEquals("one transform per raw request", requests.size(), collate.calls);
      }
    }
    session.rollback();
  }

  private static final class CountingCollate implements Collate {

    private int calls;

    @Override
    public String getName() {
      return "counting-reverse";
    }

    @Override
    public Object transform(Object value) {
      if (value instanceof String string) {
        calls++;
        return "x" + new StringBuilder(string).reverse();
      }
      return value;
    }
  }
}
