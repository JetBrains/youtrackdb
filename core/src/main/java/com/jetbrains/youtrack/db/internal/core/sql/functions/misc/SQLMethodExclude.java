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
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.core.sql.method.misc.AbstractSQLMethod;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Filter the content by excluding only some fields. If the content is a entity, then creates a
 * copy without the excluded fields. If it's a collection of documents it acts against on each
 * single entry.
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
 * exclude(&lt;field|value|expression&gt; [,&lt;field-name&gt;]* )
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
 * SELECT <b>exclude(roles, 'permissions')</b> FROM OUser
 * </pre>
 *
 * <p>
 *
 * </blockquote>
 */
public class SQLMethodExclude extends AbstractSQLMethod {

  public static final String NAME = "exclude";

  public SQLMethodExclude() {
    super(NAME, 1, -1);
  }

  @Override
  public String getSyntax() {
    return "Syntax error: exclude([<field-name>][,]*)";
  }

  @Nullable
  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      CommandContext iContext,
      Object ioResult,
      Object[] iParams) {
    var db = iContext.getDatabaseSession();
    if (iThis != null) {
      if (iThis instanceof RecordId) {
        try {
          var transaction = db.getActiveTransaction();
          iThis = transaction.load(((RecordId) iThis));
        } catch (RecordNotFoundException rnf) {
          return null;
        }
      } else {
        if (iThis instanceof Result result) {
          iThis = result.asEntity();
        }
      }
      if (iThis instanceof EntityImpl) {
        // ACT ON SINGLE ENTITY
        return copy(db, (EntityImpl) iThis, iParams);
      } else {
        if (iThis instanceof Map) {
          // ACT ON SINGLE MAP
          return copy(db, (Map) iThis, iParams);
        } else {
          if (MultiValue.isMultiValue(iThis)) {
            // ACT ON MULTIPLE DOCUMENTS
            final List<Object> result = new ArrayList<Object>((int) MultiValue.getSize(iThis));
            for (var o : MultiValue.getMultiValueIterable(iThis)) {
              if (o instanceof Identifiable) {
                try {
                  var transaction = db.getActiveTransaction();
                  var rec = transaction.load(((Identifiable) o));
                  result.add(copy(db, (EntityImpl) rec, iParams));
                } catch (RecordNotFoundException rnf) {
                  // IGNORE IT
                }
              }
            }
            return result;
          }
        }
      }
    }

    // INVALID, RETURN NULL
    return null;
  }

  private static Object copy(DatabaseSessionInternal db, final EntityImpl entity,
      final Object[] iFieldNames) {
    var result = new ResultInternal(db);

    var propertyNames = new HashSet<>(entity.getPropertyNames());
    for (var iFieldName : iFieldNames) {
      if (iFieldName != null) {
        final var fieldName = iFieldName.toString();
        if (fieldName.endsWith("*")) {
          final var fieldPart = fieldName.substring(0, fieldName.length() - 1);

          for (var propertyName : entity.getPropertyNames()) {
            if (propertyName.startsWith(fieldPart)) {
              propertyNames.remove(propertyName);
            }
          }
        } else {
          propertyNames.remove(fieldName);
        }
      }
    }

    for (var propertyName : propertyNames) {
      result.setProperty(propertyName, entity.getProperty(propertyName));
    }

    return result;
  }

  private Result copy(DatabaseSessionInternal database, final Map<String, ?> map,
      final Object[] iFieldNames) {
    var result = new ResultInternal(database);

    var propertyNames = new HashSet<>(map.keySet());

    for (var iFieldName : iFieldNames) {
      if (iFieldName != null) {
        final var fieldName = iFieldName.toString();
        if (fieldName.endsWith("*")) {
          final var fieldPart = fieldName.substring(0, fieldName.length() - 1);

          for (var propertyName : map.keySet()) {
            if (propertyName.startsWith(fieldPart)) {
              propertyNames.remove(propertyName);
            }
          }
        } else {
          propertyNames.remove(fieldName);
        }
      }
    }

    for (var propertyName : propertyNames) {
      result.setProperty(propertyName, map.get(propertyName));
    }

    return result;
  }
}
