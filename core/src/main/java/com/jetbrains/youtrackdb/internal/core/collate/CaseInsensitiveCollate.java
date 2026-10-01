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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Case insensitive collate.
 *
 * <p>{@link #compareForOrderBy} compares folded forms only. Case variants of one string tie, so a
 * later {@code ORDER BY} / {@code by(...)} key (or a trailing {@code @rid} micro-tie-break) can
 * decide.
 */
public class CaseInsensitiveCollate extends DefaultComparator implements Collate {

  public static final String NAME = "ci";

  @Override
  public @Nonnull String getName() {
    return NAME;
  }

  @Override
  public @Nullable Object transform(final @Nullable Object obj) {
    if (obj instanceof String s) {
      return s.toLowerCase(Locale.ENGLISH);
    }

    if (obj instanceof Set<?> set) {
      Set<Object> result = new HashSet<>();
      for (var o : set) {
        result.add(transform(o));
      }
      return result;
    }

    if (obj instanceof List<?> list) {
      List<Object> result = new ArrayList<>();
      for (var o : list) {
        result.add(transform(o));
      }
      return result;
    }
    return obj;
  }

  @Override
  public int hashCode() {
    return NAME.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof CaseInsensitiveCollate;
  }

  @Override
  public int compareForOrderBy(@Nonnull Object objectOne, @Nonnull Object objectTwo) {
    return super.compare(transform(objectOne), transform(objectTwo));
  }

  @Override
  public String toString() {
    return "{" + getClass().getSimpleName() + " : name = " + NAME + "}";
  }
}
