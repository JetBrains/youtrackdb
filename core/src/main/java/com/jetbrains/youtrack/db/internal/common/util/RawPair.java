/*
 *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at  http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  *  *
 *
 */
package com.jetbrains.youtrack.db.internal.common.util;

/**
 * Container for pair of non null objects.
 *
 * @since 2.2
 */
public record RawPair<V1, V2>(V1 first, V2 second) {

  public V1 getFirst() {
    return first;
  }

  public V2 getSecond() {
    return second;
  }
}
