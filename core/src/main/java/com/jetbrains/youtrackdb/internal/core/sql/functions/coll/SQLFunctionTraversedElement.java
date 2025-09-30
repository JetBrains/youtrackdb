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
package com.jetbrains.youtrackdb.internal.core.sql.functions.coll;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.command.traverse.TraverseRecordProcess;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassSnapshot;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionConfigurableAbstract;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Returns a traversed element from the stack. Use it with SQL traverse only.
 */
public class SQLFunctionTraversedElement extends SQLFunctionConfigurableAbstract {

  public static final String NAME = "traversedElement";

  public SQLFunctionTraversedElement() {
    super(NAME, 1, 2);
  }

  public SQLFunctionTraversedElement(final String name) {
    super(name, 1, 2);
  }

  public boolean aggregateResults() {
    return false;
  }

  @Nullable
  @Override
  public Object getResult() {
    return null;
  }

  @Override
  public boolean filterResult() {
    return true;
  }

  public String getSyntax(DatabaseSession session) {
    return getName(session) + "(<beginIndex> [,<items>])";
  }

  public Object execute(
      Object iThis,
      final Result iCurrentRecord,
      Object iCurrentResult,
      final Object[] iParams,
      final CommandContext iContext) {
    return evaluate(iThis, iParams, iContext, null);
  }

  @Nullable
  protected Object evaluate(
      final Object iThis,
      final Object[] iParams,
      final CommandContext iContext,
      final String iClassName) {
    final int beginIndex = (Integer) iParams[0];
    final var items = iParams.length > 1 ? (Integer) iParams[1] : 1;

    var stack = (Collection) iContext.getVariable("stack");
    if (stack == null && iThis instanceof ResultInternal resultInternal) {
      stack = (Collection) resultInternal.getMetadata("$stack");
    }
    if (stack == null) {
      throw new CommandExecutionException(iContext.getDatabaseSession(),
          "Cannot invoke " + getName(iContext.getDatabaseSession())
              + "() against non traverse command");
    }

    final List<Identifiable> result = items > 1 ? new ArrayList<Identifiable>(items) : null;

    var session = iContext.getDatabaseSession();
    if (beginIndex < 0) {
      var i = -1;
      for (final var o : stack) {
        if (o instanceof TraverseRecordProcess) {
          final var record = ((TraverseRecordProcess) o).getTarget();

          SchemaClassSnapshot result1 = null;
          var transaction = session.getActiveTransaction();
          final EntityImpl entity = transaction.load(record);
          result1 = entity.getImmutableSchemaClass(session);
          if (iClassName == null
              || result1
              .isChildOf(iClassName)) {
            if (i <= beginIndex) {
              if (items == 1) {
                return record;
              } else {
                result.add(record);
                if (result.size() >= items) {
                  break;
                }
              }
            }
            i--;
          }
        } else if (o instanceof Identifiable record) {

          SchemaClassSnapshot result1 = null;
          var transaction = session.getActiveTransaction();
          final EntityImpl entity = transaction.load(record);
          if (entity != null) {
            result1 = entity.getImmutableSchemaClass(session);
          }
          if (iClassName == null
              || result1
              .isChildOf(iClassName)) {
            if (i <= beginIndex) {
              if (items == 1) {
                return record;
              } else {
                result.add(record);
                if (result.size() >= items) {
                  break;
                }
              }
            }
            i--;
          }
        }
      }
    } else {
      var i = 0;
      var listStack = stackToList(stack);
      for (var x = listStack.size() - 1; x >= 0; x--) {
        final var o = listStack.get(x);
        if (o instanceof TraverseRecordProcess) {
          final var record = ((TraverseRecordProcess) o).getTarget();

          SchemaClassSnapshot result1 = null;
          var transaction = session.getActiveTransaction();
          final EntityImpl entity = transaction.load(record);
          if (entity != null) {
            result1 = entity.getImmutableSchemaClass(session);
          }
          if (iClassName == null
              || result1
              .isChildOf(iClassName)) {
            if (i >= beginIndex) {
              if (items == 1) {
                return record;
              } else {
                result.add(record);
                if (result.size() >= items) {
                  break;
                }
              }
            }
            i++;
          }
        } else if (o instanceof Identifiable record) {

          SchemaClassSnapshot result1 = null;
          var transaction = session.getActiveTransaction();
          final EntityImpl entity = transaction.load(record);
          if (entity != null) {
            result1 = entity.getImmutableSchemaClass(session);
          }
          if (iClassName == null
              || result1
              .isChildOf(iClassName)) {
            if (i >= beginIndex) {
              if (items == 1) {
                return record;
              } else {
                result.add(record);
                if (result.size() >= items) {
                  break;
                }
              }
            }
            i++;
          }
        }
      }
    }

    if (items > 0 && result != null && !result.isEmpty()) {
      return result;
    }
    return null;
  }

  private static List stackToList(Collection stack) {
    if (stack instanceof List) {
      return (List) stack;
    }

    return (List) stack.stream().collect(Collectors.toList());
  }
}
