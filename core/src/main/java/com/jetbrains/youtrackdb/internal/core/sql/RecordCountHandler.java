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

package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;

/**
 * A {@link ReturnHandler} that counts the number of processed records.
 */
public class RecordCountHandler implements ReturnHandler {

  private int count = 0;

  @Override
  public void reset() {
    count = 0;
  }

  @Override
  public void beforeUpdate(EntityImpl result) {
  }

  @Override
  public void afterUpdate(EntityImpl result) {
    count++;
  }

  @Override
  public Object ret() {
    return count;
  }
}
