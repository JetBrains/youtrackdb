/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.command.script;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Tests for {@link ScriptDatabaseWrapper}, the thin façade injected into script engines as the
 * {@code db} binding. Every method on the wrapper delegates one-to-one to the
 * {@link com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded} passed at
 * construction, so the tests verify that each overload forwards its arguments and returns the
 * session's result verbatim.
 *
 * <p>Tests extend {@link TestUtilsFixture} so the shared {@code @After rollbackIfLeftOpen()}
 * safety net keeps sibling tests isolated from any transaction a failing delegate left open
 * (CQ3 — reuses the Track 8 precedent instead of hand-rolling the guard per class).
 */
public class ScriptDatabaseWrapperTest extends TestUtilsFixture {

  private static final String WRAPPER_CLASS = "WrapperTarget";

  // ==========================================================================
  // query(...) — positional + named parameters forward to session.query.
  // ==========================================================================

  /**
   * {@code query(text, Object...)} must forward the script's positional query to
   * {@link com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded#query(String, Object...)}
   * and return a non-null {@link com.jetbrains.youtrackdb.internal.core.query.ResultSet} with
   * the expected number of rows. Uses the built-in {@code OUser} class so no schema setup is
   * required — DbTestBase creates two users (admin + reader).
   */
  @Test
  public void queryWithPositionalParametersDelegatesAndReturnsRows() {
    final var wrapper = new ScriptDatabaseWrapper(session);
    try (var rs = wrapper.query("select from OUser")) {
      assertNotNull(rs);
      assertEquals(2, rs.stream().count());
    }
  }

  /**
   * {@code query(text, Map<String,Object>)} must forward named parameters to the named-parameter
   * overload of {@code session.query}. Pins the alternate overload's delegation.
   */
  @Test
  public void queryWithNamedParametersDelegatesAndReturnsRows() {
    final var wrapper = new ScriptDatabaseWrapper(session);
    final Map<String, Object> params = new HashMap<>();
    params.put("uname", "admin");
    try (var rs = wrapper.query("select from OUser where name = :uname", params)) {
      assertEquals(1, rs.stream().count());
    }
  }

  // ==========================================================================
  // execute(...) — positional + named parameters forward to session.execute.
  // session.execute runs a SQL script; we run a trivial no-op SELECT that
  // exercises the wrapper's delegation without modifying data.
  // ==========================================================================

