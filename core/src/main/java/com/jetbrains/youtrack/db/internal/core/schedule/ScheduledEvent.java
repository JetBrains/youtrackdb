/*
 * Copyright 2010-2012 henryzhao81-at-gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains.youtrack.db.internal.core.schedule;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.internal.common.concur.NeedRetryException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrack.db.internal.core.db.tool.DatabaseExportException;
import com.jetbrains.youtrack.db.internal.core.metadata.function.Function;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.schedule.Scheduler.STATUS;
import com.jetbrains.youtrack.db.internal.core.type.IdentityWrapper;
import java.text.ParseException;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;

/**
 * Represents an instance of a scheduled event.
 *
 * @since Mar 28, 2013
 */
public class ScheduledEvent extends IdentityWrapper {

  public static final String CLASS_NAME = "OSchedule";

  public static final String PROP_NAME = "name";
  public static final String PROP_RULE = "rule";
  public static final String PROP_ARGUMENTS = "arguments";
  public static final String PROP_STATUS = "status";
  public static final String PROP_FUNC = "function";
  public static final String PROP_STARTTIME = "starttime";
  public static final String PROP_EXEC_ID = "nextExecId";

  private final AtomicBoolean running;
  private CronExpression cron;
  private volatile TimerTask timer;
  private final AtomicLong nextExecutionId;
  private volatile STATUS status;
  private volatile long startTime;

  private final Function function;
  private final String rule;
  private final String name;
  private final Map<String, Object> arguments;

  /**
   * Creates a scheduled event object from a configuration.
   */
  public ScheduledEvent(final EntityImpl entity, DatabaseSessionInternal session) {
    super(entity);
    var functionEntity = entity.getEntity(PROP_FUNC);

    function = session.getMetadata().getFunctionLibrary().getFunction(session,
        functionEntity.getString(Function.NAME_PROPERTY));

    rule = entity.getString(PROP_RULE);
    name = entity.getString(PROP_NAME);

    var statusValue = entity.getString(PROP_STATUS);
    if (statusValue != null) {
      status = STATUS.valueOf(statusValue);
    }

    Map<String, Object> args = entity.getProperty(PROP_ARGUMENTS);
    this.arguments = Objects.requireNonNullElse(args, Collections.emptyMap());

    running = new AtomicBoolean(false);
    Long execId = entity.getProperty(PROP_EXEC_ID);

    nextExecutionId = new AtomicLong(execId != null ? execId : 0);
    try {
      cron = new CronExpression(rule);
    } catch (ParseException e) {
      LogManager.instance()
          .error(this, "Error on compiling cron expression " + rule, e);
    }
  }

  @Override
  protected void toEntity(@Nonnull DatabaseSessionInternal session, @Nonnull EntityImpl entity) {
    entity.setProperty(PROP_NAME, name);
    entity.setProperty(PROP_RULE, rule);

    var args = entity.newEmbeddedMap(PROP_ARGUMENTS);
    if (arguments != null) {
      args.putAll(arguments);
    }

    entity.setProperty(PROP_STATUS, status);
    entity.setProperty(PROP_FUNC, function.getIdentity());
    entity.setProperty(PROP_EXEC_ID, nextExecutionId.get());
    entity.setProperty(PROP_STARTTIME, startTime);
  }

  public void interrupt() {
    synchronized (this) {
      final var t = timer;
      timer = null;
      if (t != null) {
        t.cancel();
      }
    }
  }

  public Function getFunction() {
    return function;
  }

  public String getRule() {
    return rule;
  }

  public String getName() {
    return name;
  }

  @Nonnull
  public Map<String, Object> getArguments() {
    return arguments;
  }

  public boolean isRunning() {
    return this.running.get();
  }

  public ScheduledEvent schedule(String database, String user, YouTrackDBInternal youtrackDB) {
    if (isRunning()) {
      interrupt();
    }

    if (!getIdentity().isPersistent()) {
      throw new DatabaseExportException("Cannot schedule an unsaved event");
    }

    var task = new ScheduledTimerTask(this, database, user, youtrackDB);
    task.schedule();

    timer = task;
    return this;
  }

  private void setRunning(boolean running) {
    this.running.set(running);
  }

  private static class ScheduledTimerTask extends TimerTask {

    private final ScheduledEvent event;

    private final String database;
    private final String user;
    private final YouTrackDBInternal youTrackDBInternal;

    private ScheduledTimerTask(
        ScheduledEvent event, String database, String user,
        YouTrackDBInternal youTrackDBInternal) {
      this.event = event;
      this.database = database;
      this.user = user;
      this.youTrackDBInternal = youTrackDBInternal;
    }

