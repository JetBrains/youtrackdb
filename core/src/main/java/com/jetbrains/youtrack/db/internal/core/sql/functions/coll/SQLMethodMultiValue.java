/*
 *
 * Copyright 2013 Geomatys.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrack.db.internal.core.sql.functions.coll;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.AbstractSQLMethod;
import java.util.ArrayList;
import java.util.List;

/**
 * Works against multi value objects like collections, maps and arrays.
 */
public class SQLMethodMultiValue extends AbstractSQLMethod {

  public static final String NAME = "multivalue";

  public SQLMethodMultiValue() {
    super(NAME, 1, -1);
  }

  @Override
  public String getSyntax() {
    return "multivalue(<index>)";
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      CommandContext iContext,
      Object ioResult,
      Object[] iParams) {
    if (iThis == null || iParams[0] == null) {
      return null;
    }

    if (iParams.length == 1 && !MultiValue.isMultiValue(iParams[0])) {
      return EntityHelper.getFieldValue(iContext.getDatabaseSession(), iThis, iParams[0].toString(),
          iContext);
    }

    var database = iContext.getDatabaseSession();
    // MULTI VALUES
    final List<Object> list = new ArrayList<Object>();
    for (var iParam : iParams) {
      if (MultiValue.isMultiValue(iParam)) {
        for (var o : MultiValue.getMultiValueIterable(iParam)) {
          list.add(EntityHelper.getFieldValue(database, iThis, o.toString(), iContext));
        }
      } else {
        list.add(EntityHelper.getFieldValue(database, iThis, iParam.toString(), iContext));
      }
    }

    if (list.size() == 1) {
      return list.get(0);
    }

    return list;
  }
}
