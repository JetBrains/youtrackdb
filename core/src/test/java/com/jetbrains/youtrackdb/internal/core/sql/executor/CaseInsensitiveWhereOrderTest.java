package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/** WHERE-index order must agree with the effective ORDER BY comparator, not raw stored strings. */
public class CaseInsensitiveWhereOrderTest extends GraphBaseTest {

  private void seed(boolean composite) {
    var cls = session.createVertexClass("WhereItem");
    cls.createProperty("bucket", PropertyType.STRING);
    cls.createProperty("name", PropertyType.STRING).setCollate("ci");
    session.execute("CREATE INDEX WhereItem_order ON WhereItem("
        + (composite ? "bucket, name" : "name") + ") NOTUNIQUE").close();
    for (var bucket : List.of("x", "y")) {
      for (var name : List.of("Zebra", "ada", "Ada", "ADA", "Bob", "Bob", "Ä", "ä",
          "\uD801\uDC00", "\uD801\uDC28")) {
        graph.addVertex(T.label, "WhereItem", "bucket", bucket, "name", name);
      }
      graph.addVertex(T.label, "WhereItem", "bucket", bucket);
    }
    graph.tx().commit();
  }

  private String plan(String query) {
    try (var result = session.query("EXPLAIN " + query)) {
      return String.valueOf((Object) result.next().getProperty("executionPlanAsString"));
    }
  }

  private record Row(String key, String rid) {
  }

  private List<Row> rows(String query) {
    var rows = new ArrayList<Row>();
    try (var result = session.query(query)) {
      result.forEachRemaining(row -> {
        String name = row.getProperty("name");
        rows.add(new Row(name == null ? null : name.toLowerCase(Locale.ENGLISH),
            row.getIdentity().toString()));
      });
    }
    return rows;
  }

  private void assertOrdered(String where, String order, String oracleOrder) {
    var query = "SELECT FROM WhereItem WHERE " + where + " ORDER BY " + order;
    var oracle = "SELECT FROM (SELECT FROM WhereItem WHERE " + where + ") ORDER BY "
        + oracleOrder;
    assertThat(plan(query)).contains("WhereItem_order").doesNotContain("+ ORDER BY");
    assertThat(plan(oracle)).contains("+ ORDER BY");
    var actual = rows(query);
    var expected = rows(oracle);
    assertThat(actual.stream().map(Row::key).toList())
        .isEqualTo(expected.stream().map(Row::key).toList());
    // Plain ORDER BY leaves ties unspecified. Compare membership within each folded-key group.
    var actualGroups = actual.stream().collect(Collectors.groupingBy(
        row -> String.valueOf(row.key()), Collectors.mapping(Row::rid, Collectors.toList())));
    var expectedGroups = expected.stream().collect(Collectors.groupingBy(
        row -> String.valueOf(row.key()), Collectors.mapping(Row::rid, Collectors.toList())));
    assertThat(actualGroups.keySet()).isEqualTo(expectedGroups.keySet());
    expectedGroups.forEach((key, rids) -> assertThat(actualGroups.get(key))
        .containsExactlyInAnyOrderElementsOf(rids));
    for (var page : List.of(" LIMIT 2", " SKIP 2 LIMIT 2", " SKIP 3 LIMIT 3")) {
      assertThat(rows(query + page).stream().map(Row::key).toList())
          .isEqualTo(rows(oracle + page).stream().map(Row::key).toList());
    }
  }

  /** Case variants, duplicates and Unicode use folded range order in both scan directions. */
  @Test
  public void rangeOrderMatchesClassSortWithPagesAndPendingChanges() {
    seed(false);
    for (var pending : List.of(false, true)) {
      if (pending) {
        session.rollback();
        session.begin();
        session.execute("UPDATE WhereItem SET name = 'aDa' WHERE name = 'Ada'").close();
        session.execute("DELETE VERTEX WhereItem WHERE name = 'Bob'").close();
        session.execute("CREATE VERTEX WhereItem SET bucket = 'x', name = 'Aa'").close();
      }
      for (var direction : List.of("ASC", "DESC")) {
        assertOrdered("name >= 'A' AND name <= 'ä'", "name " + direction,
            "name " + direction + " COLLATE ci");
      }
    }
  }

  /** The fixed default component is skipped, while the later ci component governs order. */
  @Test
  public void equalityPrefixMapsToCiComponentIncludingNullsAndOverlappingOrderItems() {
    seed(true);
    for (var direction : List.of("ASC", "DESC")) {
      assertOrdered("bucket = 'x'", "name " + direction, "name " + direction + " COLLATE ci");
      assertOrdered("bucket = 'x'", "bucket " + direction + ", name " + direction,
          "bucket " + direction + ", name " + direction + " COLLATE ci");
      assertThat(plan("SELECT FROM WhereItem WHERE bucket = 'x' ORDER BY bucket " + direction))
          .contains("WhereItem_order").doesNotContain("+ ORDER BY");
    }
  }

  /** Both ordered mixed-collation components must agree, even when the first has a range. */
  @Test
  public void mixedCompositeRangeChecksEveryOrderedComponent() {
    seed(true);
    for (var direction : List.of("ASC", "DESC")) {
      assertOrdered("bucket >= 'x'", "bucket " + direction + ", name " + direction,
          "bucket " + direction + ", name " + direction + " COLLATE ci");
    }
  }

  /** Explicit, custom and mismatched comparators retain a sort even with a usable WHERE index. */
  @Test
  public void incompatibleComparisonsRetainSort() {
    seed(false);
    assertThat(plan("SELECT FROM WhereItem WHERE name >= 'a' ORDER BY name COLLATE ci"))
        .contains("+ ORDER BY");
    var definition = session.getSharedContext().getIndexManager().getIndex("WhereItem_order")
        .getDefinition();
    definition.setCollate(new DefaultCollate());
    assertThat(plan("SELECT FROM WhereItem WHERE name >= 'a' ORDER BY name DESC"))
        .contains("+ ORDER BY");
    definition.setCollate(new CaseInsensitiveCollate() {
      @Override
      public String getName() {
        return "custom-ci";
      }
    });
    assertThat(plan("SELECT FROM WhereItem WHERE name >= 'a' ORDER BY name ASC"))
        .contains("+ ORDER BY");
  }

  /** NL3: compare component one, not the fixed default component zero, on a mismatch. */
  @Test
  public void equalityPrefixDoesNotHideIndexOnlyCiCollation() {
    var cls = session.createVertexClass("MismatchItem");
    cls.createProperty("bucket", PropertyType.STRING);
    cls.createProperty("name", PropertyType.STRING);
    session.execute("CREATE INDEX MismatchItem_order ON MismatchItem(bucket, name COLLATE ci)"
        + " NOTUNIQUE").close();
    for (var name : List.of("B", "a")) {
      graph.addVertex(T.label, "MismatchItem", "bucket", "x", "name", name);
    }
    graph.tx().commit();
    var query = "SELECT FROM MismatchItem WHERE bucket = 'x' ORDER BY name";
    assertThat(plan(query)).contains("MismatchItem_order", "+ ORDER BY");
    try (var result = session.query(query)) {
      assertThat(result.stream().map(row -> (String) row.getProperty("name")).toList())
          .containsExactly("B", "a");
    }
  }
}
