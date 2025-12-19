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

package com.jetbrains.youtrackdb.internal.core.schedule;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import java.util.Map;

/**
 * Scheduler interface. <<<<<<< HEAD
 *
 * @since Mar 28, 2013
 */
public interface Scheduler {

  enum STATUS {
    RUNNING,
    STOPPED,
    WAITING
  }

  /**
   * Creates a new scheduled event.
   */
  void scheduleEvent(DatabaseSession session, ScheduledEvent event);

  /**
   * Removes a scheduled event.
   *
   * @param session
   * @param eventName Event's name
   */
  void removeEvent(DatabaseSession session, String eventName);

  /**
   * Updates a scheduled event.
   */
  void updateEvent(DatabaseSession session, ScheduledEvent event);

  /**
   * Returns all the scheduled events.
   *
   * @return
   */
  Map<String, ScheduledEvent> getEvents();

  /**
   * Returns a scheduled event by name.
   *
   * @param eventName Event's name
   */
  ScheduledEvent getEvent(String eventName);

  /**
   * Loads the scheduled events from database in memory and schedule them.
   */
  @Deprecated
  void load();

  /**
   * Shuts down the scheduler.
   */
  @Deprecated
  void close();

  /**
   * Creates the scheduler classes on database.
   */
  @Deprecated
  void create();
}
