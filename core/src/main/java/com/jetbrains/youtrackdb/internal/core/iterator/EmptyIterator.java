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
package com.jetbrains.youtrackdb.internal.core.iterator;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.Iterator;
import javax.annotation.Nullable;

/**
 * Empty iterator against Object.
 */
public class EmptyIterator<T> implements Iterator<T> {

  public static final EmptyIterator<Object> ANY_INSTANCE = new EmptyIterator<>();
  public static final EmptyIterator<RID> IDENTIFIABLE_INSTANCE = new EmptyIterator<>();

  @Override
  public boolean hasNext() {
    return false;
  }

  @Override
  @Nullable
  public T next() {
    return null;
  }

  @Override
  public void remove() {
  }
}
