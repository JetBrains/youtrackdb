package com.jetbrains.youtrack.db.internal.core.command;

import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;

public class BasicServerCommandContext extends BasicCommandContext
    implements ServerCommandContext {

  private YouTrackDBInternal server;

  public BasicServerCommandContext() {
  }

  public BasicServerCommandContext(YouTrackDBInternal server) {
    this.server = server;
  }

  @Override
  public YouTrackDBInternal getServer() {
    return server;
  }

  public void setServer(YouTrackDBInternal server) {
    this.server = server;
  }
}
