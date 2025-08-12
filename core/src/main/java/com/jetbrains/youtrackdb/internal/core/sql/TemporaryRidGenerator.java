package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;

public interface TemporaryRidGenerator {

  int getTemporaryRIDCounter(final CommandContext iContext);
}
