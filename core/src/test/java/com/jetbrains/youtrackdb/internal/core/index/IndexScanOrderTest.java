package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Checks index order and row content against fixture rows collected without an index. */
@RunWith(Parameterized.class)
@Category(SequentialTest.class)
public class IndexScanOrderTest extends DbTestBase {

  private static final String CLASS_NAME = "ScanOrder";
  private static final String INDEX_NAME = CLASS_NAME + ".p";
  // Raw uppercase keys reverse their relative order after case folding.
  // The request also includes absent keys and excludes committed keys.
  private static final List<String> REQUESTED = List.of("Z", "M", "d", "b", "f", "a", "absent");

  @Parameterized.Parameters(name = "{0}_ci_{1}")
  public static Object[][] parameters() {
    return new Object[][] {
        {SchemaClass.INDEX_TYPE.UNIQUE, false},
        {SchemaClass.INDEX_TYPE.NOTUNIQUE, false},
        {SchemaClass.INDEX_TYPE.UNIQUE, true},
        {SchemaClass.INDEX_TYPE.NOTUNIQUE, true}
    };
  }

  private final SchemaClass.INDEX_TYPE type;
  private final boolean caseInsensitive;
  private final Map<String, Row> rows = new LinkedHashMap<>();
  private Index index;

  public IndexScanOrderTest(SchemaClass.INDEX_TYPE type, boolean caseInsensitive) {
    this.type = type;
    this.caseInsensitive = caseInsensitive;
  }

  @Override
  @Before
  public void beforeTest() throws Exception {
    super.beforeTest();
    var clazz = session.createClass(CLASS_NAME);
    var property = clazz.createProperty("p", PropertyType.STRING);
    if (caseInsensitive) {
      property.setCollate("ci");
    }
    clazz.createIndex(INDEX_NAME, type, "p");
    session.begin();
    var keys = new ArrayList<>(List.of("d", "M", "t", "Z", "u"));
    if (type == SchemaClass.INDEX_TYPE.NOTUNIQUE) {
      keys.add("M");
    }
    for (int i = 0; i < keys.size(); i++) {
      insert("r" + i, keys.get(i));
    }
    session.commit();
    session.begin();
    rows.clear();
    // A class iterator reads records directly. It does not use an index as the oracle.
    var records = session.browseClass(CLASS_NAME);
    while (records.hasNext()) {
      var entity = records.next();
      String tag = entity.getProperty("tag");
      rows.put(tag, new Row(entity.getIdentity(), entity.getProperty("p"), tag));
    }
    index = session.getSharedContext().getIndexManager().getIndex(INDEX_NAME);
  }

  /** Clean full scans preserve key order and every fixture row in both directions. */
  @Test
  public void cleanFullScansMatchFixture() {
    assertFullScans();
  }

  /** Clean two-bound ranges filter fixture rows and preserve order in both directions. */
  @Test
  public void cleanTwoBoundRangesMatchFixture() {
    assertRanges();
  }

  /** Requested-key scans sort collated keys, not raw requests, in both directions. */
  @Test
  public void cleanRequestedKeysMatchCollatedFixture() {
    assertRequested();
  }

  /** Descending requested-key scans use folded key order even without pending changes. */
  @Test
  public void cleanDescendingRequestedKeysMatchCollatedFixture() {
    var keys = REQUESTED.stream().map(this::collated).toList();
    assertIndexRows(index.streamEntries(session, REQUESTED, false),
        expected(row -> keys.contains(collated(row.key())), false));
  }

  /** A pending lower-key insert merges after higher stored keys in descending scans. */
  @Test
  public void pendingLowerInsertMatchesFixtureInAllScans() {
    insert("inserted", "b");
    assertAllScans();
  }

  /** A pending update to a lower key removes the old entry and keeps the new row ordered. */
  @Test
  public void pendingLowerUpdateMatchesFixtureInAllScans() {
    update("r2", "f");
    assertAllScans();
  }

  /** Pending deletes remove rows without changing the order of surviving rows. */
  @Test
  public void pendingDeleteMatchesFixtureInAllScans() {
    delete("r0");
    assertAllScans();
  }

  /** Inserts, lower-key updates and deletes merge together without missing or repeated rows. */
  @Test
  public void pendingCombinationMatchesFixtureInAllScans() {
    insert("inserted", "b");
    update("r2", "f");
    delete("r0");
    assertAllScans();
  }

  /** A two-bound range merges lower pending keys and filters deleted rows in both directions. */
  @Test
  public void pendingTwoBoundRangesMatchFixture() {
    insert("inserted", "b");
    update("r2", "f");
    delete("r0");
    assertRanges();
  }

  /** Pending requested entries and stored collated entries share one order in both directions. */
  @Test
  public void pendingRequestedKeysMatchCollatedFixture() {
    insert("inserted", "b");
    update("r2", "f");
    delete("r0");
    assertRequested();
  }

  /** A cleared index emits only pending entries in key order, including requested-key scans. */
  @Test
  public void clearedIndexPendingOnlyScansMatchFixture() {
    var tx = session.getTransactionInternal();
    tx.addIndexEntry(index, INDEX_NAME, OPERATION.CLEAR, null, null);
    rows.keySet().retainAll(List.of("r1", "r0", "r3"));
    for (var tag : List.of("r1", "r0", "r3")) {
      var row = rows.get(tag);
      tx.addIndexEntry(index, INDEX_NAME, OPERATION.PUT, collated(row.key()), row.rid());
    }
    assertAllScans();
  }

  /** Replacing a deleted unique key returns its new row once even with a pending lower key. */
  @Test
  public void uniqueDeleteAndInsertAtSameKeyDoesNotDuplicateDescendingRow() {
    assumeFalse(type == SchemaClass.INDEX_TYPE.NOTUNIQUE);
    delete("r0");
    insert("replacement", "d");
    insert("lower", "b");
    assertAllScans();
  }

