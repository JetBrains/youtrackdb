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

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Collate;
import java.util.Set;

/**
 * the Collating strategy when comparison in SQL statement is required.
 */
public interface CollateFactory {

  /**
   * Returns the set of collate names supported by this factory.
   *
   * @return Set of supported collate names of this factory
   */
  Set<String> getNames();

  /**
   * Returns the requested collate
   *
   * @param name the name of the collate to retrieve
   */
  Collate getCollate(String name);
}
