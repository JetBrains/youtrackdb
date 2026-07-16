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

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import org.junit.Test;

/**
 * Abstract-state pins for {@link StorageComponentException} — the abstract intermediate base
 * shared by {@link CollectionPositionMapException} and {@link PaginatedCollectionException}. The
 * class encapsulates the (component, message) → componentName forwarding into {@link
 * CoreException} that both subclasses depend on.
 *
 * <p>The minimal concrete subclass below pins the abstract-base ctor delegation directly, so a
 * future refactor that drops {@code component.getName()} from {@code super(...)} fails loudly
 * even if both concrete subclasses' tests pass.
 */
public class StorageComponentExceptionTest {

  /** Minimal concrete subclass for direct {@link StorageComponentException} ctor testing. */
  static class ConcreteStorageComponentException extends StorageComponentException {

    public ConcreteStorageComponentException(StorageComponentException exception) {
      super(exception);
    }

    public ConcreteStorageComponentException(
        String dbName, String message, StorageComponent component) {
      super(dbName, message, component);
    }
  }

  /**
   * The {@code (dbName, message, StorageComponent)} ctor must pull the component's name and
   * forward it into the underlying {@link CoreException} as {@code componentName}. Pin via the
   * "Component Name=…" decoration on {@code getMessage()}.
   */
  @Test
  public void componentConstructorPropagatesComponentName() {
    var component = mock(StorageComponent.class);
    when(component.getName()).thenReturn("storage-comp-x");
    // The exception reports the user-facing display name (real components fall back
    // to the component name when no display name is installed; a mock must stub it).
    when(component.getDisplayName()).thenReturn("storage-comp-x");

    var ex = new ConcreteStorageComponentException("dbX", "boom", component);

    assertThat(ex.getMessage()).contains("boom").contains("Component Name=\"storage-comp-x\"");
    assertThat(ex.getDbName()).isEqualTo("dbX");
  }

  /**
   * The copy ctor must propagate message and dbName via the {@link BaseException} copy chain.
   */
  @Test
  public void copyConstructorPreservesMessageAndDbName() {
    var component = mock(StorageComponent.class);
    when(component.getName()).thenReturn("storage-comp-y");
    // The exception reports the user-facing display name (real components fall back
    // to the component name when no display name is installed; a mock must stub it).
    when(component.getDisplayName()).thenReturn("storage-comp-y");

    var original = new ConcreteStorageComponentException("dbX", "boom", component);
    var copy = new ConcreteStorageComponentException(original);

    assertThat(copy.getMessage()).contains("boom");
    assertThat(copy.getDbName()).isEqualTo("dbX");
  }
}
