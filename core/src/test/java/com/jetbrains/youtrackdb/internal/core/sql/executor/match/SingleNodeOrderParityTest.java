package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.OrderByNullsUtil;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.util.GremlinValueComparator;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Comparator, SELECT parity, and runtime transaction checks for delegated single-node orders. */
@Category(SequentialTest.class)
public class SingleNodeOrderParityTest extends GraphBaseTest {

  private enum Shape {
    SINGLE("", List.of("p")), RANGE("p >= :floor AND p <= :ceiling", List.of("p")), COMPOSITE("",
        List.of("a", "p")), PREFIX("a = 1",
            List.of("p")), FILTER("q = 1", List.of("p")), PRESENCE("p IS NOT NULL",
                List.of("p")), IN_LIST("a IN [1, 2]", List.of("p"));

    final String filter;
    final List<String> keys;

    Shape(String filter, List<String> keys) {
      this.filter = filter;
      this.keys = keys;
    }
  }

  private enum Mutation {
    INSERT, UPDATE, DELETE, COMBINED
  }

  private static final List<PropertyType> AGREEMENT_TYPES = List.of(
      PropertyType.STRING, PropertyType.BYTE, PropertyType.SHORT, PropertyType.INTEGER,
      PropertyType.LONG, PropertyType.FLOAT, PropertyType.DOUBLE, PropertyType.DECIMAL,
      PropertyType.DATETIME, PropertyType.BOOLEAN);

  /** All agreement types serve ASC shapes with null and missing keys and each pending mutation. */
  @Test
  public void agreementTypesHaveSelectAndGremlinParityWithCleanAndPendingRidTies() {
    for (var type : AGREEMENT_TYPES) {
      var cls = "Parity" + type;
      var low = value(type, false);
      var high = value(type, true);
      seed(cls, type, low, high, true);
      verifyMatrix(cls, low, high, false, false);
      for (var mutation : Mutation.values()) {
        pendingChanges(cls, low, high, mutation);
        verifyMatrix(cls, low, high, false, true);
        graph.tx().rollback();
      }
    }
  }

  /** Every agreement type serves DESC shapes with NOTNULL keys, then each pending mutation sorts. */
  @Test
  public void descendingNonNullShapesPassThroughOnlyInCleanTransactions() {
    for (var type : AGREEMENT_TYPES) {
      var cls = "DescendingParity" + type;
      var low = value(type, false);
      var high = value(type, true);
      seed(cls, type, low, high, false);
      verifyMatrix(cls, low, high, true, false);
      for (var mutation : Mutation.values()) {
        pendingChanges(cls, low, high, mutation);
        verifyMatrix(cls, low, high, true, true);
        graph.tx().rollback();
      }
    }
  }

  /** Missing NOT NULL keys keep the DESC RID sort for both index kinds and composite keys. */
  @Test
  public void missingOptionalNotNullKeysSortDescendingRidInCleanAndPendingTransactions() {
    for (var unique : List.of(false, true)) {
      for (var composite : List.of(false, true)) {
        var cls = "MissingMatch" + unique + composite;
        var schema = db().createVertexClass(cls);
        schema.createProperty("a", PropertyType.INTEGER).setMandatory(true).setNotNull(true);
        schema.createProperty("p", PropertyType.INTEGER).setNotNull(true);
        db().execute("CREATE INDEX " + cls + "_p ON " + cls + " ("
            + (composite ? "a, p" : "p") + ") " + (unique ? "UNIQUE" : "NOTUNIQUE"))
            .close();
        graph.tx().readWrite();
        db().execute("CREATE VERTEX " + cls + " SET a = 1, p = 1").close();
        db().execute("CREATE VERTEX " + cls + " SET a = 1").close();
        if (!unique) {
          db().execute("CREATE VERTEX " + cls + " SET a = 1").close();
        }
        graph.tx().commit();
        var query = match(cls, "") + " ORDER BY "
            + (composite ? "s.a DESC, " : "") + "s.p DESC, s.@rid DESC";
        for (var pending : List.of(false, true)) {
          graph.tx().readWrite();
          if (pending) {
            db().execute("CREATE VERTEX " + cls + " SET a = 1, p = 2").close();
          }
          assertTransaction(pending);
          assertThat(plan(query, Map.of())).contains("FETCH FROM INDEX", "+ ORDER BY");
          assertSqlOracle(select(cls, ""), query, Map.of(), true);
          graph.tx().rollback();
        }
        if (!unique) {
          graph.tx().readWrite();
          // A three-row result exceeds this cap only if the retained step really sorts.
          underLowCap(() -> assertThatThrownBy(() -> ids(query, Map.of()))
              .hasMessageContaining("in-heap ORDER BY"));
          graph.tx().rollback();
        }
      }
    }
  }

