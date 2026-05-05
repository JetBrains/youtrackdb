/*
 *
 *
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
 *
 */
package com.jetbrains.youtrackdb.internal.core.db.tool.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * Live-driven coverage for {@link LinksRewriter}, the {@link
 * com.jetbrains.youtrackdb.internal.core.db.EntityPropertiesVisitor EntityPropertiesVisitor}
 * implementation that {@link com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseImport
 * DatabaseImport} uses to walk an imported entity's fields and rewrite their RID-bearing
 * payloads. The four visitor arms are:
 *
 * <ul>
 *   <li>{@code visitField} — non-converter-recognised value (scalar/string) passes through;
 *       broken-link sentinel returns {@code null}; mapped rid returns the rewritten value.</li>
 *   <li>{@code goFurther} / {@code goDeeper} — both unconditionally true (the rewriter walks
 *       every field and recurses into every embedded structure).</li>
 *   <li>{@code updateMode} — unconditionally true (the visited results are written back).</li>
 * </ul>
 *
 * <p>Pinning all four arms catches a future contract change to the visitor (e.g., short-circuit
 * on a particular type) at test time rather than during a long import-restore run.
 */
public class LinksRewriterTest extends DbTestBase {

  /**
   * Defensive {@code @After} (rollback safety net).
   */
  @After
  public void rollbackIfLeftOpen() {
    if (session == null || session.isClosed()) {
      return;
    }
    var tx = session.getActiveTransactionOrNull();
    if (tx != null && tx.isActive()) {
      tx.rollback();
    }
  }

  /**
   * A scalar value (string) doesn't dispatch to any converter — the factory returns null and
   * the visitor returns the input unchanged. This is the pass-through arm exercised whenever
   * the field walker encounters non-link fields.
   */
  @Test
  public void testVisitFieldOnScalarReturnsValueUnchanged() {
    var rewriter = new LinksRewriter(new ConverterData(session, new HashSet<>()));

    var value = "scalar-value";
    var result = rewriter.visitField(session, null, null, value);

    assertSame("scalar must pass through visitField unchanged", value, result);
  }

  /**
   * A persistent rid that is mapped triggers the {@link LinkConverter} dispatch and the
   * visitor returns the mapped target rid.
   */
  @Test
  public void testVisitFieldOnMappedLinkReturnsTargetRid() {
    var fromRid = new RecordId(10, 4);
    var toRid = new RecordId(10, 3);
    ImporterTestFixtures.setupRidMapping(session, fromRid, toRid);

    var rewriter = new LinksRewriter(new ConverterData(session, new HashSet<>()));

    var result = rewriter.visitField(session, null, null, fromRid);

    assertEquals("mapped link rewrites to target rid", toRid, result);
  }

  /**
   * A broken rid causes {@link LinkConverter} to return the {@code BROKEN_LINK} sentinel; the
   * visitor maps that to {@code null} (the field is then dropped from the entity by the
   * surrounding walker). Equality is checked by reference because the sentinel comparison in
   * {@code visitField} uses {@code ==}.
   */
  @Test
  public void testVisitFieldOnBrokenLinkReturnsNull() {
    var brokenRid = new RecordId(7, 1);
    Set<RID> brokenRids = new HashSet<>();
    brokenRids.add(brokenRid);
    ImporterTestFixtures.setupRidMapping(session, new RecordId(99, 0), new RecordId(99, 1));

    var rewriter = new LinksRewriter(new ConverterData(session, brokenRids));

    var result = rewriter.visitField(session, null, null, brokenRid);

    assertNull("broken-link sentinel must be visited as null", result);
  }

  /**
   * An unmapped persistent rid passes through unchanged — the converter looked it up and
   * found no mapping, so the visitor returns the original value.
   */
  @Test
  public void testVisitFieldOnUnmappedLinkPassesThrough() {
    ImporterTestFixtures.setupRidMapping(session, new RecordId(10, 4), new RecordId(10, 3));

    var rewriter = new LinksRewriter(new ConverterData(session, new HashSet<>()));

    var unmappedRid = new RecordId(20, 5);
    var result = rewriter.visitField(session, null, null, unmappedRid);

    assertEquals("unmapped persistent rid must pass through", unmappedRid, result);
  }

  /**
   * The {@code goFurther} arm is unconditionally true — the rewriter must walk every field of
   * the entity. Pinning the constant catches a refactor that adds early-exit logic.
   */
  @Test
  public void testGoFurtherAlwaysTrue() {
    var rewriter = new LinksRewriter(new ConverterData(session, new HashSet<>()));

    assertTrue(rewriter.goFurther(null, null, null, null));
    assertTrue(rewriter.goFurther(null, null, "any", "any"));
  }

  /**
   * The {@code goDeeper} arm is unconditionally true — the rewriter must recurse into every
   * embedded collection so nested rids are rewritten too. Pinning the constant catches a
   * refactor that adds early-exit logic on type/value.
   */
  @Test
  public void testGoDeeperAlwaysTrue() {
    var rewriter = new LinksRewriter(new ConverterData(session, new HashSet<>()));

    assertTrue(rewriter.goDeeper(null, null, null));
    assertTrue(rewriter.goDeeper(null, null, "any"));
  }

  /**
   * The {@code updateMode} arm is unconditionally true — visited values are always written
   * back. Pinning the constant catches a refactor that flips the writer into a dry-run mode
   * silently.
   */
  @Test
  public void testUpdateModeAlwaysTrue() {
    var rewriter = new LinksRewriter(new ConverterData(session, new HashSet<>()));

    assertTrue(rewriter.updateMode());
  }
}
