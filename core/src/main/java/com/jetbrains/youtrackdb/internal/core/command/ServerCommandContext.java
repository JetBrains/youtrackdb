package com.jetbrains.youtrackdb.internal.core.command;

import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;

public interface ServerCommandContext extends CommandContext {

  YouTrackDBInternal getYouTrackDB();
}
