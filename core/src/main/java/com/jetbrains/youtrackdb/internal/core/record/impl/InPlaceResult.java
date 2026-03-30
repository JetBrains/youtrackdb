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

package com.jetbrains.youtrackdb.internal.core.record.impl;

/**
 * Tri-state result for in-place property comparison. Replaces {@code Optional<Boolean>} for
 * clarity.
 *
 * <ul>
 *   <li>{@link #TRUE} — the comparison succeeded and the result is true (equal).
 *   <li>{@link #FALSE} — the comparison succeeded and the result is false (not equal).
 *   <li>{@link #FALLBACK} — the comparison could not be performed in-place; the caller must fall
 *       back to the standard deserialization + Java comparison path.
 * </ul>
 */
public enum InPlaceResult {
  TRUE, FALSE, FALLBACK
}
