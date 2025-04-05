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
package com.jetbrains.youtrack.db.internal.core.sql.functions.coll;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.command.traverse.TraverseRecordProcess;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionConfigurableAbstract;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

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
      final Identifiable iCurrentRecord,
      Object iCurrentResult,
      final Object[] iParams,
      final CommandContext iContext) {
    return evaluate(iThis, iParams, iContext, null);
  }

  protected Object evaluate(
      final Object iThis,
      final Object[] iParams,
      final CommandContext iContext,
      final String iClassName) {
    final int beginIndex = (Integer) iParams[0];
    final int items = iParams.length > 1 ? (Integer) iParams[1] : 1;

    Collection stack = (Collection) iContext.getVariable("stack");
    if (stack == null && iThis instanceof Result) {
      stack = (Collection) ((Result) iThis).getMetadata("$stack");
    }
    if (stack == null) {
      throw new CommandExecutionException(
          "Cannot invoke " + getName(iContext.getDatabase()) + "() against non traverse command");
    }

    final List<Identifiable> result = items > 1 ? new ArrayList<Identifiable>(items) : null;

    if (beginIndex < 0) {
      int i = -1;
      for (Iterator it = stack.iterator(); it.hasNext(); ) {
        final Object o = it.next();
        if (o instanceof TraverseRecordProcess) {
          final Identifiable record = ((TraverseRecordProcess) o).getTarget();

          if (iClassName == null
              || EntityInternalUtils.getImmutableSchemaClass(record.getRecord())
              .isSubClassOf(iContext.getDatabase(), iClassName)) {
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

          if (iClassName == null
              || EntityInternalUtils.getImmutableSchemaClass(record.getRecord())
              .isSubClassOf(iContext.getDatabase(), iClassName)) {
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
      int i = 0;
      List listStack = stackToList(stack);
      for (int x = listStack.size() - 1; x >= 0; x--) {
        final Object o = listStack.get(x);
        if (o instanceof TraverseRecordProcess) {
          final Identifiable record = ((TraverseRecordProcess) o).getTarget();

          if (iClassName == null
              || EntityInternalUtils.getImmutableSchemaClass(record.getRecord())
              .isSubClassOf(iContext.getDatabase(), iClassName)) {
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

          if (iClassName == null
              || EntityInternalUtils.getImmutableSchemaClass(record.getRecord())
              .isSubClassOf(iContext.getDatabase(), iClassName)) {
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

  private List stackToList(Collection stack) {
    if (stack instanceof List) {
      return (List) stack;
    }

    return (List) stack.stream().collect(Collectors.toList());
  }
}