  /** Equality bound to null must sort RID ties in clean and pending transactions. */
  @Test
  public void nullEqualityParameterKeepsDescendingRidSort() {
    var cls = "NullParameterMatch";
    var schema = db().createVertexClass(cls);
    schema.createProperty("p", PropertyType.INTEGER);
    db().execute("CREATE INDEX " + cls + "_p ON " + cls + " (p) NOTUNIQUE").close();
    graph.tx().readWrite();
    db().execute("CREATE VERTEX " + cls + " SET p = 1").close();
    db().execute("CREATE VERTEX " + cls).close();
    db().execute("CREATE VERTEX " + cls).close();
    graph.tx().commit();
    var params = new HashMap<Object, Object>();
    var query = match(cls, "p = :x") + " ORDER BY s.p DESC, s.@rid DESC";
    for (var pending : List.of(false, true)) {
      graph.tx().readWrite();
      if (pending) {
        db().execute("CREATE VERTEX " + cls).close();
      }
      for (var bound : Arrays.asList(1, null)) {
        params.put("x", bound);
        assertTransaction(pending);
        assertSqlOracle(select(cls, "p = :x"), query, params, true);
      }
      graph.tx().rollback();
    }
  }

  /** LIKE rejects null and missing values, so clean RID scans pass through and pending rows sort. */
  @Test
  public void likeFilterProvidesSameNullExclusionForMatchAndSelect() {
    var cls = "LikeProofMatch";
    var schema = db().createVertexClass(cls);
    schema.createProperty("p", PropertyType.STRING);
    db().execute("CREATE INDEX " + cls + "_p ON " + cls + " (p) NOTUNIQUE").close();
    graph.tx().readWrite();
    for (var p : List.of("aa", "aa", "ab", "b")) {
      db().execute("CREATE VERTEX " + cls + " SET p = ?", p).close();
    }
    db().execute("CREATE VERTEX " + cls).close();
    db().execute("CREATE VERTEX " + cls + " SET p = null").close();
    graph.tx().commit();
    var filter = "p LIKE :pattern";
    var params = Map.<Object, Object>of("pattern", "a%");
    var query = match(cls, filter) + " ORDER BY s.p DESC, s.@rid DESC";
    for (var pending : List.of(false, true)) {
      graph.tx().readWrite();
      if (pending) {
        db().execute("CREATE VERTEX " + cls + " SET p = 'aa'").close();
      }
      assertTransaction(pending);
      assertThat(plan(query, params)).contains("FETCH FROM INDEX", "+ ORDER BY");
      if (pending) {
        assertSqlOracle(select(cls, filter), query, params, true);
      } else {
        underLowCap(() -> assertSqlOracle(select(cls, filter), query, params, true));
      }
      graph.tx().rollback();
    }
  }

  private DatabaseSessionEmbedded db() {
    return ((YTDBGraphEmbedded) graph).getUnderlyingDatabaseSession();
  }

