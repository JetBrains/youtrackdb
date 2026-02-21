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
package com.jetbrains.youtrackdb.internal.core.collate;

import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Default collate, does not apply conversions.
 */
public class DefaultCollate extends DefaultComparator implements Collate {

  public static final String NAME = "default";

  @Override
  public @Nonnull String getName() {
    return NAME;
  }

  @Override
  public @Nullable Object transform(final @Nullable Object obj) {
    return obj;
  }

  @Override
  public int hashCode() {
    return NAME.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof DefaultCollate;
  }

  @Override
  public String toString() {
    return "{" + getClass().getSimpleName() + " : name = " + NAME + "}";
  }
}
