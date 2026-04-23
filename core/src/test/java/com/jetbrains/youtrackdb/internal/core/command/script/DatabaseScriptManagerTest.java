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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.sql.SQLScriptEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TestUtilsFixture;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link DatabaseScriptManager} covering pool lifecycle — acquire/release for sql and
 * javascript languages and the close() path.
 *
 * <p>Background: {@link DatabaseScriptManager} wraps a
 * {@link com.jetbrains.youtrackdb.internal.common.concur.resource.ResourcePoolFactory} keyed by
 * language. The inner
 * {@link com.jetbrains.youtrackdb.internal.common.concur.resource.ResourcePoolListener#createNewResource}
 * delegates to {@link ScriptManager#getEngine} and eagerly evaluates the function library (so a
 * malformed stored function throws at acquire time). The
 * {@link com.jetbrains.youtrackdb.internal.common.concur.resource.ResourcePoolListener#reuseResource}
 * method is a defensive filter that rejects cross-language engines — in practice each pool is
 * keyed by language so a same-language engine always comes back to the same pool, and the false
 * branches only fire if an engine is externally injected into the wrong pool (not expressible
 * through the public API).
 */
public class DatabaseScriptManagerTest extends TestUtilsFixture {

  private ScriptManager scriptManager;

  @Before
  public void grabScriptManager() {
    scriptManager =
        ((YouTrackDBInternalEmbedded) YouTrackDBInternal.extract(youTrackDB)).getScriptManager();
  }

  @Test
  public void acquireSqlEngineReturnsSqlScriptEngineInstance() {
    final var dbm = new DatabaseScriptManager(scriptManager, session.getDatabaseName());
    try {
      final var engine = dbm.acquireEngine(session, "sql");
      try {
        assertNotNull(engine);
        assertTrue(
            "Expected SQLScriptEngine for 'sql', got " + engine.getClass().getName(),
            engine instanceof SQLScriptEngine);
      } finally {
        dbm.releaseEngine("sql", engine);
      }
    } finally {
      dbm.close();
    }
  }

  @Test
  public void releaseAndReacquireReturnsEngineFromPool() {
    // Demonstrates same-language pool reuse: release then re-acquire the same language and
    // observe that the same engine instance is returned (pool hit via reuseResource → true).
    final var dbm = new DatabaseScriptManager(scriptManager, session.getDatabaseName());
    try {
      final var first = dbm.acquireEngine(session, "sql");
      dbm.releaseEngine("sql", first);
      final var second = dbm.acquireEngine(session, "sql");
      try {
        // TB3 fix: pin instance identity — assertEquals(first.getClass(), second.getClass())
        // passed even if the pool created a fresh engine every time (class equality always
        // holds for two SQLScriptEngine instances). assertSame is the observable proof that
        // ResourcePool returned the previously-released entry (reuseResource=true branch).
        assertSame(
            "ResourcePool must return the previously-released instance "
                + "(proves reuseResource=true and resources.poll found the entry)",
            first,
            second);
      } finally {
        dbm.releaseEngine("sql", second);
      }
    } finally {
      dbm.close();
    }
  }

  @Test
  public void differentLanguagesHaveSeparatePools() {
    // Each language keys an independent pool via ResourcePoolFactory.get(language). Acquiring
    // "sql" and "javascript" must return engines of distinct types.
    final var dbm = new DatabaseScriptManager(scriptManager, session.getDatabaseName());
    try {
      final var sql = dbm.acquireEngine(session, "sql");
      try {
        final var js = dbm.acquireEngine(session, "javascript");
        try {
          assertNotNull(sql);
          assertNotNull(js);
          // Distinct factories: sql produces SQLScriptEngine, javascript produces a JSR-223
          // or Graal polyglot engine (never SQLScriptEngine).
          assertTrue(sql instanceof SQLScriptEngine);
          assertFalse(
              "javascript engine must not be SQLScriptEngine",
              js instanceof SQLScriptEngine);
        } finally {
          dbm.releaseEngine("javascript", js);
        }
      } finally {
        dbm.releaseEngine("sql", sql);
      }
    } finally {
      dbm.close();
    }
  }

  @Test
  public void closeIsIdempotent() {
    final var dbm = new DatabaseScriptManager(scriptManager, session.getDatabaseName());
    final var engine = dbm.acquireEngine(session, "sql");
    dbm.releaseEngine("sql", engine);
    // Close once and again — second close must be a no-op (no exception thrown).
    dbm.close();
    dbm.close();
  }

  @Test
  public void createNewResourcePopulatesLibraryWhenPresent() {
    // When a stored function exists with language=javascript and code, the createNewResource
    // path eagerly evaluates the library into a newly-acquired javascript engine so the
    // function is callable from scripts on that engine. This test exercises the
    // library != null branch at DatabaseScriptManager:52-58.
    final var fname = "LibFn" + System.nanoTime();
    session.begin();
    final var f = session.getMetadata().getFunctionLibrary().createFunction(fname);
    f.setCode("function " + fname + "() { return 42; }");
    f.setLanguage("javascript");
    session.commit();
    try {
      final var dbm = new DatabaseScriptManager(scriptManager, session.getDatabaseName());
      try {
        // Acquire must succeed — library is evaluated inside createNewResource without
        // throwing a CommandScriptException.
        final var engine = dbm.acquireEngine(session, "javascript");
        try {
          assertNotNull(engine);
        } finally {
          dbm.releaseEngine("javascript", engine);
        }
      } finally {
        dbm.close();
      }
    } finally {
      session.begin();
      session.getMetadata().getFunctionLibrary().dropFunction(session, fname);
      session.commit();
    }
  }

  @Test
  public void releaseEngineForLanguageNotPreviouslyAcquiredDoesNotThrow() {
    // CQ1/TB4 fix: the previous version acquired+released "sql" on the SAME language — the
    // foreign-language branch was never exercised. This version acquires "sql" then releases
    // into a DIFFERENT, never-acquired language key. DatabaseScriptManager.releaseEngine
    // calls pooledEngines.get(language).returnResource(...); the ResourcePoolFactory lazily
    // creates a pool for the foreign key, and returnResource must tolerate an engine whose
    // listener does not match the pool (the defensive reuseResource-returns-false branch).
    final var dbm = new DatabaseScriptManager(scriptManager, session.getDatabaseName());
    try {
      final var sqlEngine = dbm.acquireEngine(session, "sql");
      assertNotNull(sqlEngine);
      // Release the sql engine into a pool keyed by a never-acquired language. Must not
      // throw — the observable is absence-of-exception. The engine itself is not returned to
      // the sql pool (intentional: we then also release it via the correct sql pool below to
      // keep pool bookkeeping clean).
      final var foreignLang = "never-acquired-" + System.nanoTime();
      dbm.releaseEngine(foreignLang, sqlEngine);
      // Cleanup through the correct pool so close() tears down bookkeeping without warnings.
      dbm.releaseEngine("sql", sqlEngine);
    } finally {
      dbm.close();
    }
  }

  @Test
  public void acquireAfterCloseCreatesFreshPool() {
    // Validates that close() fully drains state — subsequent operations work as if on a
    // fresh DatabaseScriptManager (except that the enclosing object is reused; we verify
    // by constructing a second DatabaseScriptManager with the same dbName).
    final var dbm1 = new DatabaseScriptManager(scriptManager, session.getDatabaseName());
    final var e1 = dbm1.acquireEngine(session, "sql");
    dbm1.releaseEngine("sql", e1);
    dbm1.close();

    final var dbm2 = new DatabaseScriptManager(scriptManager, session.getDatabaseName());
    try {
      final var e2 = dbm2.acquireEngine(session, "sql");
      try {
        assertNotNull(e2);
      } finally {
        dbm2.releaseEngine("sql", e2);
      }
    } finally {
      dbm2.close();
    }
  }

  @Test
  public void createNewResourceWithNullLibraryDoesNotFailAcquire() {
    // Default DB has no user functions in "unknown-nobody-lang" or any lang — getLibrary
    // returns null → the `if (library != null)` branch at DatabaseScriptManager:52 is
    // skipped. Acquire for javascript still returns a valid engine.
    final var dbm = new DatabaseScriptManager(scriptManager, session.getDatabaseName());
    try {
      final var engine = dbm.acquireEngine(session, "javascript");
      try {
        assertNotNull(engine);
      } finally {
        dbm.releaseEngine("javascript", engine);
      }
    } finally {
      dbm.close();
    }
  }
}
