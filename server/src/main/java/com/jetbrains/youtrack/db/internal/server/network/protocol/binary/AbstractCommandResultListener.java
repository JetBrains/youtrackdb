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
package com.jetbrains.youtrack.db.internal.server.network.protocol.binary;

import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.internal.client.remote.SimpleValueFetchPlanCommandListener;
import com.jetbrains.youtrack.db.internal.core.command.CommandResultListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.fetch.FetchContext;
import com.jetbrains.youtrack.db.internal.core.fetch.FetchHelper;
import com.jetbrains.youtrack.db.internal.core.fetch.FetchListener;
import com.jetbrains.youtrack.db.internal.core.fetch.FetchPlan;
import com.jetbrains.youtrack.db.internal.core.fetch.remote.RemoteFetchContext;
import javax.annotation.Nonnull;

/**
 * Abstract class to manage command results.
 */
public abstract class AbstractCommandResultListener
    implements SimpleValueFetchPlanCommandListener {

  protected final CommandResultListener wrappedResultListener;

  private FetchPlan fetchPlan;

  protected AbstractCommandResultListener(final CommandResultListener wrappedResultListener) {
    this.wrappedResultListener = wrappedResultListener;
  }

  public abstract boolean isEmpty();

  @Override
  public void end(@Nonnull DatabaseSessionInternal session) {
    if (wrappedResultListener != null) {
      wrappedResultListener.end(session);
    }
  }

  public void setFetchPlan(final String iText) {
    fetchPlan = FetchHelper.buildFetchPlan(iText);
  }

  protected void fetchRecord(DatabaseSessionInternal db, final Object iRecord,
      final FetchListener iFetchListener) {
    if (fetchPlan != null
        && fetchPlan != FetchHelper.DEFAULT_FETCHPLAN
        && iRecord instanceof DBRecord record) {
      final FetchContext context = new RemoteFetchContext();
      FetchHelper.fetch(db, record, record, fetchPlan, iFetchListener, context, "");
    }
  }

  @Override
  public Object getResult() {
    if (wrappedResultListener != null) {
      return wrappedResultListener.getResult();
    }

    return null;
  }
}
