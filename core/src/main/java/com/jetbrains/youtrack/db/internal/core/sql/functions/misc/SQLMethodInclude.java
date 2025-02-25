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
package com.jetbrains.youtrack.db.internal.core.sql.functions.misc;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.AbstractSQLMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Filter the content by including only some fields. If the content is a entity, then creates a copy
 * with only the included fields. If it's a collection of documents it acts against on each single
 * entry.
 *
 * <p>
 *
 * <p>Syntax:
 *
 * <blockquote>
 *
 * <p>
 *
 * <pre>
 * include(&lt;field|value|expression&gt; [,&lt;field-name&gt;]* )
 * </pre>
 *
 * <p>
 *
 * </blockquote>
 *
 * <p>
 *
 * <p>Examples:
 *
 * <blockquote>
 *
 * <p>
 *
 * <pre>
 * SELECT <b>include(roles, 'name')</b> FROM OUser
 * </pre>
 *
 * <p>
 *
 * </blockquote>
 */
public class SQLMethodInclude extends AbstractSQLMethod {

  public static final String NAME = "include";

  public SQLMethodInclude() {
    super(NAME, 1, -1);
  }

  @Override
  public String getSyntax() {
    return "Syntax error: include([<field-name>][,]*)";
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      CommandContext iContext,
      Object ioResult,
      Object[] iParams) {

    var db = iContext.getDatabaseSession();
    if (iParams[0] != null) {
      if (iThis instanceof Identifiable) {
        try {
          iThis = ((Identifiable) iThis).getRecord(db);
        } catch (RecordNotFoundException rnf) {
          return null;
        }
      } else if (iThis instanceof Result result) {
        iThis = result.asEntity();
      }
      if (iThis instanceof EntityImpl) {
        // ACT ON SINGLE ENTITY
        return copy((EntityImpl) iThis, iParams);
      } else if (iThis instanceof Map) {
        // ACT ON MAP
        return copy((Map) iThis, iParams);
      } else if (MultiValue.isMultiValue(iThis)) {
        // ACT ON MULTIPLE DOCUMENTS
        final List<Object> result = new ArrayList<Object>(MultiValue.getSize(iThis));
        for (var o : MultiValue.getMultiValueIterable(iThis)) {
          if (o instanceof Identifiable) {
            try {
              var record = ((Identifiable) o).getRecord(db);
              result.add(copy((EntityImpl) record, iParams));
            } catch (RecordNotFoundException rnf) {
              // IGNORE IT
            }
          }
        }
        return result;
      }
    }

    // INVALID, RETURN NULL
    return null;
  }

  private static Object copy(final EntityImpl entity,
      final Object[] iFieldNames) {
    final var ent = new EntityImpl(null);
    for (var iFieldName : iFieldNames) {
      if (iFieldName != null) {

        final var fieldName = iFieldName.toString();

        if (!fieldName.isEmpty() && fieldName.charAt(fieldName.length() - 1) == '*') {
          final var fieldPart = fieldName.substring(0, fieldName.length() - 1);
          final List<String> toInclude = new ArrayList<String>();
          for (var f : entity.fieldNames()) {
            if (f.startsWith(fieldPart)) {
              toInclude.add(f);
            }
          }

          for (var f : toInclude) {
            ent.field(fieldName, entity.field(f));
          }

        } else {
          ent.field(fieldName, entity.field(fieldName));
        }
      }
    }
    return ent;
  }

  private static Object copy(final Map map,
      final Object[] iFieldNames) {
    final var entity = new EntityImpl(null);
    for (var iFieldName : iFieldNames) {
      if (iFieldName != null) {
        final var fieldName = iFieldName.toString();

        if (!fieldName.isEmpty() && fieldName.charAt(fieldName.length() - 1) == '*') {
          final var fieldPart = fieldName.substring(0, fieldName.length() - 1);
          final List<String> toInclude = new ArrayList<String>();
          for (var f : map.keySet()) {
            if (f.toString().startsWith(fieldPart)) {
              toInclude.add(f.toString());
            }
          }

          for (var f : toInclude) {
            entity.field(fieldName, map.get(f));
          }

        } else {
          entity.field(fieldName, map.get(fieldName));
        }
      }
    }
    return entity;
  }
}
