package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/** Collation must agree across index scans, local sorts, and the downstream early cutoff. */
public class CaseInsensitiveEdgeOrderTest extends GraphBaseTest {

  private String sourceRid;

  private void seed() {
    var target = session.createVertexClass("Comment");
    target.createProperty("name", PropertyType.STRING).setCollate("ci")
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
    session.createVertexClass("Person");
    session.createVertexClass("Reply");
    session.createEdgeClass("Wrote");
    session.createEdgeClass("Next");
    var source = graph.addVertex(T.label, "Person");
    for (var name : List.of("B", "a", "A", "b", "Ä", "ä", "z")) {
      var comment = graph.addVertex(T.label, "Comment", "name", name);
      source.addEdge("Wrote", comment);
      comment.addEdge("Next", graph.addVertex(T.label, "Reply"));
    }
    var missing = graph.addVertex(T.label, "Comment");
    source.addEdge("Wrote", missing);
    missing.addEdge("Next", graph.addVertex(T.label, "Reply"));
    // Sparse membership makes loading the small adjacency cheaper than scanning the index.
    for (var i = 0; i < 300; i++) {
      graph.addVertex(T.label, "Comment", "name", "noise" + i);
    }
    graph.tx().commit();
    sourceRid = graph.traversal().V().hasLabel("Person").next().id().toString();
  }

  private String query(boolean downstream, String filter, String order) {
    return "MATCH {rid: " + sourceRid + ", as: s}.out('Wrote')"
        + "{class: Comment, as: m" + filter + "}"
        + (downstream ? ".out('Next'){class: Reply, as: r}" : "")
        + " RETURN m ORDER BY " + order;
  }

  private InternalExecutionPlan plan(String query) {
    return ((SQLMatchStatement) SQLEngine.parse(query, session))
        .createExecutionPlanNoCache(new BasicCommandContext(session), false);
  }

  private static IndexOrderedEdgeStep ordered(InternalExecutionPlan plan) {
    return plan.getSteps().stream().filter(IndexOrderedEdgeStep.class::isInstance)
        .map(IndexOrderedEdgeStep.class::cast).findFirst()
        .orElseThrow(() -> new AssertionError(plan.prettyPrint(0, 2)));
  }

  private List<String> rows(String query) {
    try (var result = session.query(query)) {
      var rows = new ArrayList<String>();
      result.forEachRemaining(
          row -> rows.add(((Identifiable) row.getProperty("m")).getIdentity().toString()));
      return rows;
    }
  }

  private List<String> execute(InternalExecutionPlan plan) {
    session.begin();
    var stream = plan.start();
    try {
      return stream.stream(plan.getContext())
          .map(row -> ((Identifiable) row.getProperty("m")).getIdentity().toString()).toList();
    } finally {
      stream.close(plan.getContext());
      plan.close();
      session.commit();
    }
  }

