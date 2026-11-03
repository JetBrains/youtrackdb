package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/** Sort-only index order must agree with the class-scan ORDER BY comparator on every route. */
public class CaseInsensitiveSortOnlyTest extends GraphBaseTest {

  private void seed() {
    session.createVertexClass("CiItem").createProperty("name", PropertyType.STRING)
        .setCollate("ci");
    session.execute("CREATE INDEX CiItem_name ON CiItem(name) NOTUNIQUE").close();
    for (var name : List.of("Zebra", "ada", "Ada", "ADA", "Bob", "Bob", "Ä", "ä",
        "\uD801\uDC00", "\uD801\uDC28", "\uD83D\uDE00")) {
      graph.addVertex(T.label, "CiItem", "name", name);
    }
    graph.addVertex(T.label, "CiItem");
    graph.tx().commit();
  }

  private String plan(String query) {
    try (var result = session.query("EXPLAIN " + query)) {
      return String.valueOf((Object) result.next().getProperty("executionPlanAsString"));
    }
  }

  private void assertStreaming(String query, String index) {
    assertThat(plan(query)).contains("FETCH FROM INDEX VALUES", index)
        .doesNotContain("FETCH FROM CLASS", "+ ORDER BY");
  }

  private List<String> names(String query) {
    var names = new ArrayList<String>();
    try (var result = session.query(query)) {
      result.forEachRemaining(row -> names.add(row.getProperty("name")));
    }
    return names;
  }

  private List<String> rids(String query, boolean match) {
    var rids = new ArrayList<String>();
    try (var result = session.query(query)) {
      result.forEachRemaining(row -> rids.add((match ? row.getVertex("i").getIdentity()
          : row.getIdentity()).toString()));
    }
    return rids;
  }

  private static List<String> folded(List<String> names) {
    return names.stream().map(name -> name == null ? null : name.toLowerCase(Locale.ENGLISH))
        .toList();
  }

  /** Nulls, duplicate keys, case variants and supplementary Unicode retain comparator order. */
  @Test
  public void selectUsesCiIndexAndMatchesClassSortInBothDirections() {
    seed();
    for (var direction : List.of("ASC", "DESC")) {
      var query = "SELECT FROM CiItem ORDER BY name " + direction;
      var oracle = "SELECT FROM (SELECT FROM CiItem) ORDER BY name " + direction + " COLLATE ci";
      assertStreaming(query, "CiItem_name");
      assertThat(plan(oracle)).contains("FETCH FROM CLASS", "+ ORDER BY");
      assertThat(folded(names(query))).isEqualTo(folded(names(oracle)));
      assertThat(names(query)).containsExactlyInAnyOrderElementsOf(names(oracle));
      // SKIP lands inside the three-member Ada group in ascending order.
      assertThat(folded(names(query + " SKIP 2 LIMIT 2")))
          .isEqualTo(folded(names(oracle + " SKIP 2 LIMIT 2")));
    }
  }

  /** MATCH carries ci through its synthetic SELECT and preserves explicit RID ties and pages. */
  @Test
  public void matchUsesCiIndexAndMatchesClassSortWithRidTies() {
    seed();
    assertStreaming("MATCH {class: CiItem, as: i} RETURN i ORDER BY i.name ASC",
        "CiItem_name");
    for (var direction : List.of("ASC", "DESC")) {
      var query = "MATCH {class: CiItem, as: i} RETURN i ORDER BY i.name " + direction
          + ", i.@rid " + direction;
      var oracle = "SELECT FROM (SELECT FROM CiItem) ORDER BY name " + direction
          + " COLLATE ci, @rid " + direction;
      for (var page : List.of("", " SKIP 2 LIMIT 2")) {
        assertThat(rids(query + page, true)).isEqualTo(rids(oracle + page, false));
      }
    }
  }

