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
package com.jetbrains.youtrack.db.internal.core.query;

import com.jetbrains.youtrack.db.api.schema.Collate;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemFieldMultiAbstract;
import java.util.List;

/**
 * Represent multiple values in query.
 */
public class QueryRuntimeValueMulti {

  protected final SQLFilterItemFieldMultiAbstract definition;
  protected final List<Collate> collates;
  protected final Object[] values;

  public QueryRuntimeValueMulti(
      final SQLFilterItemFieldMultiAbstract iDefinition,
      final Object[] iValues,
      final List<Collate> iCollates) {
    definition = iDefinition;
    values = iValues;
    collates = iCollates;
  }

  @Override
  public String toString() {
    if (getValues() == null) {
      return "";
    }

    var buffer = new StringBuilder(128);
    buffer.append("[");
    var i = 0;
    for (var v : getValues()) {
      if (i++ > 0) {
        buffer.append(",");
      }
      buffer.append(v);
    }
    buffer.append("]");
    return buffer.toString();
  }

  public SQLFilterItemFieldMultiAbstract getDefinition() {
    return definition;
  }

  public Collate getCollate(final int iIndex) {
    return collates.get(iIndex);
  }

  public Object[] getValues() {
    return values;
  }
}
