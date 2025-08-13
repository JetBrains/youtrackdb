package com.jetbrains.youtrackdb.internal.client.remote;

import com.jetbrains.youtrackdb.internal.core.command.CommandResultListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;

public interface SimpleValueFetchPlanCommandListener extends CommandResultListener {

  void linkdedBySimpleValue(DatabaseSessionInternal db, EntityImpl entity);
}