  /** Pending case-only updates, inserts and deletes preserve keys and each RID exactly once. */
  @Test
  public void pendingChangesMatchClassSortWithoutAssumingRidOrderInsideKeys() {
    seed();
    session.begin();
    session.execute("UPDATE CiItem SET name = 'ADA' WHERE name = 'Ada'").close();
    session.execute("DELETE VERTEX CiItem WHERE name = 'Bob'").close();
    session.execute("CREATE VERTEX CiItem SET name = 'aDa'").close();
    for (var direction : List.of("ASC", "DESC")) {
      var query = "SELECT FROM CiItem ORDER BY name " + direction;
      var oracle = "SELECT FROM (SELECT FROM CiItem) ORDER BY name " + direction + " COLLATE ci";
      assertStreaming(query, "CiItem_name");
      assertThat(folded(names(query))).isEqualTo(folded(names(oracle)));
      assertThat(rids(query, false)).containsExactlyInAnyOrderElementsOf(rids(oracle, false));
      var match = "MATCH {class: CiItem, as: i} RETURN i ORDER BY i.name " + direction
          + ", i.@rid " + direction + " SKIP 2 LIMIT 3";
      assertThat(rids(match, true)).isEqualTo(rids(oracle + ", @rid " + direction
          + " SKIP 2 LIMIT 3", false));
    }
  }

