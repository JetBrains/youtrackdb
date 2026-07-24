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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.storage.collection.PaginatedCollection;
import org.junit.Test;

/**
 * Bespoke tests for {@link PaginatedCollectionException} — outside the parameterized fan because
 * the only public non-copy ctor takes a {@link PaginatedCollection} component reference rather
 * than a flat (dbName, message) pair. The ctor delegates to {@link StorageComponentException}
 * which forwards {@code component.getName()} as the {@code componentName} into {@link
 * CoreException}.
 */
public class PaginatedCollectionExceptionTest {

  /**
   * The {@code (String dbName, String message, PaginatedCollection component)} ctor must forward
   * the component name into the underlying {@link CoreException} as {@code componentName}. Pin
   * via the message decoration pattern.
   */
  @Test
  public void componentConstructorPropagatesComponentName() {
    var component = mock(PaginatedCollection.class);
    when(component.getName()).thenReturn("paginated-coll-x");
    // The exception reports the user-facing display name (real components fall back
    // to the component name when no display name is installed; a mock must stub it).
    when(component.getDisplayName()).thenReturn("paginated-coll-x");

    var ex = new PaginatedCollectionException("dbA", "store failed", component);

    assertThat(ex.getMessage())
        .contains("store failed")
        .contains("Component Name=\"paginated-coll-x\"");
    assertThat(ex.getDbName()).isEqualTo("dbA");
  }

  /**
   * The copy ctor must propagate the message and dbName via {@code super(exception)}.
   */
  @Test
  public void copyConstructorPreservesMessageAndDbName() {
    var component = mock(PaginatedCollection.class);
    when(component.getName()).thenReturn("paginated-coll-y");
    // The exception reports the user-facing display name (real components fall back
    // to the component name when no display name is installed; a mock must stub it).
    when(component.getDisplayName()).thenReturn("paginated-coll-y");

    var original = new PaginatedCollectionException("dbA", "store failed", component);
    var copy = new PaginatedCollectionException(original);

    assertThat(copy.getMessage()).contains("store failed");
    assertThat(copy.getDbName()).isEqualTo("dbA");
  }
}