  /** LOAD_SORT must fold case before LIMIT and break folded ties by the requested RID direction. */
  @Test
  public void localSortMatchesExplicitCollationOracleAcrossPages() {
    seed();
    for (var direction : List.of("ASC", "DESC")) {
      var filter = direction.equals("DESC") ? ", where: (name is not null)" : "";
      for (var skip : List.of(0, 2, 4)) {
        var suffix = ", m.@rid " + direction + " SKIP " + skip + " LIMIT 3";
        var optimized = query(true, filter, "m.name " + direction + suffix);
        var oracle = query(true, filter, "m.name " + direction + " COLLATE ci" + suffix);
        var execution = plan(optimized);
        var step = ordered(execution);
        assertThat(execute(execution)).isEqualTo(rows(oracle));
        assertThat(step.getChosenRuntimePath())
            .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_SORT);
      }
    }
  }

  /** Without RID order, compare folded sequences and whole tie-group membership, not tie order. */
  @Test
  public void singleKeyLocalSortPreservesGroupsAndLimit() {
    seed();
    var execution = plan(query(true, "", "m.name ASC LIMIT 20"));
    var step = ordered(execution);
    var actual = execute(execution);
    var expected = rows(query(true, "", "m.name ASC COLLATE ci LIMIT 20"));
    assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(folded(actual)).isEqualTo(folded(expected));
    assertThat(step.getChosenRuntimePath())
        .isEqualTo(IndexOrderedEdgeStep.RuntimePath.LOAD_SORT);
    var limited = rows(query(true, "", "m.name ASC SKIP 2 LIMIT 2"));
    assertThat(folded(limited))
        .isEqualTo(folded(rows(query(true, "", "m.name ASC COLLATE ci SKIP 2 LIMIT 2"))));
  }

  private List<String> folded(List<String> rids) {
    return rids.stream().map(rid -> {
      try (var result = session.query("SELECT name FROM " + rid)) {
        var name = result.next().<String>getProperty("name");
        return name == null ? "<null>" : name.toLowerCase(Locale.ENGLISH);
      }
    }).toList();
  }

  /** Global unfiltered scans accept ci and preserve case ties, Unicode, and missing keys. */
  @Test
  public void indexScanMatchesOracleAndKeepsDescendingNullSafeguard() {
    seed();
    var prefix = "MATCH {class: Person, as: s}.out('Wrote'){class: Comment, as: m}"
        + " RETURN m ORDER BY ";
    var execution = plan(prefix + "m.name ASC, m.@rid ASC");
    assertThat(execution.prettyPrint(0, 2)).contains(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
    assertThat(execute(execution))
        .isEqualTo(rows(prefix + "m.name ASC COLLATE ci, m.@rid ASC"));
    var descending = plan(prefix + "m.name DESC, m.@rid DESC");
    assertThat(descending.prettyPrint(0, 2))
        .doesNotContain(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
    assertThat(execute(descending))
        .isEqualTo(rows(prefix + "m.name DESC COLLATE ci, m.@rid DESC"));
  }

  /** A pending case variant must withdraw RID pass-through and remain in the sorted result. */
  @Test
  public void pendingChangesUseOuterSort() {
    seed();
    // An unfiltered global scan bypasses the sparse single-source cost decision in this seed.
    var prefix = "MATCH {class: Person, as: s}.out('Wrote'){class: Comment, as: m}"
        + " RETURN m ORDER BY ";
    var execution = plan(prefix + "m.name ASC, m.@rid ASC LIMIT 6");
    var step = ordered(execution);
    assertThat(execution.prettyPrint(0, 2)).contains(IndexOrderedEdgeStep.RID_TIE_BREAK_MARKER);
    session.begin();
    session.execute("CREATE VERTEX Comment SET name = 'A', pending = true").close();
    session.execute("CREATE EDGE Wrote FROM " + sourceRid
        + " TO (SELECT FROM Comment WHERE pending = true)").close();
    assertThat(session.getTransactionInternal().getEntryCount()).isPositive();
    var stream = execution.start();
    try {
      var actual = stream.stream(execution.getContext())
          .map(row -> ((Identifiable) row.getProperty("m")).getIdentity().toString()).toList();
      assertThat(step.getChosenRuntimePath())
          .isEqualTo(IndexOrderedEdgeStep.RuntimePath.GLOBAL_SCAN);
      assertThat(execution.getContext().<Boolean>getSystemVariable(
          CommandContext.VAR_INDEX_ORDERED_PRE_SORTED)).isFalse();
      assertThat(actual)
          .isEqualTo(rows(prefix + "m.name ASC COLLATE ci, m.@rid ASC LIMIT 6"));
    } finally {
      stream.close(execution.getContext());
      execution.close();
      graph.tx().rollback();
    }
  }

  /** Resolved ci selects the ci index even when the default index is created first. */
  @Test
  public void competingIndexesCiDefaultCreatedFirst() {
    competingIndexes("ci", List.of("default", "ci"));
  }

  /** Resolved ci selects the ci index when it is created first. */
  @Test
  public void competingIndexesCiCreatedFirst() {
    competingIndexes("ci", List.of("ci", "default"));
  }

  /** Resolved default selects the default index when it is created first. */
  @Test
  public void competingIndexesDefaultCreatedFirst() {
    competingIndexes("default", List.of("default", "ci"));
  }

  /** Resolved default selects the default index even when the ci index is created first. */
  @Test
  public void competingIndexesDefaultCiCreatedFirst() {
    competingIndexes("default", List.of("ci", "default"));
  }

  private void competingIndexes(String resolved, List<String> creationOrder) {
    seed();
    session.execute("DROP INDEX Comment.name").close();
    session.execute("ALTER PROPERTY Comment.name COLLATE " + resolved).close();
    for (var collate : creationOrder) {
      session.execute("CREATE INDEX Comment_" + collate + " ON Comment"
          + " (name COLLATE " + collate + ") NOTUNIQUE").close();
    }
    var execution = plan(query(true, "", "m.name ASC, m.@rid ASC LIMIT 20"));
    ordered(execution);
    assertThat(execution.prettyPrint(0, 2)).contains("Comment_" + resolved);
    assertThat(execute(execution)).isEqualTo(
        rows(query(true, "", "m.name ASC COLLATE " + resolved + ", m.@rid ASC LIMIT 20")));
  }

  /** A mixed-collation composite index cannot serve this single-field edge-hop shortcut. */
  @Test
  public void compositeIndexKeepsOuterSort() {
    seed();
    session.execute("DROP INDEX Comment.name").close();
    session.execute("CREATE PROPERTY Comment.rank INTEGER").close();
    session.execute("CREATE INDEX Comment.mixed ON Comment (name, rank) NOTUNIQUE").close();
    var execution = plan(query(true, "", "m.name ASC LIMIT 3"));
    assertThat(execution.prettyPrint(0, 2)).doesNotContain("INDEX ORDERED MATCH");
    execution.close();
    assertThat(folded(rows(query(true, "", "m.name ASC LIMIT 3"))))
        .isEqualTo(folded(rows(query(true, "", "m.name ASC COLLATE ci LIMIT 3"))));
  }

  /** Explicit COLLATE retains the outer sort even when its rule agrees with the index. */
  @Test
  public void explicitCollationRefusesShortcut() {
    seed();
    var execution = plan(query(true, "", "m.name ASC COLLATE ci LIMIT 3"));
    assertThat(execution.prettyPrint(0, 2)).doesNotContain("INDEX ORDERED MATCH");
    execution.close();
  }

  /** The single-node WHERE path uses the mapped ci index component and elides its outer sort. */
  @Test
  public void singleNodeWhereUsesCiIndex() {
    seed();
    var prefix = "MATCH {class: Comment, as: m, where: (name >= 'a')} RETURN m ORDER BY ";
    var execution = plan(prefix + "m.name ASC LIMIT 12");
    assertThat(execution.prettyPrint(0, 2)).doesNotContain("+ ORDER BY");
    assertThat(folded(execute(execution)))
        .isEqualTo(folded(rows(prefix + "m.name ASC COLLATE ci LIMIT 12")));
  }
}
