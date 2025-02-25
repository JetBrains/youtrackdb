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
package com.jetbrains.youtrack.db.internal.core.sql.functions.text;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.AbstractSQLMethod;

/**
 * Converts a document in JSON string.
 */
public class SQLMethodFromJSON extends AbstractSQLMethod {

  public static final String NAME = "fromjson";

  public SQLMethodFromJSON() {
    super(NAME, 0, 1);
  }

  @Override
  public String getSyntax() {
    return "fromJSON([<options>])";
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      CommandContext iContext,
      Object ioResult,
      Object[] iParams) {
    if (iThis instanceof String) {
      var db = iContext.getDatabaseSession();
      if (iParams.length > 0) {
        try {
          final var entity = new EntityImpl(db).updateFromJSON(iThis.toString(),
              iParams[0].toString());
          if (iParams[0].toString().contains("embedded")) {
            entity.setOwner((EntityImpl) iCurrentRecord.castToEntity());
          }

          return entity;
        } catch (RecordNotFoundException e) {
          return null;
        }
      }

      var entity = new EntityImpl(db);
      entity.updateFromJSON(iThis.toString());
      return entity;
    }

    return null;
  }
}
