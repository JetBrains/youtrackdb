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
package com.jetbrains.youtrack.db.internal.core.sql.filter;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.schema.Collate;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.query.QueryRuntimeValueMulti;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents one or more object fields as value in the query condition.
 */
public abstract class SQLFilterItemFieldMultiAbstract extends SQLFilterItemAbstract {

  private final List<String> names;
  private final SchemaClass clazz;
  private final List<Collate> collates = new ArrayList<Collate>();

  public SQLFilterItemFieldMultiAbstract(
      DatabaseSessionInternal session, final SQLPredicate iQueryCompiled,
      final String iName,
      final SchemaClass iClass,
      final List<String> iNames) {
    super(session, iQueryCompiled, iName);
    names = iNames;
    clazz = iClass;

    for (var n : iNames) {
      collates.add(getCollateForField(session, iClass, n));
    }
  }

  public Object getValue(
      final Result iRecord, Object iCurrentResult, CommandContext iContext) {

    if (names.size() == 1 && iRecord.isEntity()) {
      var entity = iRecord.castToEntity();
      return transformValue(
          iRecord, iContext,
          EntityHelper.getIdentifiableValue(iContext.getDatabaseSession(), entity,
              names.getFirst()));
    }

    final List<String> fieldNames;
    var propertyNames = iRecord.getPropertyNames();
    if (propertyNames instanceof List<String> list) {
      fieldNames = list;
    } else {
      fieldNames = new ArrayList<>(propertyNames);
    }

    final var values = new Object[fieldNames.size()];

    collates.clear();
    var db = iContext.getDatabaseSession();
    for (var i = 0; i < values.length; ++i) {
      values[i] = iRecord.getProperty(fieldNames.get(i));
      collates.add(getCollateForField(db, clazz, fieldNames.get(i)));
    }

    if (hasChainOperators()) {
      // TRANSFORM ALL THE VALUES
      for (var i = 0; i < values.length; ++i) {
        values[i] = transformValue(iRecord, iContext, values[i]);
      }
    }

    return new QueryRuntimeValueMulti(this, values, collates);
  }
}
