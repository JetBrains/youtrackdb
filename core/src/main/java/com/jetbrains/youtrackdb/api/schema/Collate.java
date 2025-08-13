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
package com.jetbrains.youtrackdb.api.schema;

import com.jetbrains.youtrackdb.internal.common.comparator.DefaultComparator;
import com.jetbrains.youtrackdb.internal.core.collate.CaseInsensitiveCollate;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import java.io.Serializable;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Specify the Collating strategy when comparison in SQL statement is required.
 */
public interface Collate extends Serializable {

  @Nonnull
  static Collate caseInsensitiveCollate() {
    return new CaseInsensitiveCollate();
  }

  @Nonnull
  static Collate defaultCollate() {
    return new DefaultCollate();
  }

  @Nonnull
  String getName();

  @Nullable
  Object transform(@Nullable Object obj);

  default int compareForOrderBy(@Nonnull Object o1, @Nonnull Object o2) {
    return DefaultComparator.INSTANCE.compare(transform(o1), transform(o2));
  }
}
