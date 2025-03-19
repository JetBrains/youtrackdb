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
package com.jetbrains.youtrack.db.internal.core.collate;

import com.jetbrains.youtrack.db.api.schema.Collate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Factory to hold collating strategies to compare values in SQL statement and indexes.
 */
public class DefaultCollateFactory implements CollateFactory {

  private static final Map<String, Collate> COLLATES = new HashMap<String, Collate>(2);

  static {
    register(new DefaultCollate());
    register(new CaseInsensitiveCollate());
  }

  /**
   * @return Set of supported collate names of this factory
   */
  @Override
  public Set<String> getNames() {
    return COLLATES.keySet();
  }

  /**
   * Returns the requested collate
   *
   * @param name
   */
  @Override
  public Collate getCollate(final String name) {
    return COLLATES.get(name.toLowerCase());
  }

  private static void register(final Collate iCollate) {
    COLLATES.put(iCollate.getName().toLowerCase(), iCollate);
  }
}
