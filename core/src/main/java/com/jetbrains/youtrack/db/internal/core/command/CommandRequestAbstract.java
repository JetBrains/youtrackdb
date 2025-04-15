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
package com.jetbrains.youtrack.db.internal.core.command;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext.TIMEOUT_STRATEGY;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.replication.AsyncReplicationError;
import com.jetbrains.youtrack.db.internal.core.replication.AsyncReplicationOk;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Text based Command Request abstract class.
 */
public abstract class CommandRequestAbstract
    implements CommandRequestInternal {

  protected CommandResultListener resultListener;
  protected ProgressListener progressListener;
  protected int limit = -1;
  protected long timeoutMs = GlobalConfiguration.COMMAND_TIMEOUT.getValueAsLong();
  protected TIMEOUT_STRATEGY timeoutStrategy = TIMEOUT_STRATEGY.EXCEPTION;
  protected Map<Object, Object> parameters;
  protected String fetchPlan = null;
  protected boolean useCache = false;
  protected boolean cacheableResult = false;
  protected CommandContext context;
  protected AsyncReplicationOk onAsyncReplicationOk;
  protected AsyncReplicationError onAsyncReplicationError;

  private final Set<String> nodesToExclude = new HashSet<String>();
  private boolean recordResultSet = true;

  protected CommandRequestAbstract() {
  }

  public CommandResultListener getResultListener() {
    return resultListener;
  }

  public void setResultListener(CommandResultListener iListener) {
    resultListener = iListener;
  }

  public Map<Object, Object> getParameters() {
    return parameters;
  }

  protected void setParameters(final Object... iArgs) {
    if (iArgs != null && iArgs.length > 0) {
      parameters = convertToParameters(iArgs);
    }
  }

  @SuppressWarnings("unchecked")
  protected static Map<Object, Object> convertToParameters(Object... iArgs) {
    final Map<Object, Object> params;

    if (iArgs.length == 1 && iArgs[0] instanceof Map) {
      params = (Map<Object, Object>) iArgs[0];
    } else {
      if (iArgs.length == 1
          && iArgs[0] != null
          && iArgs[0].getClass().isArray()
          && iArgs[0] instanceof Object[]) {
        iArgs = (Object[]) iArgs[0];
      }

      params = new HashMap<>(iArgs.length);
      for (var i = 0; i < iArgs.length; ++i) {
        var par = iArgs[i];

        if (par instanceof Identifiable
            && ((RecordId) ((Identifiable) par).getIdentity()).isValidPosition())
        // USE THE RID ONLY
        {
          par = ((Identifiable) par).getIdentity();
        }

        params.put(i, par);
      }
    }
    return params;
  }

  public ProgressListener getProgressListener() {
    return progressListener;
  }

  public CommandRequestAbstract setProgressListener(ProgressListener progressListener) {
    this.progressListener = progressListener;
    return this;
  }

  public void reset() {
  }

  public int getLimit() {
    return limit;
  }

  public CommandRequestAbstract setLimit(final int limit) {
    this.limit = limit;
    return this;
  }

  public String getFetchPlan() {
    return fetchPlan;
  }

  @SuppressWarnings("unchecked")
  public <RET extends CommandRequest> RET setFetchPlan(String fetchPlan) {
    this.fetchPlan = fetchPlan;
    return (RET) this;
  }

  public boolean isUseCache() {
    return useCache;
  }

  public void setUseCache(boolean useCache) {
    this.useCache = useCache;
  }

  @Override
  public boolean isCacheableResult() {
    return cacheableResult;
  }

  @Override
  public void setCacheableResult(final boolean iValue) {
    cacheableResult = iValue;
  }

  @Override
  public CommandContext getContext() {
    if (context == null) {
      context = new BasicCommandContext();
    }
    return context;
  }

  public CommandRequestAbstract setContext(final CommandContext iContext) {
    context = iContext;
    return this;
  }

  public long getTimeoutTime() {
    return timeoutMs;
  }

  public void setTimeout(final long timeout, final TIMEOUT_STRATEGY strategy) {
    this.timeoutMs = timeout;
    this.timeoutStrategy = strategy;
  }

  public TIMEOUT_STRATEGY getTimeoutStrategy() {
    return timeoutStrategy;
  }

  @Override
  public void setRecordResultSet(boolean recordResultSet) {
    this.recordResultSet = recordResultSet;
  }

  public boolean isRecordResultSet() {
    return recordResultSet;
  }
}