  private Object value(PropertyType type, boolean high) {
    var number = high ? 10 : 2;
    return switch (type) {
      case STRING -> high ? "ten" : "two";
      case BYTE -> (byte) number;
      case SHORT -> (short) number;
      case INTEGER -> number;
      case LONG -> (long) number;
      case FLOAT -> (float) number;
      case DOUBLE -> (double) number;
      case DECIMAL -> new BigDecimal(high ? "10.0" : "2.00");
      case DATETIME, DATE -> new Date(number * 86_400_000L);
      case BOOLEAN -> high;
      default -> throw new IllegalArgumentException(type.toString());
    };
  }

  private void seed(String cls, PropertyType type, Object low, Object high, boolean nullable) {
    var schema = db().createVertexClass(cls);
    schema.createProperty("a", PropertyType.INTEGER).setMandatory(!nullable).setNotNull(!nullable);
    schema.createProperty("p", type).setMandatory(!nullable).setNotNull(!nullable);
    schema.createProperty("q", PropertyType.INTEGER);
    schema.createProperty("n", PropertyType.INTEGER);
    db().execute("CREATE INDEX " + cls + "_p ON " + cls + " (p) NOTUNIQUE").close();
    db().execute("CREATE INDEX " + cls + "_ap ON " + cls + " (a, p) NOTUNIQUE").close();
    graph.tx().readWrite();
    for (var n = 0; n < 12; n++) {
      db().execute("CREATE VERTEX " + cls + " SET a = ?, p = ?, q = ?, n = ?",
          n % 3 == 0 ? 2 : 1, n % 4 == 0 ? high : low, n % 3 == 0 ? 2 : 1, n).close();
    }
    if (nullable) {
      db().execute("CREATE VERTEX " + cls + " SET a = 1, p = null, q = 1, n = 12").close();
      db().execute("CREATE VERTEX " + cls + " SET a = 1, q = 1, n = 13").close();
      db().execute("CREATE VERTEX " + cls + " SET q = 1, n = 14").close();
    }
    graph.tx().commit();
  }

  private void pendingChanges(String cls, Object low, Object high) {
    pendingChanges(cls, low, high, Mutation.COMBINED);
  }

  private void pendingChanges(String cls, Object low, Object high, Mutation mutation) {
    // Start a new snapshot for each mutation. No record leaves a key and returns to it.
    graph.tx().rollback();
    graph.tx().readWrite();
    if (mutation == Mutation.INSERT || mutation == Mutation.COMBINED) {
      db().execute("CREATE VERTEX " + cls + " SET a = 1, p = ?, q = 1, n = 20", low).close();
    }
    if (mutation == Mutation.UPDATE || mutation == Mutation.COMBINED) {
      db().execute("UPDATE " + cls + " SET p = ? WHERE n = 1", high).close();
    }
    if (mutation == Mutation.DELETE || mutation == Mutation.COMBINED) {
      db().execute("DELETE VERTEX " + cls + " WHERE n = 2").close();
    }
    assertThat(db().getTransactionInternal().getEntryCount())
        .isGreaterThanOrEqualTo(mutation == Mutation.COMBINED ? 3 : 1);
  }

