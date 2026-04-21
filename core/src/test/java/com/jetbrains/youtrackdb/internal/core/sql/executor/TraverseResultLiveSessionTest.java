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
package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * Covers the {@code session != null} half of {@link TraverseResult}'s
 * {@code assertIfNotActive()} branch. The {@link SqlExecutorDeadCodeTest} already pins every
 * observable behavior with a {@code null} session; the remaining branch-coverage gap is the
 * short-circuited right-hand side of {@code session == null || session.assertIfNotActive()}.
 *
 * <p>Kept as a standalone companion file (rather than extending
 * {@link SqlExecutorDeadCodeTest}) because those dead-code pins run standalone and we want the
 * live-session exercise in a {@link com.jetbrains.youtrackdb.internal.DbTestBase}-backed suite.
 *
 * <p>WHEN-FIXED: Track 22 — delete {@link TraverseResult}. These tests will be deleted alongside
 * the class; the dead-code pins in {@link SqlExecutorDeadCodeTest} are the primary markers for
 * the removal.
 */
public class TraverseResultLiveSessionTest extends TestUtilsFixture {

  @Test
  public void depthGetterWithActiveSession() {
    var r = new TraverseResult(session);
    r.setProperty("$depth", 4);
    assertThat((Integer) r.getProperty("$depth")).isEqualTo(4);
  }

  @Test
  public void depthSetterWithActiveSessionIgnoresNonNumber() {
    var r = new TraverseResult(session);
    r.setProperty("$depth", 2);
    // Non-Number input is silently ignored — depth stays at 2.
    r.setProperty("$depth", "not-a-number");
    assertThat((Integer) r.getProperty("$depth")).isEqualTo(2);
    // Non-$depth properties delegate to super.setProperty, which requires checkSession().
    r.setProperty("k", "v");
    assertThat((String) r.getProperty("k")).isEqualTo("v");
  }

  @Test
  public void depthGetterDelegatesForNonDepthPropertiesWithActiveSession() {
    var r = new TraverseResult(session);
    r.setProperty("regular", 42);
    assertThat((Integer) r.getProperty("regular")).isEqualTo(42);
    // getProperty("$depth") when depth was never set returns null via the short-circuit.
    assertThat((Object) r.getProperty("$depth")).isNull();
  }
}
