package com.jetbrains.youtrackdb.benchmarks.ldbc;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;

/**
 * Shared builder for the in-memory synthetic {@code Bench} dataset used by the YTDB-916 Track-06
 * predicate-evaluation benchmarks (Bench 1 sensitivity + Bench 2/3 throughput coverage).
 *
 * <p>Factored out of the individual JMH states so the DB/schema/insert logic lives in one place.
 * The schema is {@code Bench(age INT, name STRING, mid STRING nullable, nameCi STRING ci-collation)}
 * with <b>NO index on any property</b> — a full sequential scan is required so {@code FilterStep}
 * runs on every row rather than being short-circuited by an index lookup.
 */
final class BenchDataset {

  /** Schema class name for the synthetic dataset. */
  static final String CLASS_NAME = "Bench";

  private BenchDataset() {
  }

  /** Vertex/edge class names for the star-graph dataset (Bench 3). */
  static final String ROOT_CLASS = "Root";
  static final String LEAF_CLASS = "Leaf";
  static final String EDGE_CLASS = "HasLeaf";

  /** Row count (~100k by default), overridable via {@code -Danalyzed.bench.rows=<n>}. */
  static int defaultRowCount() {
    return Integer.getInteger("analyzed.bench.rows", 100_000);
  }

  /** Leaf count for the star graph (~10k by default), overridable via
   * {@code -Danalyzed.bench.expand.leaves=<n>}. */
  static int defaultLeafCount() {
    return Integer.getInteger("analyzed.bench.expand.leaves", 10_000);
  }

  /** Open in-memory DB + session, kept OPEN for the whole trial by the owning state. */
  static final class Handle {

    final YouTrackDB db;
    final String dbName;
    final DatabaseSessionEmbedded session;
    /** The tx-result-cache flag value seen before this handle forced it off; restored in close(). */
    final Object priorTxResultCache;

    Handle(YouTrackDB db, String dbName, DatabaseSessionEmbedded session,
        Object priorTxResultCache) {
      this.db = db;
      this.dbName = dbName;
      this.session = session;
      this.priorTxResultCache = priorTxResultCache;
    }
  }

  /**
   * Opens a fresh in-memory embedded DB + session (no schema). Shared bootstrap for all dataset
   * builders so the DB open logic is not duplicated. Captures the prior tx-result-cache flag into
   * the returned {@link Handle} so {@link #close} can restore it.
   */
  private static Handle open(String namePrefix) {
    // Keep the per-transaction query-result cache OFF (it is off by default) so that repeated
    // identical SELECTs in the throughput benches re-run the full scan/traversal + filter every op
    // instead of being served from a tx cache — otherwise the bench would measure cache retrieval.
    // Capture the prior value so close() can restore it (no unscoped global side-effect).
    Object priorTxResultCache = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.getValue();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    try {
      String dbName = namePrefix + System.nanoTime();
      YouTrackDB db = YourTracks.instance(".");
      db.createIfNotExists(dbName, DatabaseType.MEMORY, "admin", "admin", "admin");
      // Public YouTrackDB only exposes openTraversal(Gremlin); cast to the impl for a session that
      // yields internal Result rows and runs SQL through the real execution pipeline.
      DatabaseSessionEmbedded session = ((YouTrackDBImpl) db).open(dbName, "admin", "admin");
      return new Handle(db, dbName, session, priorTxResultCache);
    } catch (RuntimeException e) {
      // No Handle exists yet to release, but at least restore the global we just mutated.
      GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(priorTxResultCache);
      throw e;
    }
  }