  private void verifyMatrix(
      String cls, Object low, Object high, boolean desc, boolean pending) {
    if (!pending) {
      graph.tx().readWrite();
    }
    var increasing = GremlinValueComparator.ORDERABILITY.compare(low, high) <= 0;
    var floor = increasing ? low : high;
    var ceiling = increasing ? high : low;
    var params = Map.<Object, Object>of("floor", floor, "ceiling", ceiling);
    for (var shape : Shape.values()) {
      assertTransaction(pending);
      var direction = desc ? " DESC" : " ASC";
      var order = String.join(", ", shape.keys.stream().map(key -> key + direction).toList());
      var matchOrder = String.join(", ",
          shape.keys.stream().map(key -> "s." + key + direction).toList());
      var select = select(cls, shape.filter);
      var root = match(cls, shape.filter);
      var propertyQuery = root + " ORDER BY " + matchOrder;
      var accepted = shape != Shape.IN_LIST;
      assertThat(plan(select + " ORDER BY " + order, params).contains("+ ORDER BY"))
          .as("%s %s SELECT", cls, shape).isEqualTo(!accepted);
      var propertyPlan = plan(propertyQuery, params);
      assertThat(propertyPlan).contains("FETCH FROM INDEX").doesNotContain("PREFETCH");
      assertThat(propertyPlan.contains("+ ORDER BY"))
          .as("%s %s MATCH", cls, shape).isEqualTo(!accepted);
      assertKeyParity(select + " ORDER BY " + order, propertyQuery, params);
      assertSqlOracle(select, propertyQuery, params, false);
      var ridQuery = propertyQuery + ", s.@rid" + direction;
      assertThat(plan(ridQuery, params).split("\\+ ORDER BY", -1)).hasSize(2);
      var expected = sqlOracle(select, ridQuery, params);
      if (pending) {
        assertThat(ids(ridQuery, params)).isEqualTo(expected);
        assertThat(db().getTransactionInternal().getEntryCount()).as(ridQuery).isPositive();
      } else if (accepted) {
        underLowCap(() -> assertThat(ids(ridQuery, params)).isEqualTo(expected));
      } else {
        assertThat(ids(ridQuery, params)).isEqualTo(expected);
      }

      // Gremlin has(p) tests property presence, including an explicitly stored null.
      // Its SQL control uses IS DEFINED rather than SQL's non-null presence filter.
      var gremlinFilter = shape == Shape.PRESENCE ? "p IS DEFINED" : shape.filter;
      var gremlinSelect = select(cls, gremlinFilter);
      var gremlinMatch = match(cls, gremlinFilter) + " ORDER BY " + matchOrder;
      var traversal = traversal(cls, shape, floor, ceiling, desc);
      var text = translatedPlan(traversal);
      assertThat(text).contains("FETCH FROM INDEX").contains(".@rid" + direction);
      var oracle = gremlinOracle(gremlinSelect, shape.keys, desc, params);
      assertKeyParity(gremlinSelect + " ORDER BY " + order, gremlinMatch, params);
      assertSqlOracle(gremlinSelect, gremlinMatch, params, false);
      assertTransaction(pending);
      if (pending) {
        assertThat(traversal.toList().stream().map(v -> v.id().toString()).toList())
            .as("%s %s pending Gremlin", cls, shape).isEqualTo(oracle);
      } else if (accepted) {
        underLowCap(() -> assertThat(
            traversal.toList().stream().map(v -> v.id().toString()).toList())
            .as("%s %s clean Gremlin", cls, shape).isEqualTo(oracle));
      } else {
        assertThat(text.split("\\+ ORDER BY", -1)).hasSize(2);
        assertThat(traversal.toList().stream().map(v -> v.id().toString()).toList())
            .as("%s %s clean Gremlin", cls, shape).isEqualTo(oracle);
      }
    }
  }

  private GraphTraversal<Vertex, Vertex> traversal(
      String cls, Shape shape, Object floor, Object ceiling, boolean desc) {
    GraphTraversal<Vertex, Vertex> traversal = graph.traversal().V().hasLabel(cls);
    traversal = switch (shape) {
      case RANGE -> traversal.has("p", P.gte(floor).and(P.lte(ceiling)));
      case PREFIX -> traversal.has("a", 1);
      case FILTER -> traversal.has("q", 1);
      case PRESENCE -> traversal.has("p");
      case IN_LIST -> traversal.has("a", P.within(1, 2));
      default -> traversal;
    };
    traversal = traversal.order();
    for (var key : shape.keys) {
      traversal = traversal.by(key, desc ? Order.desc : Order.asc);
    }
    return traversal;
  }

  private String translatedPlan(GraphTraversal<Vertex, Vertex> traversal) {
    traversal.asAdmin().applyStrategies();
    assertThat(traversal.asAdmin().getStartStep()).isInstanceOf(YTDBMatchPlanStep.class);
    return ((YTDBMatchPlanStep<?, ?>) traversal.asAdmin().getStartStep()).getPlan().prettyPrint(0,
        2);
  }

