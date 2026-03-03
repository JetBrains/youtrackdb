package com.jetbrains.youtrackdb.internal.core.db;

import java.util.concurrent.ScheduledFuture;

public interface SchedulerInternal {

  ScheduledFuture<?> schedule(Runnable task, long delay, long period);

  ScheduledFuture<?> scheduleOnce(Runnable task, long delay);
}
