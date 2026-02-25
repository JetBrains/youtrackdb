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
package com.jetbrains.youtrackdb.internal.core.command;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Basic implementation of CommandContext interface that stores variables in a map. Supports
 * parent/child context to build a tree of contexts. If a variable is not found on current object
 * the search is applied recursively on child contexts.
 */
public class BasicCommandContext implements CommandContext {
  public static final String INVALID_COMPARE_COUNT = "INVALID_COMPARE_COUNT";

  protected DatabaseSessionEmbedded session;
  protected Object[] args;

  protected boolean recordMetrics = false;
  protected CommandContext parent;
  protected CommandContext child;
  private Map<String, Object> variables;

  private final Int2ObjectOpenHashMap<Object> systemVariables = new Int2ObjectOpenHashMap<>();

  protected Map<Object, Object> inputParameters;

  protected Set<String> declaredScriptVariables = new HashSet<>();

  // MANAGES THE TIMEOUT
  private long executionStartedOn;
  private long timeoutMs;
  private CommandContext.TIMEOUT_STRATEGY timeoutStrategy;

  private final Map<ExecutionStep, StepStats> stepStats = new IdentityHashMap<>();
  private final LinkedList<StepStats> currentStepStats = new LinkedList<>();
  private final List<SQLBooleanExpression> parentWhereExpressions = new LinkedList<>();

  public BasicCommandContext() {
  }

  public BasicCommandContext(DatabaseSessionEmbedded session) {
    this.session = session;
  }

  @Override
  public <T> void setSystemVariable(int id, T value) {
    if (parent != null) {
      if (parent.hasSystemVariable(id)) {
        parent.setSystemVariable(id, value);
      } else {
        systemVariables.put(id, value);
      }
    } else {
      systemVariables.put(id, value);
    }
  }

  @Override
  public boolean hasSystemVariable(int id) {
    if (systemVariables.containsKey(id)) {
      return true;
    } else if (parent != null) {
      return parent.hasSystemVariable(id);
    }
    return false;
  }

  @Nullable
  @SuppressWarnings("TypeParameterUnusedInFormals")
  @Override
  public <T> T getSystemVariable(int id) {
    var value = systemVariables.get(id);
    if (value != null) {
      return (T) value;
    } else if (parent != null) {
      return parent.getSystemVariable(id);
    }
    return null;
  }

  @Override
  public Object getVariable(String iName) {
    return getVariable(iName, null);
  }

  @Override
  public Object getVariable(String iName, final Object iDefault) {
    if (iName == null) {
      return iDefault;
    }

    Object result = null;

    if (iName.startsWith("$")) {
      iName = iName.substring(1);
    }

    var pos = StringSerializerHelper.getLowerIndexOf(iName, 0, ".", "[");

    String firstPart;
    String lastPart;
    if (pos > -1) {
      firstPart = iName.substring(0, pos);
      if (iName.charAt(pos) == '.') {
        pos++;
      }
      lastPart = iName.substring(pos);
      if (firstPart.equalsIgnoreCase("PARENT") && parent != null) {
        // UP TO THE PARENT
        if (lastPart.startsWith("$")) {
          result = parent.getVariable(lastPart.substring(1));
        } else {
          result = EntityHelper.getFieldValue(getDatabaseSession(), parent, lastPart);
        }

        return result != null ? resolveValue(result) : iDefault;

      } else if (firstPart.equalsIgnoreCase("ROOT")) {
        CommandContext p = this;
        while (p.getParent() != null) {
          p = p.getParent();
        }

        if (lastPart.startsWith("$")) {
          result = p.getVariable(lastPart.substring(1));
        } else {
          result = EntityHelper.getFieldValue(getDatabaseSession(), p, lastPart, this);
        }

        return result != null ? resolveValue(result) : iDefault;
      }
    } else {
      firstPart = iName;
      lastPart = null;
    }

    if (firstPart.equalsIgnoreCase("CONTEXT")) {
      result = getVariables();
    } else if (firstPart.equalsIgnoreCase("PARENT")) {
      result = parent;
    } else if (firstPart.equalsIgnoreCase("ROOT")) {
      CommandContext p = this;
      while (p.getParent() != null) {
        p = p.getParent();
      }
      result = p;
    } else {
      if (variables != null && variables.containsKey(firstPart)) {
        result = variables.get(firstPart);
      } else {
        if (child != null) {
          result = child.getVariable(firstPart);
        } else {
          result = getVariableFromParentHierarchy(firstPart);
        }
      }
    }

    if (pos > -1) {
      result = EntityHelper.getFieldValue(getDatabaseSession(), result, lastPart, this);
    }

    return result != null ? resolveValue(result) : iDefault;
  }