  /** BINARY signed-byte and DATE Gremlin orders sort rather than using index order, in both txs. */
  @Test
  public void binaryAndDateGremlinUseTheirComparatorAndRidInsteadOfIndexOrder() {
    for (var type : List.of(PropertyType.BINARY, PropertyType.DATE)) {
      var cls = "Rejected" + type;
      Object low = type == PropertyType.BINARY ? new byte[] {0, 127} : value(type, false);
      Object high = type == PropertyType.BINARY ? new byte[] {(byte) 128, (byte) 255}
          : value(type, true);
      seed(cls, type, low, high, true);
      graph.tx().readWrite();
      for (var pending : List.of(false, true)) {
        if (pending) {
          pendingChanges(cls, low, high);
        }
        for (var desc : List.of(false, true)) {
          assertTransaction(pending);
          var traversal = traversal(cls, Shape.SINGLE, low, high, desc);
          assertThat(translatedPlan(traversal)).contains("+ ORDER BY")
              .doesNotContain("FETCH FROM INDEX");
          assertThat(traversal.toList().stream().map(v -> v.id().toString()).toList())
              .isEqualTo(gremlinOracle(select(cls, ""), List.of("p"), desc, Map.of()));
        }
      }
      graph.tx().rollback();
    }
  }

  /** Terminal SQL BINARY keeps its index plan, but BINARY before RID always sorts SQL ties. */
  @Test
  public void binarySqlRidSortsInCleanAndPendingTransactionsWhileTerminalKeyKeepsIndex() {
    var cls = "SqlBinary";
    var low = new byte[] {(byte) 200};
    var high = new byte[] {1};
    seed(cls, PropertyType.BINARY, low, high, true);
    graph.tx().readWrite();
    var query = match(cls, "") + " ORDER BY s.p";
    assertThat(plan(query, Map.of())).contains("FETCH FROM INDEX VALUES")
        .doesNotContain("+ ORDER BY");
    for (var pending : List.of(false, true)) {
      if (pending) {
        pendingChanges(cls, low, high);
      }
      for (var desc : List.of(false, true)) {
        var rid = query + (desc ? " DESC, s.@rid DESC" : " ASC, s.@rid ASC");
        assertThat(plan(rid, Map.of())).contains("+ ORDER BY");
        assertTransaction(pending);
        assertSqlOracle(select(cls, ""), query + (desc ? " DESC" : " ASC"), Map.of(), false);
        assertSqlOracle(select(cls, ""), rid, Map.of(), true);
      }
    }
    graph.tx().rollback();
    // A query error rolls back its transaction. Probe only after all pending-result checks.
    for (var desc : List.of(false, true)) {
      graph.tx().readWrite();
      assertTransaction(false);
      var rid = query + (desc ? " DESC, s.@rid DESC" : " ASC, s.@rid ASC");
      underLowCap(() -> assertThatThrownBy(() -> ids(rid, Map.of()))
          .hasMessageContaining("in-heap ORDER BY"));
      graph.tx().rollback();
    }
  }

