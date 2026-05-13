/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.api.exception;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.Test;

/**
 * Coverage for the {@link HighLevelException} marker interface. Marker interfaces have no
 * methods so the only meaningful coverage shape is "the three concrete exceptions in
 * {@code api.exception} that declare {@code implements HighLevelException} continue to
 * register as instances of the marker." Pinning the relationship guards against an accidental
 * removal of {@code implements HighLevelException} which would silently change exception
 * routing in {@link com.jetbrains.youtrackdb.internal.core.exception.BaseException#wrapException}
 * — that helper has a {@code if (cause instanceof HighLevelException)} branch that returns the
 * cause without wrapping when the marker is present, so silently dropping the marker would
 * change wrapping behaviour.
 */
public class HighLevelExceptionTest {

  /**
   * {@link ConcurrentModificationException} implements the marker. The instance check is
   * deliberately broad — any subclass of the public exception inherits the marker.
   */
  @Test
  public void concurrentModificationExceptionIsHighLevel() {
    var ex =
        new ConcurrentModificationException(
            "dbX",
            new RecordId(7, 42),
            99L,
            1L,
            com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation.UPDATED);
    assertTrue(ex instanceof HighLevelException);
  }

  /**
   * {@link RecordDuplicatedException} implements the marker.
   */
  @Test
  public void recordDuplicatedExceptionIsHighLevel() {
    var ex =
        new RecordDuplicatedException(null, "msg", "Idx", new RecordId(7, 42), "k");
    assertTrue(ex instanceof HighLevelException);
  }

  /**
   * {@link RecordNotFoundException} implements the marker.
   */
  @Test
  public void recordNotFoundExceptionIsHighLevel() {
    var ex = new RecordNotFoundException("dbX", new RecordId(7, 42));
    assertTrue(ex instanceof HighLevelException);
  }

  /**
   * The marker interface itself declares no methods — pinning the contract so a future
   * regression that adds a method to the marker (turning it into an SPI) breaks loudly. The
   * declared-methods reflection is exact: {@code 0} methods total.
   */
  @Test
  public void markerInterfaceDeclaresNoMethods() {
    assertEquals(0, HighLevelException.class.getDeclaredMethods().length);
    assertTrue(HighLevelException.class.isInterface());
  }
}
