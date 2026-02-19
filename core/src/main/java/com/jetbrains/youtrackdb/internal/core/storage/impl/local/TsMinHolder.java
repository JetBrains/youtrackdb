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

package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

/**
 * Holds the minimum active operation timestamp for a single thread. Shared between the owning
 * thread (via {@code ThreadLocal}) and the cleanup thread (via the {@code tsMins} set in
 * {@link AbstractStorage}).
 *
 * <p>Non-volatile: stale reads during cleanup are safe — they only make cleanup slightly more
 * conservative (retaining entries a bit longer than strictly necessary).
 */
// Identity-based equals/hashCode (inherited from Object) is required — instances are used
// as WeakHashMap keys in AbstractStorage.tsMins.
final class TsMinHolder {

  long tsMin = Long.MAX_VALUE;

  // Used by lazy registration in AbstractStorage (Leaf 3, YTDB-510): the owning thread checks
  // this flag before calling tsMins.add(this) so registration happens at most once per thread.
  boolean registeredInTsMins;
}