  /** A BINARY leading composite key requires SQL and Gremlin sorts before later keys or RID. */
  @Test
  public void binaryLeadingCompositeSortsWithBothComparatorsInCleanAndPendingTransactions() {
    var cls = "BinaryLeading";
    var schema = db().createVertexClass(cls);
    schema.createProperty("a", PropertyType.BINARY);
    schema.createProperty("p", PropertyType.INTEGER);
    schema.createProperty("n", PropertyType.INTEGER);
    db().execute("CREATE INDEX " + cls + "_ap ON " + cls + " (a, p) NOTUNIQUE").close();
    graph.tx().readWrite();
    for (var n = 0; n < 12; n++) {
      db().execute("CREATE VERTEX " + cls + " SET a = ?, p = ?, n = ?",
          new byte[] {(byte) (n % 2 == 0 ? 255 : 1)}, n % 3, n).close();
    }
    db().execute("CREATE VERTEX " + cls + " SET p = 1, n = 12").close();
    db().execute("CREATE VERTEX " + cls + " SET a = ?, n = 13", new byte[] {127}).close();
    graph.tx().commit();
    graph.tx().readWrite();
    for (var pending : List.of(false, true)) {
      if (pending) {
        graph.tx().rollback();
        graph.tx().readWrite();
        db().execute("CREATE VERTEX " + cls + " SET a = ?, p = 0, n = 20",
            new byte[] {(byte) 128}).close();
        db().execute("UPDATE " + cls + " SET p = 2 WHERE n = 1").close();
        db().execute("DELETE VERTEX " + cls + " WHERE n = 2").close();
      }
      for (var desc : List.of(false, true)) {
        assertTransaction(pending);
        var direction = desc ? " DESC" : " ASC";
        var control = select(cls, "") + " ORDER BY a" + direction + ", p" + direction;
        var query = match(cls, "") + " ORDER BY s.a" + direction + ", s.p" + direction;
        assertThat(plan(control, Map.of())).contains("+ ORDER BY");
        assertThat(plan(query, Map.of()).split("\\+ ORDER BY", -1)).hasSize(2);
        assertSqlOracle(select(cls, ""), query, Map.of(), false);
        var rid = query + ", s.@rid" + direction;
        assertThat(plan(rid, Map.of()).split("\\+ ORDER BY", -1)).hasSize(2);
        assertSqlOracle(select(cls, ""), rid, Map.of(), true);
        var traversal = traversal(cls, Shape.COMPOSITE, 0, 2, desc);
        assertThat(translatedPlan(traversal)).contains("+ ORDER BY")
            .doesNotContain("FETCH FROM INDEX");
        assertThat(traversal.toList().stream().map(v -> v.id().toString()).toList())
            .isEqualTo(gremlinOracle(select(cls, ""), List.of("a", "p"), desc, Map.of()));
      }
    }
    graph.tx().rollback();
  }

  private void assertTransaction(boolean pending) {
    var entries = db().getTransactionInternal().getEntryCount();
    if (pending) {
      assertThat(entries).as("pending fixture must remain active").isPositive();
    } else {
      assertThat(entries).as("pass-through proof requires a clean transaction").isZero();
    }
  }

  private String select(String cls, String filter) {
    return "SELECT FROM " + cls + (filter.isEmpty() ? "" : " WHERE " + filter);
  }

  private String match(String cls, String filter) {
    return "MATCH {class: " + cls + ", as: s"
        + (filter.isEmpty() ? "" : ", where: (" + filter + ")") + "} RETURN s";
  }

  private String plan(String query, Map<Object, Object> params) {
    try (var rows = db().query("EXPLAIN " + query, params)) {
      return String.valueOf((Object) rows.next().getProperty("executionPlanAsString"));
    }
  }

  private List<Result> rows(String query, Map<Object, Object> params) {
    try (var results = db().query(query, params)) {
      return results.stream().toList();
    }
  }

  private List<String> ids(String query, Map<Object, Object> params) {
    return rows(query, params).stream().map(row -> row.getVertex("s").getIdentity().toString())
        .toList();
  }

  private List<Result> wrappedRows(String select, Map<Object, Object> params) {
    return rows(select, params).stream().map(row -> {
      var wrapped = new ResultInternal(db());
      wrapped.setProperty("s", row);
      return (Result) wrapped;
    }).toList();
  }

  private void assertKeyParity(String select, String match, Map<Object, Object> params) {
    var expected = wrappedRows(select, params);
    var actual = rows(match, params);
    var order = ((SQLMatchStatement) SQLEngine.parse(match, db())).getOrderBy();
    var context = new BasicCommandContext(db());
    var placements = OrderByNullsUtil.resolvePlacementsForSort(context);
    assertThat(actual).hasSize(expected.size());
    for (var i = 0; i < actual.size(); i++) {
      assertThat(order.compare(actual.get(i), expected.get(i), context, placements))
          .as("%s row %s actual %s SELECT %s", match, i, actual.get(i), expected.get(i)).isZero();
    }
  }

