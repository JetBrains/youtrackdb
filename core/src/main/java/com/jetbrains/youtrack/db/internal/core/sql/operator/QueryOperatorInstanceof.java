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
package com.jetbrains.youtrack.db.internal.core.sql.operator;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;

/**
 * EQUALS operator.
 */
public class QueryOperatorInstanceof extends QueryOperatorEqualityNotNulls {

  public QueryOperatorInstanceof() {
    super("INSTANCEOF", 5, false);
  }

  @Override
  protected boolean evaluateExpression(
      final Result iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      CommandContext iContext) {
    var session = iContext.getDatabaseSession();
    final Schema schema = session.getMetadata().getImmutableSchemaSnapshot();

    final var baseClassName = iRight.toString();
    final var baseClass = schema.getClass(baseClassName);
    if (baseClass == null) {
      throw new CommandExecutionException(session,
          "Class '" + baseClassName + "' is not defined in database schema");
    }

    SchemaClass cls = null;
    if (iLeft instanceof Identifiable) {
      // GET THE RECORD'S CLASS
      var record = ((Identifiable) iLeft).getRecord(iContext.getDatabaseSession());
      if (record instanceof EntityImpl) {
        SchemaImmutableClass result = null;
        if (record != null) {
          result = ((EntityImpl) record).getImmutableSchemaClass(session);
        }
        cls = result;
      }
    } else if (iLeft instanceof String)
    // GET THE CLASS BY NAME
    {
      cls = schema.getClass((String) iLeft);
    }

    return cls != null && cls.isSubClassOf(session, baseClass);
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    return IndexReuseType.NO_INDEX;
  }

  @Override
  public RID getBeginRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }

  @Override
  public RID getEndRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    return null;
  }
}
