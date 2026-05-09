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

import org.junit.Test;

/**
 * Tests for {@link CommonStorageComponentException} — outside the parameterized fan because the
 * primary public ctor signature is {@code (String message, String componentName, String dbName)}
 * with arguments in a non-canonical order (component name in the middle, dbName last). Pin both
 * the round-trip into the {@link CoreException} parent's decorating {@code getMessage()} and the
 * copy ctor.
 */
public class CommonStorageComponentExceptionTest {

  /**
   * The 3-arg ctor must round-trip the message, dbName, and componentName into {@link
   * CoreException}'s decorated {@code getMessage()}. Pin all three observables.
   */
  @Test
  public void threeArgConstructorRoundTripsAllFields() {
    var ex = new CommonStorageComponentException("boom", "comp-a", "dbX");

    assertThat(ex.getMessage())
        .contains("boom")
        .contains("DB Name=\"dbX\"")
        .contains("Component Name=\"comp-a\"");
    assertThat(ex.getDbName()).isEqualTo("dbX");
  }

  /** The copy ctor preserves message and dbName via the {@link BaseException} chain. */
  @Test
  public void copyConstructorPreservesMessageAndDbName() {
    var original = new CommonStorageComponentException("boom", "comp-a", "dbX");
    var copy = new CommonStorageComponentException(original);

    assertThat(copy.getMessage()).contains("boom");
    assertThat(copy.getDbName()).isEqualTo("dbX");
  }
}
