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
package com.jetbrains.youtrackdb.internal.core.sql.operator;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition;
import javax.annotation.Nullable;

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
      var transaction = iContext.getDatabaseSession().getActiveTransaction();
      var record = transaction.load(((Identifiable) iLeft));
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

    return cls != null && cls.isSubClassOf(baseClass);
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    return IndexReuseType.NO_INDEX;
  }

  @Nullable
  @Override
  public RID getBeginRidRange(DatabaseSessionEmbedded session, Object iLeft, Object iRight) {
    return null;
  }

  @Nullable
  @Override
  public RID getEndRidRange(DatabaseSessionEmbedded session, Object iLeft, Object iRight) {
    return null;
  }
}