  private Object resolveValue(Object value) {
    if (value instanceof DynamicVariable) {
      value = ((DynamicVariable) value).resolve(this);
    }
    return value;
  }

  @Nullable
  protected Object getVariableFromParentHierarchy(String varName) {
    if (this.variables != null && variables.containsKey(varName)) {
      return variables.get(varName);
    }
    if (parent != null && parent instanceof BasicCommandContext) {
      return ((BasicCommandContext) parent).getVariableFromParentHierarchy(varName);
    }
    return null;
  }

  public CommandContext setDynamicVariable(String iName, final DynamicVariable iValue) {
    return setVariable(iName, iValue);
  }

  @Override
  @Nullable
  public CommandContext setVariable(String iName, final Object iValue) {
    if (iName == null) {
      return null;
    }

    if (!iName.isEmpty() && iName.charAt(0) == '$') {
      iName = iName.substring(1);
    }

    init();

    var pos = StringSerializerHelper.getHigherIndexOf(iName, 0, ".", "[");
    if (pos > -1) {
      var nested = getVariable(iName.substring(0, pos));
      if (nested instanceof CommandContext commandContext) {
        commandContext.setVariable(iName.substring(pos + 1), iValue);
      }
    } else {
      if (variables.containsKey(iName)) {
        variables.put(
            iName, iValue); // this is a local existing variable, so it's bound to current contex
      } else if (parent instanceof BasicCommandContext basicCommandContext
          && basicCommandContext.hasVariable(iName)) {
        if ("current".equalsIgnoreCase(iName) || "parent".equalsIgnoreCase(iName)) {
          variables.put(iName, iValue);
        } else {
          parent.setVariable(
              iName,
              iValue); // it is an existing variable in parent context, so it's bound to parent
          // context
        }
      } else {
        variables.put(iName, iValue); // it's a new variable, so it's created in this context
      }
    }
    return this;
  }

  boolean hasVariable(String iName) {
    if (variables != null && variables.containsKey(iName)) {
      return true;
    }
    if (parent != null && parent instanceof BasicCommandContext) {
      return ((BasicCommandContext) parent).hasVariable(iName);
    }
    return false;
  }

  @Override
  public CommandContext incrementVariable(String iName) {
    if (iName != null) {
      if (iName.startsWith("$")) {
        iName = iName.substring(1);
      }

      init();

      var pos = StringSerializerHelper.getHigherIndexOf(iName, 0, ".", "[");
      if (pos > -1) {
        var nested = getVariable(iName.substring(0, pos));
        if (nested instanceof CommandContext commandContext) {
          commandContext.incrementVariable(iName.substring(pos + 1));
        }
      } else {
        final var v = variables.get(iName);
        if (v == null) {
          variables.put(iName, 1);
        } else if (v instanceof Number number) {
          variables.put(iName, PropertyTypeInternal.increment(number, 1));
        } else {
          throw new IllegalArgumentException(
              "Variable '" + iName + "' is not a number, but: " + v.getClass());
        }
      }
    }
    return this;
  }

  @Override
  public long updateMetric(final String iName, final long iValue) {
    if (!recordMetrics) {
      return -1;
    }

    init();
    var value = (Long) variables.get(iName);
    if (value == null) {
      value = iValue;
    } else {
      value = Long.valueOf(value.longValue() + iValue);
    }
    variables.put(iName, value);
    return value.longValue();
  }

  /**
   * Returns a read-only map with all the variables.
   */
  @Override
  public Map<String, Object> getVariables() {
    final var map = new HashMap<String, Object>();
    if (child != null) {
      map.putAll(child.getVariables());
    }

    if (variables != null) {
      map.putAll(variables);
    }

    return map;
  }

  /**
   * Set the inherited context avoiding to copy all the values every time.
   */
  @Override
  public CommandContext setChild(final CommandContext iContext) {
    if (iContext == null) {
      if (child != null) {
        // REMOVE IT
        child.setParent(null);
        child.setDatabaseSession(null);

        child = null;
      }
    } else if (child != iContext) {
      // ADD IT
      child = iContext;
      iContext.setParent(this);
      child.setDatabaseSession(session);
    }

    return this;
  }

  @Override
  public CommandContext getParent() {
    return parent;
  }

  @Override
  public CommandContext setParent(final CommandContext iParentContext) {
    if (parent != iParentContext) {
      parent = iParentContext;
      if (parent != null) {
        parent.setChild(this);
      }
    }
    return this;
  }

  public CommandContext setParentWithoutOverridingChild(final CommandContext iParentContext) {
    if (parent != iParentContext) {
      parent = iParentContext;
    }
    return this;
  }

  @Override
  public String toString() {
    return getVariables().toString();
  }