  /**
   * {@code execute(script, Object...)} must forward the positional-arg script to
   * {@code session.execute}. {@code session.execute} requires an active transaction
   * (the session enforces this for compiled SQL scripts regardless of whether the script
   * mutates data), so the test wraps the call in begin/rollback.
   */
  @Test
  public void executeWithPositionalParametersDelegates() {
    session.begin();
    try {
      final var wrapper = new ScriptDatabaseWrapper(session);
      try (var rs = wrapper.execute("select from OUser")) {
        assertNotNull(rs);
        assertTrue("script must return at least one row", rs.hasNext());
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code execute(script, Map<String,Object>)} must forward named parameters to
   * {@code session.execute}. Pins the alternate overload. Wrapped in a transaction per
   * {@code session.execute}'s precondition.
   */
  @Test
  public void executeWithNamedParametersDelegates() {
    session.begin();
    try {
      final var wrapper = new ScriptDatabaseWrapper(session);
      final Map<String, Object> params = new HashMap<>();
      params.put("uname", "admin");
      try (var rs = wrapper.execute("select from OUser where name = :uname", params)) {
        assertEquals(1, rs.stream().count());
      }
    } finally {
      session.rollback();
    }
  }

  // ==========================================================================
  // command(...) — SQL side-effecting command, positional + named.
  // Void return; we verify the side effect (row created) via a follow-up query.
  // ==========================================================================

  /**
   * {@code command(text, Object...)} returns void, so its delegation is visible through a side
   * effect — creating a class and then verifying via a query. Pins the positional-arg delegate.
   */
  @Test
  public void commandWithPositionalParametersDelegatesAndCreatesClass() {
    final var wrapper = new ScriptDatabaseWrapper(session);
    wrapper.command("create class " + WRAPPER_CLASS + "Pos");
    assertNotNull(
        "CREATE CLASS must register the class on the schema",
        session.getMetadata().getSchema().getClass(WRAPPER_CLASS + "Pos"));
  }

  /**
   * {@code command(text, Map)} must delegate with named parameters. Pins the named-map overload
   * by observing a CREATE CLASS that uses a parameter binding.
   */
  @Test
  public void commandWithNamedParametersDelegatesAndCreatesClass() {
    final var wrapper = new ScriptDatabaseWrapper(session);
    final Map<String, Object> params = new HashMap<>();
    // YouTrackDB SQL for DDL does not itself use :params, but the delegation shape is
    // identical — the Map is forwarded to the session verbatim.
    wrapper.command("create class " + WRAPPER_CLASS + "Named", params);
    assertNotNull(
        session.getMetadata().getSchema().getClass(WRAPPER_CLASS + "Named"));
  }

  // ==========================================================================
  // runScript(...) — invokes session.computeScript with the language arg.
  // ==========================================================================

  /**
   * {@code runScript(language, script, Object...)} must forward to
   * {@code session.computeScript(language, script, Object...)}. Uses the SQL language engine
   * (always available). Wrapped in a transaction per {@code session.computeScript}'s
   * precondition for SQL scripts. Pins the positional-arg overload.
   */
  @Test
  public void runScriptWithPositionalParametersDelegatesToComputeScript() {
    session.begin();
    try {
      final var wrapper = new ScriptDatabaseWrapper(session);
      try (var rs = wrapper.runScript("sql", "select from OUser")) {
        assertNotNull(rs);
        assertEquals(2, rs.stream().count());
      }
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code runScript(language, script, Map)} — named-parameter overload. Pins the alternate
   * delegate. Wrapped in a transaction.
   */
  @Test
  public void runScriptWithNamedParametersDelegatesToComputeScript() {
    session.begin();
    try {
      final var wrapper = new ScriptDatabaseWrapper(session);
      final Map<String, Object> params = new HashMap<>();
      params.put("uname", "admin");
      try (var rs = wrapper.runScript("sql", "select from OUser where name = :uname", params)) {
        assertEquals(1, rs.stream().count());
      }
    } finally {
      session.rollback();
    }
  }

  // ==========================================================================
  // newInstance / newVertex / newEdge — entity factory delegation.
  // Must happen inside a transaction because entity creation reads schema and
  // may read existing records.
  // ==========================================================================

  /**
   * {@code newInstance()} and {@code newInstance(className)} must delegate to the session's
   * entity factories and return non-null entities. The className overload must produce an
   * entity bound to the given schema class. Pins both overloads with a single round-trip.
   */
  @Test
  public void newInstanceOverloadsDelegateToSession() {
    session.createClassIfNotExist(WRAPPER_CLASS);
    session.begin();
    try {
      final var wrapper = new ScriptDatabaseWrapper(session);

      final var plain = wrapper.newInstance();
      assertNotNull("newInstance() must return a non-null entity", plain);

      final var classed = wrapper.newInstance(WRAPPER_CLASS);
      assertNotNull(classed);
      assertEquals(
          "newInstance(className) must bind the entity to the requested class",
          WRAPPER_CLASS,
          classed.getSchemaClassName());
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code newVertex()} and {@code newVertex(className)} must delegate to
   * {@code session.newVertex(...)}. Uses the schemaless path (no className) and the schema'd
   * path (className="V" — the built-in vertex base class). Pins both overloads.
   */
  @Test
  public void newVertexOverloadsDelegateToSession() {
    session.begin();
    try {
      final var wrapper = new ScriptDatabaseWrapper(session);

      final var plain = wrapper.newVertex();
      assertNotNull("newVertex() must return a non-null vertex", plain);
      // TB8 pin: newVertex() without args defaults to the built-in "V" base class. A
      // regression that returned a null-class Entity or a differently-named default would
      // surface here rather than silently passing assertNotNull.
      assertEquals(
          "newVertex() must default to the built-in 'V' base class",
          "V",
          plain.getSchemaClassName());

      final var classed = wrapper.newVertex("V");
      assertNotNull(classed);
      assertEquals("V", classed.getSchemaClassName());
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code newEdge(from, to)} and {@code newEdge(from, to, edgeClassName)} must delegate to the
   * session's edge factories. Creates two vertices, adds a default-class edge and a classed
   * edge, and rolls back. Pins both overloads.
   */
  @Test
  public void newEdgeOverloadsDelegateToSession() {
    session.createEdgeClass("WrapperEdge");
    session.begin();
    try {
      final var wrapper = new ScriptDatabaseWrapper(session);
      final var from = wrapper.newVertex();
      final var to = wrapper.newVertex();

      final var plainEdge = wrapper.newEdge(from, to);
      assertNotNull("newEdge(from,to) must return a non-null edge", plainEdge);

      final var classedEdge = wrapper.newEdge(from, to, "WrapperEdge");
      assertNotNull(classedEdge);
      assertEquals("WrapperEdge", classedEdge.getSchemaClassName());
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code newBlob()} must delegate to {@code session.newBlob()} and return a non-null blob
   * instance. Pins the factory delegate.
   */
  @Test
  public void newBlobDelegatesToSession() {
    session.begin();
    try {
      final var wrapper = new ScriptDatabaseWrapper(session);
      final var blob = wrapper.newBlob();
      assertNotNull("newBlob() must return a non-null blob", blob);
    } finally {
      session.rollback();
    }
  }

  // ==========================================================================
  // delete — delegate to session.delete; verified via follow-up query.
  // ==========================================================================

  /**
   * {@code delete(record)} must forward to {@code session.delete(record)}. Creates a record,
   * deletes it through the wrapper, and asserts via follow-up query that the record is gone.
   * Pins the delete delegate observably.
   */
  @Test
  public void deleteRemovesTheRecord() {
    session.createClassIfNotExist(WRAPPER_CLASS);

    session.begin();
    var ent = session.newInstance(WRAPPER_CLASS);
    ent.setProperty("marker", "to-delete");
    session.commit();

    final var rid = ent.getIdentity();

    session.begin();
    try {
      final var wrapper = new ScriptDatabaseWrapper(session);
      wrapper.delete(session.load(rid));
    } finally {
      session.commit();
    }

    session.begin();
    try {
      try (var rs =
          session.query(
              "select from " + WRAPPER_CLASS + " where marker = 'to-delete'")) {
        assertEquals("record must be gone after delete", 0, rs.stream().count());
      }
    } finally {
      session.rollback();
    }
  }

  // ==========================================================================
  // Transaction control — begin / commit / rollback delegates.
  // ==========================================================================

  /**
   * {@code begin()} must forward to {@code session.begin()} — observable because the
   * transaction becomes active. Pins the delegate.
   */
  @Test
  public void beginStartsATransactionOnTheSession() {
    final var wrapper = new ScriptDatabaseWrapper(session);
    wrapper.begin();
    try {
      assertTrue(
          "begin() must start an active transaction on the underlying session",
          session.isTxActive());
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code commit()} must forward to {@code session.commit()}. After wrapper.commit the
   * transaction is no longer active. Pins the delegate.
   */
  @Test
  public void commitEndsTheActiveTransactionOnTheSession() {
    session.begin();
    final var wrapper = new ScriptDatabaseWrapper(session);
    wrapper.commit();
    assertFalse(
        "commit() must end the active transaction",
        session.isTxActive());
  }

  /**
   * {@code rollback()} must forward to {@code session.rollback()}. After wrapper.rollback the
   * transaction is no longer active and pending changes are discarded. Pins the delegate.
   */
  @Test
  public void rollbackEndsTheActiveTransactionOnTheSession() {
    session.begin();
    final var wrapper = new ScriptDatabaseWrapper(session);
    wrapper.rollback();
    assertFalse(
        "rollback() must end the active transaction",
        session.isTxActive());
  }
}
