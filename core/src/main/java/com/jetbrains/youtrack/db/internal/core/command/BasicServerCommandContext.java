package com.jetbrains.youtrack.db.internal.core.command;

import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;

public class BasicServerCommandContext extends BasicCommandContext
    implements ServerCommandContext {

  private YouTrackDBInternal ytdb;

  public BasicServerCommandContext() {
  }

  @Override
  public YouTrackDBInternal getYouTrackDB() {
    return ytdb;
  }

  public void setYouTrackDB(YouTrackDBInternal ytdb) {
    this.ytdb = ytdb;
  }
}
