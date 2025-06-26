package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.api.remote.query.RemoteResultSet;

public class RemoteQueryResult {
  private final RemoteResultSet result;

  public RemoteQueryResult(RemoteResultSet result) {
    this.result = result;
  }

  public RemoteResultSet getResult() {
    return result;
  }
}