  @Override
  public boolean isRecordingMetrics() {
    return recordMetrics;
  }

  @Override
  public CommandContext setRecordingMetrics(final boolean recordMetrics) {
    this.recordMetrics = recordMetrics;
    return this;
  }

  @Override
  public void beginExecution(final long iTimeout, final TIMEOUT_STRATEGY iStrategy) {
    if (iTimeout > 0) {
      executionStartedOn = System.currentTimeMillis();
      timeoutMs = iTimeout;
      timeoutStrategy = iStrategy;
    }
  }

  @Override
  public boolean checkTimeout() {
    if (timeoutMs > 0) {
      if (System.currentTimeMillis() - executionStartedOn > timeoutMs) {
        // TIMEOUT!
        switch (timeoutStrategy) {
          case RETURN -> {
            return false;
          }
          case EXCEPTION ->
              throw new TimeoutException(
                  "Command execution timeout exceed (" + timeoutMs + "ms)");
        }
      }
    } else if (parent != null)
    // CHECK THE TIMER OF PARENT CONTEXT
    {
      return parent.checkTimeout();
    }

    return true;
  }

  @Override
  public CommandContext copy() {
    final var copy = new BasicCommandContext();
    copy.init();

    if (variables != null && !variables.isEmpty()) {
      copy.variables.putAll(variables);
    }
    if (!systemVariables.isEmpty()) {
      copy.systemVariables.putAll(systemVariables);
    }

    copy.recordMetrics = recordMetrics;

    copy.child = child.copy();
    copy.child.setParent(copy);

    copy.setDatabaseSession(null);

    return copy;
  }

  @Override
  public void merge(final CommandContext iContext) {
    // TODO: SOME VALUES NEED TO BE MERGED
  }

  private void init() {
    if (variables == null) {
      variables = new HashMap<String, Object>();
    }
  }

  @Override
  @Nullable
  public Map<Object, Object> getInputParameters() {
    if (inputParameters != null) {
      return inputParameters;
    }

    return parent == null ? null : parent.getInputParameters();
  }

  @Override
  public void setInputParameters(Map<Object, Object> inputParameters) {
    this.inputParameters = inputParameters;
  }

  @Override
  public DatabaseSessionEmbedded getDatabaseSession() {
    if (session != null) {
      return session;
    }

    if (parent != null) {
      session = parent.getDatabaseSession();
    }

    if (session == null && !(this instanceof ServerCommandContext)) {
      throw new DatabaseException("No database session found in SQL context");
    }

    return session;
  }


  @Override
  public void setDatabaseSession(DatabaseSessionEmbedded session) {
    this.session = session;

    if (child != null) {
      child.setDatabaseSession(session);
    }
  }

  @Override
  public void declareScriptVariable(String varName) {
    this.declaredScriptVariables.add(varName);
  }

  @Override
  public boolean isScriptVariableDeclared(String varName) {
    if (varName == null || varName.length() == 0) {
      return false;
    }
    var dollarVar = varName;
    if (!dollarVar.startsWith("$")) {
      dollarVar = "$" + varName;
    }
    varName = dollarVar.substring(1);
    if (variables != null && (variables.containsKey(varName) || variables.containsKey(dollarVar))) {
      return true;
    }
    return declaredScriptVariables.contains(varName)
        || declaredScriptVariables.contains(dollarVar)
        || (parent != null && parent.isScriptVariableDeclared(varName));
  }

  @Override
  public void startProfiling(ExecutionStep step) {
    var stats = stepStats.get(step);
    if (stats == null) {
      stats = new StepStats();
      stepStats.put(step, stats);
    }
    if (!this.currentStepStats.isEmpty()) {
      this.currentStepStats.getLast().pause();
    }
    stats.start();
    this.currentStepStats.push(stats);
  }

  @Override
  public void endProfiling(ExecutionStep step) {
    if (!this.currentStepStats.isEmpty()) {
      this.currentStepStats.pop().end();
      if (!this.currentStepStats.isEmpty()) {
        this.currentStepStats.getLast().resume();
      }
    }
  }

  @Override
  public StepStats getStats(ExecutionStep step) {
    return stepStats.get(step);
  }

  @Override
  public void registerBooleanExpression(SQLBooleanExpression expression) {
    parentWhereExpressions.add(expression);
  }

  @Override
  public List<SQLBooleanExpression> getParentWhereExpressions() {
    if (parent == null) {
      return parentWhereExpressions;
    }

    if (parentWhereExpressions.isEmpty()) {
      return parent.getParentWhereExpressions();
    }

    final var result = new LinkedList<>(parentWhereExpressions);
    result.addAll(parent.getParentWhereExpressions());
    return result;
  }
}
