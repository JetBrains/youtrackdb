package com.jetbrains.youtrackdb.internal.core.db;

import java.util.TimerTask;

public interface SchedulerInternal {

  void schedule(TimerTask task, long delay, long period);

  void scheduleOnce(TimerTask task, long delay);
}
