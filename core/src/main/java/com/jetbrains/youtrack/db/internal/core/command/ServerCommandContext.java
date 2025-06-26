package com.jetbrains.youtrack.db.internal.core.command;

import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;

public interface ServerCommandContext extends CommandContext {
  YouTrackDBInternal getYouTrackDB();
}