    public void schedule() {
      synchronized (this) {
        event.nextExecutionId.incrementAndGet();
        var now = new Date();
        var time = event.cron.getNextValidTimeAfter(now).getTime();
        var delay = time - now.getTime();
        youTrackDBInternal.scheduleOnce(this, delay);
      }
    }

    @Override
    public void run() {
      youTrackDBInternal.execute(
          database,
          user,
          db -> {
            runTask(db);
            return null;
          });
    }

    private void runTask(DatabaseSessionInternal db) {
      if (event.running.get()) {
        LogManager.instance()
            .error(
                this,
                "Error: The scheduled event '" + event.getName() + "' is already running",
                null);
        return;
      }

      try {
        event.setRunning(true);

        LogManager.instance()
            .info(
                this,
                "Checking for the execution of the scheduled event '%s' executionId=%d...",
                event.getName(),
                event.nextExecutionId.get());
        try {
          var executeEvent = executeEvent(db);
          if (executeEvent) {
            LogManager.instance()
                .info(
                    this,
                    "Executing scheduled event '%s' executionId=%d...",
                    event.getName(),
                    event.nextExecutionId.get());
            executeEventFunction(db);
          }

        } finally {
          event.setRunning(false);
        }
      } catch (Exception e) {
        LogManager.instance().error(this, "Error during execution of scheduled function", e);
      } finally {
        if (event.timer != null) {
          // RE-SCHEDULE THE NEXT EVENT
          event.schedule(database, user, youTrackDBInternal);
        }
      }
    }

    private boolean executeEvent(DatabaseSessionInternal db) {
      for (var retry = 0; retry < 10; ++retry) {
        try {
          try {
            return db.computeInTx(() -> {
              var eventEntity = db.loadEntity(event.getIdentity());
              if (isEventAlreadyExecuted(eventEntity)) {
                return false;
              }

              event.status = STATUS.RUNNING;
              event.startTime = System.currentTimeMillis();

              eventEntity.setProperty(PROP_STATUS, event.status);
              eventEntity.setProperty(PROP_STARTTIME, event.startTime);
              eventEntity.setProperty(PROP_EXEC_ID, event.nextExecutionId.get());

              return true;
            });
          } catch (RecordNotFoundException e) {
            event.interrupt();
            return false;
          }
        } catch (NeedRetryException e) {
          if (db.computeInTx(() -> {
            var eventEntity = db.loadEntity(event.getIdentity());
            // CONCURRENT UPDATE, PROBABLY EXECUTED BY ANOTHER SERVER
            return isEventAlreadyExecuted(eventEntity);
          })) {
            return false;
          }

          LogManager.instance()
              .info(
                  this,
                  "Cannot change the status of the scheduled event '%s' executionId=%d, retry %d",
                  e,
                  event.getName(),
                  event.nextExecutionId.get(),
                  retry);

        } catch (RecordNotFoundException e) {
          LogManager.instance()
              .info(
                  this,
                  "Scheduled event '%s' executionId=%d not found on database, removing event",
                  e,
                  event.getName(),
                  event.nextExecutionId.get());
          event.interrupt();
          break;
        } catch (Exception e) {
          // SUSPEND EXECUTION
          LogManager.instance()
              .error(
                  this,
                  "Error during starting of scheduled event '%s' executionId=%d",
                  e,
                  event.getName(),
                  event.nextExecutionId.get());

          event.interrupt();
          break;
        }
      }
      return false;
    }

    private void executeEventFunction(DatabaseSessionInternal session) {
      Object result = null;
      try {
        var context = new BasicCommandContext();
        context.setDatabaseSession(session);

        result = session.computeInTx(
            () -> event.getFunction().executeInContext(context, event.getArguments()));
      } finally {
        LogManager.instance()
            .info(
                this,
                "Scheduled event '%s' executionId=%d completed with result: %s",
                event.getName(),
                event.nextExecutionId.get(),
                result);
        for (var retry = 0; retry < 10; ++retry) {
          session.executeInTx(
              () -> {
                try {
                  event.status = STATUS.WAITING;
                  event.save(session);
                } catch (NeedRetryException e) {
                  //continue
                } catch (Exception e) {
                  LogManager.instance()
                      .error(this, "Error on saving status for event '%s'", e,
                          event.getName());
                }
              });
        }
      }
    }

    private boolean isEventAlreadyExecuted(Entity eventEntity) {
      final Long currentExecutionId = eventEntity.getProperty(PROP_EXEC_ID);
      if (currentExecutionId == null) {
        return false;
      }

      if (currentExecutionId >= event.nextExecutionId.get()) {
        LogManager.instance()
            .info(
                this,
                "Scheduled event '%s' with id %d is already running (current id=%d)",
                event.getName(),
                event.nextExecutionId.get(),
                currentExecutionId);
        // ALREADY RUNNING
        return true;
      }
      return false;
    }
  }
}
