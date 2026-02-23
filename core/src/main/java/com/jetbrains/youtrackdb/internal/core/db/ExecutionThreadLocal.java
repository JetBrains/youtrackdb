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
package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.internal.common.thread.SoftThread;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBListenerAbstract;
import com.jetbrains.youtrackdb.internal.core.db.ExecutionThreadLocal.ExecutionThreadData;
import com.jetbrains.youtrackdb.internal.core.replication.AsyncReplicationError;
import com.jetbrains.youtrackdb.internal.core.replication.AsyncReplicationOk;

/**
 * Thread Local to store execution setting.
 */
public class ExecutionThreadLocal extends ThreadLocal<ExecutionThreadData> {

  public class ExecutionThreadData {

    public volatile AsyncReplicationOk onAsyncReplicationOk;
    public volatile AsyncReplicationError onAsyncReplicationError;
  }

  @Override
  protected ExecutionThreadData initialValue() {
    return new ExecutionThreadData();
  }

  public static volatile ExecutionThreadLocal INSTANCE = new ExecutionThreadLocal();

  public static boolean isInterruptCurrentOperation() {
    final var t = Thread.currentThread();
    if (t instanceof SoftThread softThread) {
      return softThread.isShutdownFlag();
    }
    return false;
  }

  public void setInterruptCurrentOperation(final Thread t) {
    if (t instanceof SoftThread softThread) {
      softThread.softShutdown();
    }
  }

  public static void setInterruptCurrentOperation() {
    final var t = Thread.currentThread();
    if (t instanceof SoftThread softThread) {
      softThread.softShutdown();
    }
  }

  static {
    final var inst = YouTrackDBEnginesManager.instance();
    inst.registerListener(
        new YouTrackDBListenerAbstract() {
          @Override
          public void onStartup() {
            if (INSTANCE == null) {
              INSTANCE = new ExecutionThreadLocal();
            }
          }

          @Override
          public void onShutdown() {
            INSTANCE = null;
          }
        });
  }
}