  /** Pending SELECT DESC keeps its clean index plan and matches the independent row reference. */
  @Test
  public void pendingSelectDescendingKeepsPlanAndMatchesFixture() {
    assumeFalse(caseInsensitive);
    assertPendingSelect("SELECT FROM " + CLASS_NAME + " ORDER BY p DESC", row -> true);
  }

  /** Pending two-bound SELECT DESC keeps its range plan and returns the reference rows in order. */
  @Test
  public void pendingSelectDescendingRangeKeepsPlanAndMatchesFixture() {
    assumeFalse(caseInsensitive);
    assertPendingSelect(
        "SELECT FROM " + CLASS_NAME + " WHERE p >= 'A' AND p <= 'u' ORDER BY p DESC",
        this::withinRange);
  }

  private void assertPendingSelect(String query, Predicate<Row> filter) {
    var cleanPlan = planText(query);
    insert("inserted", "b");
    update("r2", "f");
    delete("r0");
    var plan = planText(query);
    assertEquals("pending changes must not change plan text", cleanPlan, plan);
    assertTrue("SELECT must read the index", plan.contains(INDEX_NAME));
    assertFalse("SELECT must not add an in-memory sort", plan.contains("ORDER BY"));
    var actual = new ArrayList<Row>();
    try (var result = session.query(query)) {
      while (result.hasNext()) {
        var row = result.next();
        actual.add(new Row(row.getIdentity(), row.getProperty("p"),
            row.getProperty("tag")));
      }
    }
    assertRows(expected(filter, false), actual);
  }

  private void assertAllScans() {
    assertFullScans();
    assertRanges();
    assertRequested();
  }

  private void assertFullScans() {
    prepareIndexChanges();
    for (boolean ascending : new boolean[] {true, false}) {
      assertIndexRows(ascending ? index.stream(session) : index.descStream(session),
          expected(row -> true, ascending));
    }
  }

  private void assertRanges() {
    prepareIndexChanges();
    for (boolean ascending : new boolean[] {true, false}) {
      for (boolean inclusive : new boolean[] {true, false}) {
        Predicate<Row> filter = row -> {
          var key = collated(row.key());
          var lower = collated("A");
          return inclusive ? key.compareTo(lower) >= 0 && key.compareTo("u") <= 0
              : key.compareTo(lower) > 0 && key.compareTo("u") < 0;
        };
        assertIndexRows(index.streamEntriesBetween(session, "A", inclusive, "u", inclusive,
            ascending), expected(filter, ascending));
      }
    }
  }

  private boolean withinRange(Row row) {
    return row.key().compareTo("A") >= 0 && row.key().compareTo("u") <= 0;
  }

  private void assertRequested() {
    prepareIndexChanges();
    var keys = REQUESTED.stream().map(this::collated).toList();
    for (boolean ascending : new boolean[] {true, false}) {
      assertIndexRows(index.streamEntries(session, REQUESTED, ascending),
          expected(row -> keys.contains(collated(row.key())), ascending));
    }
  }

  private void prepareIndexChanges() {
    // Direct index APIs need the same record callbacks that query execution invokes.
    // This builds pending index changes without committing the transaction.
    session.getTransactionInternal().preProcessRecordsAndExecuteCallCallbacks();
  }

  private List<Row> expected(Predicate<Row> filter, boolean ascending) {
    Comparator<Row> byKey = Comparator.comparing(row -> collated(row.key()));
    return rows.values().stream().filter(filter)
        .sorted(ascending ? byKey : byKey.reversed()).toList();
  }

  private void assertIndexRows(Stream<RawPair<Object, RID>> stream, List<Row> expected) {
    try (stream) {
      var entries = stream.toList();
      assertEquals("index keys must follow the independently sorted fixture",
          expected.stream().map(row -> collated(row.key())).toList(),
          entries.stream().map(RawPair::first).toList());
      var actual = entries.stream().map(entry -> {
        var entity = session.loadEntity(entry.second());
        return new Row(entity.getIdentity(), entity.getProperty("p"), entity.getProperty("tag"));
      }).toList();
      assertRows(expected, actual);
    }
  }

  private void assertRows(List<Row> expected, List<Row> actual) {
    assertEquals("row keys must follow the reference order",
        expected.stream().map(row -> collated(row.key())).toList(),
        actual.stream().map(row -> collated(row.key())).toList());
    // Equal-key pending entries have no RID-order contract. Compare their full row content
    // separately so a repeated, missing or substituted record cannot hide behind equal keys.
    var byTag = Comparator.comparing(Row::tag);
    assertEquals("every record and its content must match the fixture exactly",
        expected.stream().sorted(byTag).toList(), actual.stream().sorted(byTag).toList());
  }

  private String collated(String key) {
    return caseInsensitive ? key.toLowerCase(Locale.ROOT) : key;
  }

  private void insert(String tag, String key) {
    var entity = session.newEntity(CLASS_NAME);
    entity.setProperty("p", key);
    entity.setProperty("tag", tag);
    rows.put(tag, new Row(entity.getIdentity(), key, tag));
  }

  private void update(String tag, String key) {
    var row = rows.get(tag);
    session.loadEntity(row.rid()).setProperty("p", key);
    rows.put(tag, new Row(row.rid(), key, tag));
  }

  private void delete(String tag) {
    session.delete(session.loadEntity(rows.remove(tag).rid()));
  }

  private String planText(String query) {
    try (var result = session.query(query)) {
      return result.getExecutionPlan().prettyPrint(0, 2);
    }
  }

  private record Row(RID rid, String key, String tag) {
  }
}
