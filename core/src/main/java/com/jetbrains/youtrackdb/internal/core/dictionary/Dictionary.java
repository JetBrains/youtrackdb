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
package com.jetbrains.youtrackdb.internal.core.dictionary;

import com.jetbrains.youtrackdb.internal.core.index.Index;

@SuppressWarnings({"unchecked", "DeprecatedIsStillUsed"})
@Deprecated
public class Dictionary<T> {

  private final Index index;

  public Dictionary(final Index iIndex) {
    index = iIndex;
  }

  public <RET extends T> RET get(final String iKey) {
    throw new UnsupportedOperationException();
  }

  public <RET extends T> RET get(final String iKey, final String fetchPlan) {
    throw new UnsupportedOperationException();
  }

  public void put(final String iKey, final Object iValue) {
    throw new UnsupportedOperationException();
  }

  public boolean remove(final String iKey) {
    throw new UnsupportedOperationException();
  }

  public long size() {
    throw new UnsupportedOperationException();
  }

  public Index getIndex() {
    return index;
  }
}
