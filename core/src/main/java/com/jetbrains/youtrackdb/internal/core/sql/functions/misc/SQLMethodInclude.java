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
package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.AbstractSQLMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

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

  @Nullable
  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      CommandContext iContext,
      Object ioResult,
      Object[] iParams) {

    var session = iContext.getDatabaseSession();
    if (iParams[0] != null) {
      if (iThis instanceof Identifiable id) {
        try {
          var transaction = session.getActiveTransaction();
          iThis = transaction.load(id);
        } catch (RecordNotFoundException rnf) {
          return null;
        }
      } else if (iThis instanceof Result result) {
        iThis = result.asEntityOrNull();
      }
      if (iThis instanceof EntityImpl) {
        // ACT ON SINGLE ENTITY
        return copy((EntityImpl) iThis, iParams, session);
      } else if (iThis instanceof Map) {
        // ACT ON MAP
        return copy((Map) iThis, iParams, session);
      } else if (MultiValue.isMultiValue(iThis)) {
        // ACT ON MULTIPLE DOCUMENTS
        final List<Object> result = new ArrayList<Object>((int) MultiValue.getSize(iThis));
        for (var o : MultiValue.getMultiValueIterable(iThis)) {
          if (o instanceof Identifiable id) {
            try {
              var transaction = session.getActiveTransaction();
              var record = transaction.load(id);
              result.add(copy((EntityImpl) record, iParams, session));
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
      final Object[] iFieldNames, DatabaseSessionEmbedded session) {
    var result = new ResultInternal(session);
    for (var iFieldName : iFieldNames) {
      if (iFieldName != null) {

        final var fieldName = iFieldName.toString();

        if (!fieldName.isEmpty() && fieldName.charAt(fieldName.length() - 1) == '*') {
          final var fieldPart = fieldName.substring(0, fieldName.length() - 1);
          final List<String> toInclude = new ArrayList<String>();
          for (var f : entity.propertyNames()) {
            if (f.startsWith(fieldPart)) {
              toInclude.add(f);
            }
          }

          for (var f : toInclude) {
            result.setProperty(fieldName, entity.getProperty(f));
          }

        } else {
          result.setProperty(fieldName, entity.getProperty(fieldName));
        }
      }
    }

    return result;
  }

  private static Object copy(final Map map,
      final Object[] iFieldNames, DatabaseSessionEmbedded session) {
    final var entity = new ResultInternal(session);
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
            entity.setProperty(fieldName, map.get(f));
          }

        } else {
          entity.setProperty(fieldName, map.get(fieldName));
        }
      }
    }
    return entity;
  }
}