  /** Translated Gremlin must stream the ci index and equal a class scan with explicit RID ties. */
  @Test
  public void translatedGremlinUsesCiIndexAndMatchesClassSort() throws Exception {
    seed();
    for (var direction : List.of(Order.asc, Order.desc)) {
      try (var traversal = graph.traversal().V().hasLabel("CiItem")
          .order().by("name", direction).asAdmin()) {
        traversal.applyStrategies();
        assertThat(traversal.getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
        var text = ((YTDBMatchPlanStep<?, ?>) traversal.getStartStep()).getPlan().prettyPrint(0, 2);
        assertThat(text).contains("FETCH FROM INDEX VALUES", "CiItem_name")
            .doesNotContain("FETCH FROM CLASS");
        var actual = traversal.toList().stream().map(vertex -> vertex.id().toString()).toList();
        var sqlDirection = direction == Order.asc ? "ASC" : "DESC";
        assertThat(actual).isEqualTo(rids("SELECT FROM (SELECT FROM CiItem) ORDER BY name "
            + sqlDirection + " COLLATE ci, @rid " + sqlDirection, false));
      }
    }
  }

  private record MixedSortRow(List<String> key, String rid) {

  }

  private List<MixedSortRow> mixedSortRows(String query, boolean orderByNick) {
    var rows = new ArrayList<MixedSortRow>();
    try (var result = session.query(query)) {
      result.forEachRemaining(row -> {
        String name = row.getProperty("name");
        String nick = row.getProperty("nick");
        var key = orderByNick ? List.of(name, nick.toLowerCase(Locale.ENGLISH)) : List.of(name);
        rows.add(new MixedSortRow(key, row.getIdentity().toString()));
      });
    }
    return rows;
  }

  /** Raw name order and folded nick order agree with class sorting, including prefix-only scans. */
  @Test
  public void compositeMixedCollationsMatchClassSortAndKeepRidSortForTrailingFields() {
    var cls = session.createVertexClass("MixedItem");
    cls.createProperty("name", PropertyType.STRING);
    cls.createProperty("nick", PropertyType.STRING).setCollate("ci");
    session.execute("CREATE INDEX MixedItem_names ON MixedItem(name, nick) NOTUNIQUE").close();
    for (var name : List.of("B", "a", "A", "b")) {
      for (var nick : List.of("Z", "a", "A")) {
        graph.addVertex(T.label, "MixedItem", "name", name, "nick", nick);
      }
    }
    graph.tx().commit();
    for (var direction : List.of("ASC", "DESC")) {
      for (var orderByNick : List.of(false, true)) {
        var order = " ORDER BY name " + direction;
        var suffix = orderByNick ? ", nick " + direction : "";
        var query = "SELECT FROM MixedItem" + order + suffix;
        var oracle = "SELECT FROM (SELECT FROM MixedItem)" + order + suffix
            + (orderByNick ? " COLLATE ci" : "");
        assertStreaming(query, "MixedItem_names");
        assertThat(plan(oracle)).contains("FETCH FROM CLASS", "+ ORDER BY");
        var actual = mixedSortRows(query, orderByNick);
        var expected = mixedSortRows(oracle, orderByNick);
        assertThat(actual.stream().map(MixedSortRow::key).toList())
            .isEqualTo(expected.stream().map(MixedSortRow::key).toList());
        // Ties have no RID order contract, but each equal-key group must keep every RID.
        var actualGroups = actual.stream().collect(Collectors.groupingBy(MixedSortRow::key,
            Collectors.mapping(MixedSortRow::rid, Collectors.toList())));
        var expectedGroups = expected.stream().collect(Collectors.groupingBy(MixedSortRow::key,
            Collectors.mapping(MixedSortRow::rid, Collectors.toList())));
        assertThat(actualGroups.keySet()).isEqualTo(expectedGroups.keySet());
        expectedGroups.forEach((key, expectedRids) -> assertThat(actualGroups.get(key))
            .containsExactlyInAnyOrderElementsOf(expectedRids));
        assertThat(rids(query, false)).containsExactlyInAnyOrderElementsOf(rids(oracle, false));
      }
    }
    var match = "MATCH {class: MixedItem, as: i} RETURN i ORDER BY i.name ASC, i.@rid ASC";
    assertThat(plan(match)).contains("MixedItem_names", "+ ORDER BY");
    assertThat(rids(match, true)).isEqualTo(rids(
        "SELECT FROM (SELECT FROM MixedItem) ORDER BY name ASC, @rid ASC", false));
  }

  /** Both mismatch directions, explicit COLLATE and output aliases must retain class sorting. */
  @Test
  public void mismatchedAndExplicitCollationsAndAliasesFallBack() {
    seed();
    assertThat(plan("SELECT name AS n FROM CiItem ORDER BY n"))
        .contains("FETCH FROM CLASS", "+ ORDER BY");
    var definition = session.getSharedContext().getIndexManager().getIndex("CiItem_name")
        .getDefinition();
    definition.setCollate(new DefaultCollate());
    assertThat(plan("SELECT FROM CiItem ORDER BY name")).contains("FETCH FROM CLASS", "+ ORDER BY");
    definition.setCollate(new CaseInsensitiveCollate());
    assertThat(plan("SELECT FROM CiItem ORDER BY name COLLATE ci"))
        .contains("FETCH FROM CLASS", "+ ORDER BY");
    session.rollback();
    session.createVertexClass("DefaultItem").createProperty("name", PropertyType.STRING);
    session.execute("CREATE INDEX DefaultItem_name ON DefaultItem(name) NOTUNIQUE").close();
    session.getSharedContext().getIndexManager().getIndex("DefaultItem_name").getDefinition()
        .setCollate(new CaseInsensitiveCollate());
    assertThat(plan("SELECT FROM DefaultItem ORDER BY name"))
        .contains("FETCH FROM CLASS", "+ ORDER BY");
  }

  /** A custom collation cannot opt into index ordering by sharing a built-in implementation. */
  @Test
  public void customCollationKeepsClassSort() {
    seed();
    session.getSharedContext().getIndexManager().getIndex("CiItem_name").getDefinition()
        .setCollate(new CaseInsensitiveCollate() {
          @Override
          public String getName() {
            return "custom-ci";
          }
        });
    assertThat(plan("SELECT FROM CiItem ORDER BY name"))
        .contains("FETCH FROM CLASS", "+ ORDER BY");
  }

  /** A default child beneath a ci parent makes its ci index disagree with hierarchy ordering. */
  @Test
  public void disagreeingHierarchyFallsBackToClassSort() {
    var parent = session.createVertexClass("ParentItem");
    parent.createProperty("name", PropertyType.STRING).setCollate("ci");
    session.execute("CREATE INDEX ParentItem_name ON ParentItem(name) NOTUNIQUE").close();
    var child = session.getMetadata().getSchema().createClass("ChildItem", parent);
    child.createProperty("name", PropertyType.STRING);
    assertThat(plan("SELECT FROM ParentItem ORDER BY name"))
        .contains("FETCH FROM CLASS", "+ ORDER BY");
  }
}
