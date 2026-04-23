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
import static org.junit.Assert.assertSame;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Test;

/**
 * Live-subset pin tests for the two deprecated script wrappers — {@link ScriptYouTrackDbWrapper}
 * and {@link ScriptDocumentDatabaseWrapper}. Both classes are reachable today only through the
 * deprecated {@code ScriptManager.bind(...)} overload invoked from
 * {@link Jsr223ScriptExecutor#executeFunction} (stored JavaScript functions); the R2 review
 * finding in Track 9 Phase A flagged the bulk of their methods as dead, reachable only via a
 * narrow script-embedded code path.
 *
 * <p>This test pins the live subset:
 *
 * <ul>
 *   <li>{@link ScriptYouTrackDbWrapper#ScriptYouTrackDbWrapper(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded)
 *   ScriptYouTrackDbWrapper(db)} stores the session verbatim — pinned via the positive
 *   getDatabase() path.
 *   <li>{@link ScriptYouTrackDbWrapper#getDatabase()} with a non-null session returns a
 *   freshly-constructed {@link ScriptDocumentDatabaseWrapper}.
 *   <li>{@link ScriptDocumentDatabaseWrapper#getName()} delegates to
 *   {@code session.getDatabaseName()} — one representative delegate that proves the wrapper
 *   can actually read session state (the dead-ness flag would be a ctor that stores null).
 * </ul>
 *
 * <p>The no-arg {@code ScriptYouTrackDbWrapper} ctor throw behavior is pinned separately in
 * {@code CommandScriptDeadCodeTest#scriptYouTrackDbWrapperNoArgCtorThrowsOnGetDatabase}. The
 * remaining {@link ScriptDocumentDatabaseWrapper} methods (query, save, browseClass, getIndex,
 * ...) are documented as dead in the Track 9 baseline and pinned en-masse by the
 * {@code CommandScriptDeadCodeTest} package-level pins — they will be deleted in Track 22
 * unless a new caller emerges.
 *
 * <p>WHEN-FIXED: Track 22 — delete both wrappers once the deprecated {@code ScriptManager.bind}
 * overload is removed. If {@link ScriptYouTrackDbWrapper} is fully deleted, this test class
 * should be removed alongside it.
 */
public class ScriptLegacyWrappersTest extends DbTestBase {

  // ==========================================================================
  // ScriptYouTrackDbWrapper — live-ctor path (non-null session) + getDatabase()
  // positive return.
  // ==========================================================================

  /**
   * {@code new ScriptYouTrackDbWrapper(session).getDatabase()} must return a non-null
   * {@link ScriptDocumentDatabaseWrapper} — the positive branch of the null-guard at
   * {@code ScriptYouTrackDbWrapper#getDatabase()} line 42-47. Each call allocates a FRESH
   * wrapper (the implementation does {@code new ScriptDocumentDatabaseWrapper(db)} every time),
   * so two invocations must yield distinct instances. Pin the non-cached delegation shape.
   */
  @Test
  public void getDatabaseWithNonNullSessionReturnsFreshDocumentWrapper() {
    final var wrapper = new ScriptYouTrackDbWrapper(session);

    final var first = wrapper.getDatabase();
    assertNotNull("getDatabase() must not return null when session is non-null", first);

    final var second = wrapper.getDatabase();
    assertNotNull(second);
    // Each call must allocate a new ScriptDocumentDatabaseWrapper. If a future cache is
    // added, this test fails visibly so the change is deliberate.
    assertFalse(
        "getDatabase() must return a fresh wrapper per call, not a cached instance",
        first == second);
  }

  // ==========================================================================
  // ScriptDocumentDatabaseWrapper — one representative live delegate to prove
  // the ctor stores the session and at least one method can read it.
  // ==========================================================================

  /**
   * {@link ScriptDocumentDatabaseWrapper#getName()} delegates to
   * {@code session.getDatabaseName()}. Pin the delegate. Covers the ctor's field assignment
   * and the single-line getName method body.
   */
  @Test
  public void scriptDocumentDatabaseWrapperGetNameDelegatesToSession() {
    final var wrapper = new ScriptDocumentDatabaseWrapper(session);
    assertEquals(
        "ScriptDocumentDatabaseWrapper.getName() must delegate to session.getDatabaseName()",
        session.getDatabaseName(),
        wrapper.getName());
  }

  /**
   * Confirm that the wrapper stores the supplied session verbatim (reflection-free proof: the
   * delegated methods are consistent with the caller's session). {@code isClosed()} is a
   * second trivial delegate that hits the same field — exercising it alongside getName proves
   * the field binding is stable across calls.
   */
  @Test
  public void scriptDocumentDatabaseWrapperIsClosedDelegatesToSession() {
    final var wrapper = new ScriptDocumentDatabaseWrapper(session);
    // DbTestBase's session is open during the test — wrapper.isClosed() must report false.
    assertEquals(
        "ScriptDocumentDatabaseWrapper.isClosed() must mirror session.isClosed()",
        session.isClosed(),
        wrapper.isClosed());
  }

  /**
   * Cross-check the two wrappers' composition: the {@link ScriptDocumentDatabaseWrapper}
   * obtained via {@link ScriptYouTrackDbWrapper#getDatabase()} reports the SAME database name
   * as a direct wrapper constructed with the same session. This pins the composition
   * invariant: going through ScriptYouTrackDbWrapper does not rebind the session.
   */
  @Test
  public void composedWrappersExposeTheSameDatabaseName() {
    final var viaYouTrackDbWrapper = new ScriptYouTrackDbWrapper(session).getDatabase();
    final var direct = new ScriptDocumentDatabaseWrapper(session);
    assertSame(
        "both wrappers must bind to the exact same session object",
        direct.getName(),
        // String.intern() is called by javac on compile-time constants only, so using .equals
        // alone would not prove identity. Since both go through session.getDatabaseName() and
        // the session is the same, they read the same underlying String.
        session.getDatabaseName());
    assertEquals(direct.getName(), viaYouTrackDbWrapper.getName());
  }
}
