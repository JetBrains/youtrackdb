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

package com.jetbrains.youtrack.db.internal.common.thread;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.Service;

public abstract class SoftThread extends Thread implements Service {

  private volatile boolean shutdownFlag;

  private boolean dumpExceptions = true;

  public SoftThread(String name) {
    super(name);
    setUncaughtExceptionHandler(
        new com.jetbrains.youtrack.db.internal.common.util.UncaughtExceptionHandler());
  }

  public SoftThread(final ThreadGroup group, final String name) {
    super(group, name);
    setDaemon(true);
    setUncaughtExceptionHandler(
        new com.jetbrains.youtrack.db.internal.common.util.UncaughtExceptionHandler());
  }

  protected abstract void execute() throws Exception;

  @Override
  public void startup() {
  }

  @Override
  public void shutdown() {
  }

  public void sendShutdown() {
    shutdownFlag = true;
  }

  public void softShutdown() {
    shutdownFlag = true;
  }

  public boolean isShutdownFlag() {
    return shutdownFlag;
  }

  @Override
  public void run() {
    startup();

    while (!shutdownFlag && !isInterrupted()) {
      try {
        beforeExecution();
        execute();
        afterExecution();
      } catch (Exception e) {
        if (dumpExceptions) {
          LogManager.instance().error(this, "Error during thread execution", e);
        }
      } catch (Error e) {
        if (dumpExceptions) {
          LogManager.instance().error(this, "Error during thread execution", e);
        }
        shutdown();
        throw e;
      }
    }

    shutdown();
  }

  public void setDumpExceptions(final boolean dumpExceptions) {
    this.dumpExceptions = dumpExceptions;
  }

  protected void beforeExecution() throws InterruptedException {
  }

  protected void afterExecution() throws InterruptedException {
  }
}
