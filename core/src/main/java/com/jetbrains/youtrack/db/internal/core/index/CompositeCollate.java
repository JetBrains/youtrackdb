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
package com.jetbrains.youtrack.db.internal.core.index;

import com.jetbrains.youtrack.db.api.schema.Collate;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Collate implementation used on composite indexes.
 */
public class CompositeCollate implements Collate {

  private static final long serialVersionUID = 8683726773893639905L;
  private final AbstractIndexDefinition oCompositeIndexDefinition;

  /**
   * @param oCompositeIndexDefinition
   */
  public CompositeCollate(final AbstractIndexDefinition oCompositeIndexDefinition) {
    this.oCompositeIndexDefinition = oCompositeIndexDefinition;
  }

  private final List<Collate> collates = new ArrayList<Collate>();

  public void addCollate(Collate collate) {
    collates.add(collate);
  }

  @Override
  public @Nonnull String getName() {
    throw new UnsupportedOperationException("getName");
  }

  @SuppressWarnings("unchecked")
  @Override
  public @Nullable Object transform(final @Nullable Object obj) {
    final List<Object> keys;
    if (obj instanceof CompositeKey compositeKey) {
      keys = compositeKey.getKeys();
    } else if (obj instanceof List) {
      keys = (List<Object>) obj;
    } else {
      throw new IndexException(
          "Impossible add as key of a CompositeIndex a value of type " + obj.getClass());
    }

    final var transformedKey = new CompositeKey();

    final var size = Math.min(keys.size(), collates.size());
    for (var i = 0; i < size; i++) {
      final var key = keys.get(i);

      final var collate = collates.get(i);
      transformedKey.addKey(collate.transform(key));
    }

    for (var i = size; i < keys.size(); i++) {
      transformedKey.addKey(keys.get(i));
    }

    return transformedKey;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    final var that = (CompositeCollate) o;

    return collates.equals(that.collates);
  }

  @Override
  public int hashCode() {
    return collates.hashCode();
  }

  public List<Collate> getCollates() {
    return collates;
  }

  @Override
  public String toString() {
    return "CompositeCollate{"
        + "collates="
        + collates
        + ", null values ignored = "
        + this.oCompositeIndexDefinition.isNullValuesIgnored()
        + '}';
  }
}
