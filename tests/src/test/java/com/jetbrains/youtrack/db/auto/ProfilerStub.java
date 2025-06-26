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
package com.jetbrains.youtrack.db.auto;

/**
 * This is a leftover from the old profiler implementation, still used in some tests. Not removing
 * it for now, so that the original logic in the tests is clear. Should be removed, when the tests
 * are refactored.
 */
@Deprecated
public class ProfilerStub {

  public static ProfilerStub INSTANCE = new ProfilerStub();

  public long getCounter(String iStatName) {
    return 0;
  }
}
