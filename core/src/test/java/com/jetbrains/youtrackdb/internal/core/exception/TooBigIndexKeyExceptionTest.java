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
package com.jetbrains.youtrackdb.internal.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.exception.HighLevelException;
import org.junit.Test;

/**
 * Bespoke test for {@link TooBigIndexKeyException} — outside the parameterized fan because the
 * only public non-copy ctor takes the bespoke 3-arg shape {@code (String dbName, String message,
 * String componentName)}, and the copy ctor accepts {@link CoreException} (not the leaf type
 * directly).
 */
public class TooBigIndexKeyExceptionTest {

  /**
   * The 3-arg ctor must round-trip the message, dbName, and componentName into {@link
   * CoreException}'s decorated {@code getMessage()}.
   */
  @Test
  public void threeArgConstructorRoundTripsAllFields() {
    var ex = new TooBigIndexKeyException("dbX", "key too big", "indexA");

    assertThat(ex.getMessage())
        .contains("key too big")
        .contains("DB Name=\"dbX\"")
        .contains("Component Name=\"indexA\"");
    assertThat(ex.getDbName()).isEqualTo("dbX");
  }

  /**
   * The copy ctor (note: typed as {@link CoreException}) must propagate the message and dbName
   * via the {@link BaseException} chain.
   */
  @Test
  public void coreExceptionCopyConstructorPreservesMessageAndDbName() {
    var original = new TooBigIndexKeyException("dbX", "key too big", "indexA");
    var copy = new TooBigIndexKeyException(original);

    assertThat(copy.getMessage()).contains("key too big");
    assertThat(copy.getDbName()).isEqualTo("dbX");
  }

  /** Pin the {@link HighLevelException} marker so wrap-exception short-circuits propagate. */
  @Test
  public void implementsHighLevelException() {
    var ex = new TooBigIndexKeyException("dbX", "key too big", "indexA");
    assertThat(ex).isInstanceOf(HighLevelException.class);
    assertThat(ex).isInstanceOf(CoreException.class);
  }
}