  private List<Result> referenceRows(String select, Map<Object, Object> params) {
    // Read every class row without WHERE or ORDER BY, then evaluate the predicate in memory.
    // This source cannot hide a missing or duplicated record from the tested index scan.
    var scan = select.split(" WHERE ", 2)[0];
    assertThat(plan(scan, Map.of())).contains("FETCH FROM CLASS")
        .doesNotContain("FETCH FROM INDEX");
    var where = ((SQLSelectStatement) SQLEngine.parse(select, db())).getWhereClause();
    var context = new BasicCommandContext(db());
    context.setInputParameters(params);
    return rows(scan, Map.of()).stream()
        .filter(row -> where == null || where.matchesFilters(row, context)).toList();
  }

  private List<Result> sqlSortedRows(String select, String match, Map<Object, Object> params) {
    var sorted = new ArrayList<Result>();
    for (var row : referenceRows(select, params)) {
      var wrapped = new ResultInternal(db());
      wrapped.setProperty("s", row);
      sorted.add(wrapped);
    }
    var order = ((SQLMatchStatement) SQLEngine.parse(match, db())).getOrderBy();
    var context = new BasicCommandContext(db());
    var placements = OrderByNullsUtil.resolvePlacementsForSort(context);
    sorted.sort((left, right) -> order.compare(left, right, context, placements));
    return sorted;
  }

  private List<String> sqlOracle(String select, String match, Map<Object, Object> params) {
    return sqlSortedRows(select, match, params).stream()
        .map(row -> row.getVertex("s").getIdentity().toString()).toList();
  }

  private void assertSqlOracle(String select, String match, Map<Object, Object> params,
      boolean rid) {
    var expected = sqlOracle(select, match, params);
    if (rid) {
      assertThat(ids(match, params)).isEqualTo(expected);
    } else {
      // SQL leaves identity order inside equal-key groups unspecified.
      var sorted = sqlSortedRows(select, match, params);
      var actual = rows(match, params);
      var order = ((SQLMatchStatement) SQLEngine.parse(match, db())).getOrderBy();
      var context = new BasicCommandContext(db());
      var placements = OrderByNullsUtil.resolvePlacementsForSort(context);
      assertThat(actual).hasSize(sorted.size());
      for (var i = 0; i < actual.size(); i++) {
        assertThat(order.compare(actual.get(i), sorted.get(i), context, placements))
            .as("%s row %s, MATCH p=%s, oracle p=%s, SELECT p=%s", match, i,
                actual.stream().map(row -> row.getVertex("s").getProperty("p")).toList(),
                sorted.stream().map(row -> row.getVertex("s").getProperty("p")).toList(),
                rows(select + " ORDER BY p DESC", params).stream()
                    .map(row -> row.getProperty("p")).toList())
            .isZero();
      }
      assertThat(ids(match, params)).containsExactlyInAnyOrderElementsOf(expected);
    }
  }

  private List<String> gremlinOracle(
      String select, List<String> keys, boolean desc, Map<Object, Object> params) {
    var sorted = new ArrayList<>(referenceRows(select, params));
    sorted.sort((left, right) -> {
      for (var key : keys) {
        var compared = GremlinValueComparator.ORDERABILITY.compare(
            left.getProperty(key), right.getProperty(key));
        if (compared != 0) {
          return desc ? -compared : compared;
        }
      }
      var compared = left.getIdentity().compareTo(right.getIdentity());
      return desc ? -compared : compared;
    });
    return sorted.stream().map(row -> row.getIdentity().toString()).toList();
  }

  private void underLowCap(Runnable check) {
    var setting = GlobalConfiguration.QUERY_MAX_HEAP_ELEMENTS_ALLOWED_PER_OP;
    var previous = setting.getValue();
    setting.setValue(2);
    try {
      check.run();
    } finally {
      setting.setValue(previous);
    }
  }
}
