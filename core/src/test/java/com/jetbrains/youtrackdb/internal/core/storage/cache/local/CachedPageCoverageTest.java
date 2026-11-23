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
package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CachedPageCoverageTest {

  // Coverage is an ordered suffix query, isolated by file and incarnation, and removed on purge.
  @Test
  public void coverageTracksInsertionRemovalAndReplacement() {
    var coverage = new CachedPageCoverage();
    var first = new LogicalPageFileTable.Extents(10, 0);
    var next = new LogicalPageFileTable.Extents(10, 0);
    assertFalse(coverage.covers(1, 0, first));
    coverage.add(1, 2, first);
    coverage.add(1, 8, first);
    coverage.add(2, 9, first);
    assertTrue(coverage.covers(1, 8, first));
    assertFalse(coverage.covers(1, 9, first));
    assertFalse(coverage.covers(1, 0, next));
    coverage.remove(1, 8);
    assertFalse(coverage.covers(1, 3, first));
    assertTrue(coverage.covers(1, 2, first));
    coverage.add(1, 5, next);
    assertFalse(coverage.covers(1, 0, first));
    assertTrue(coverage.covers(1, 5, next));
    coverage.remove(1, 5);
    coverage.remove(1, 5);
    assertFalse(coverage.covers(1, 0, next));
    assertTrue(coverage.covers(2, 9, first));
  }
}