  /**
   * Creates an in-memory embedded DB, the unindexed {@code Bench} schema, and inserts
   * {@code rowCount} rows. Values: {@code age = i % 100} (uniform 0..99 → ~50% selectivity for
   * {@code age > 49}), {@code name = "name"+i}, {@code mid} alternating null / non-null, and a
   * constant ci-collated {@code nameCi = "Xyz"}.
   *
   * <p>On any failure after the DB/session is opened, the partially-built resources are released
   * (session closed, in-memory DB dropped, global flag restored) before the exception propagates,
   * so a setup failure cannot leak the embedded DB.
   *
   * @param namePrefix a per-state prefix for the unique DB name
   * @param rowCount   number of rows to insert
   */
  static Handle create(String namePrefix, int rowCount) {
    Handle handle = open(namePrefix);
    try {
      DatabaseSessionEmbedded session = handle.session;

      SchemaClass cls = session.getMetadata().getSchema().createClass(CLASS_NAME);
      cls.createProperty("age", PropertyType.INTEGER);
      cls.createProperty("name", PropertyType.STRING);
      cls.createProperty("mid", PropertyType.STRING); // nullable by default (no NOTNULL constraint)
      cls.createProperty("nameCi", PropertyType.STRING).setCollate("ci");
      // Intentionally NO index on any property — a full scan is required for the filter benches.

      session.begin();
      try {
        for (int i = 0; i < rowCount; i++) {
          var e = session.newEntity(CLASS_NAME);
          e.setProperty("age", i % 100);
          e.setProperty("name", "name" + i);
          e.setProperty("mid", (i % 2 == 0) ? null : ("mid" + i));
          e.setProperty("nameCi", "Xyz");
        }
        session.commit();
      } catch (RuntimeException e) {
        session.rollback();
        throw e;
      }
      return handle;
    } catch (RuntimeException e) {
      // Release the opened session + in-memory DB (and restore the global) before rethrowing.
      close(handle);
      throw e;
    }
  }

  /**
   * Creates an in-memory embedded DB and a synthetic STAR GRAPH: one {@code Root} vertex connected
   * via {@code HasLeaf} edges to {@code leafCount} {@code Leaf} vertices, each carrying an
   * <b>unindexed</b> {@code age INT} (= {@code i % 100}, uniform 0..99 → ~50% selectivity for
   * {@code age > 49}). No index → full traversal so {@code ExpandStep}'s push-down filter runs on
   * every expanded leaf.
   *
   * @param namePrefix a per-state prefix for the unique DB name
   * @param leafCount  number of leaf vertices attached to the single root
   */
  static Handle createStarGraph(String namePrefix, int leafCount) {
    Handle handle = open(namePrefix);
    try {
      DatabaseSessionEmbedded session = handle.session;

      session.createVertexClass(ROOT_CLASS);
      SchemaClass leaf = session.createVertexClass(LEAF_CLASS);
      leaf.createProperty("age", PropertyType.INTEGER); // intentionally NOT indexed
      session.createEdgeClass(EDGE_CLASS);

      session.begin();
      try {
        Vertex root = session.newVertex(ROOT_CLASS);
        for (int i = 0; i < leafCount; i++) {
          Vertex l = session.newVertex(LEAF_CLASS);
          l.setProperty("age", i % 100);
          session.newEdge(root, l, EDGE_CLASS);
        }
        session.commit();
      } catch (RuntimeException e) {
        session.rollback();
        throw e;
      }
      return handle;
    } catch (RuntimeException e) {
      // Release the opened session + in-memory DB (and restore the global) before rethrowing.
      close(handle);
      throw e;
    }
  }

  /**
   * Closes the session, drops the owned in-memory DB, and restores the tx-result-cache flag to the
   * value captured when the handle was opened. Null-safe.
   */
  static void close(Handle handle) {
    if (handle == null) {
      return;
    }
    if (handle.session != null) {
      handle.session.close();
    }
    if (handle.db != null) {
      if (handle.dbName != null && handle.db.exists(handle.dbName)) {
        handle.db.drop(handle.dbName);
      }
      // Do NOT call handle.db.close() here. handle.db is the process-wide cached YouTrackDBImpl
      // returned by YourTracks.instance(".") (cached in YTDBGraphFactory.storagePathYTDBMap keyed
      // on "."). Closing it would invalidate the shared singleton for every other test or benchmark
      // state that keys on the same path — safe only because surefire runs classes sequentially
      // today. The correct scope is to drop only the owned uniquely-named DB (above) and leave the
      // shared manager open.
    }
    // Restore the global tx-result-cache flag captured in open() (no unscoped side-effect).
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(handle.priorTxResultCache);
  }

  /**
   * Parses a full SELECT and returns its WHERE clause. Reimplemented inline (jmh-ldbc has no access
   * to core's test-jar), mirroring the parity test's {@code parseWhere}.
   */
  static SQLWhereClause parseWhere(String selectSql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(selectSql.getBytes()));
      var stm = (SQLSelectStatement) parser.parse();
      return stm.getWhereClause();
    } catch (Exception e) {
      throw new IllegalStateException("failed to parse WHERE from: " + selectSql, e);
    }
  }
}
