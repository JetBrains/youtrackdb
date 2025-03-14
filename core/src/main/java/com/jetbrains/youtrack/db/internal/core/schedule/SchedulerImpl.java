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

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.function.Function;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionOptimistic;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scheduler default implementation.
 *
 * @since Mar 28, 2013
 */
public class SchedulerImpl {

  private static final String RIDS_OF_EVENTS_TO_RESCHEDULE_KEY =
      SchedulerImpl.class.getName() + ".ridsOfEventsToReschedule";
  private static final String DROPPED_EVENTS_MAP = "droppedEventsMap";

  private final ConcurrentHashMap<String, ScheduledEvent> events =
      new ConcurrentHashMap<>();

  private final YouTrackDBInternal youtrackDB;

  public SchedulerImpl(YouTrackDBInternal youtrackDB) {
    this.youtrackDB = youtrackDB;
  }

  public void scheduleEvent(DatabaseSession session, final ScheduledEvent event) {
    if (events.putIfAbsent(event.getName(), event) == null) {
      var database = session.getDatabaseName();
      event.schedule(database, "admin", youtrackDB);
    }
  }

  public ScheduledEvent removeEventInternal(final String eventName) {
    final var event = events.remove(eventName);

    if (event != null) {
      event.interrupt();
    }
    return event;
  }

  public static void onAfterEventDropped(FrontendTransactionOptimistic currentTx,
      EntityImpl eventEntity) {
    @SuppressWarnings("unchecked")
    var droppedSequencesMap = (HashMap<RID, String>) currentTx.getCustomData(DROPPED_EVENTS_MAP);
    if (droppedSequencesMap == null) {
      droppedSequencesMap = new HashMap<>();
    }

    currentTx.setCustomData(DROPPED_EVENTS_MAP, droppedSequencesMap);
    droppedSequencesMap.put(eventEntity.getIdentity(),
        eventEntity.getProperty(ScheduledEvent.PROP_NAME));
  }

  public void onEventDropped(DatabaseSessionInternal session, RID rid) {
    var currentTx = (FrontendTransactionOptimistic) session.getTransactionInternal();

    @SuppressWarnings("unchecked")
    var droppedSequencesMap = (HashMap<RID, String>) currentTx.getCustomData(DROPPED_EVENTS_MAP);
    var eventName = droppedSequencesMap.get(rid);
    removeEventInternal(eventName);
  }

  public void removeEvent(DatabaseSessionInternal session, final String eventName) {
    LogManager.instance().debug(this, "Removing scheduled event '%s'...", eventName);

    final var event = removeEventInternal(eventName);

    if (event != null) {
      // RECORD EXISTS: DELETE THE EVENT RECORD
      session.executeInTx(() -> {
        try {
          event.delete(session);
        } catch (RecordNotFoundException ignore) {
        }
      });
    }
  }

  public void updateEvent(DatabaseSessionInternal session, final ScheduledEvent event) {
    final var oldEvent = events.remove(event.getName());
    if (oldEvent != null) {
      oldEvent.interrupt();
    }
    scheduleEvent(session, event);
    LogManager.instance()
        .debug(
            this,
            "Updated scheduled event '%s' rid=%s...",
            event,
            event.getIdentity());
  }

  public Map<String, ScheduledEvent> getEvents() {
    return events;
  }

  public ScheduledEvent getEvent(final String name) {
    return events.get(name);
  }

  public void load(DatabaseSessionInternal session) {
    if (session.getMetadata().getSchema().existsClass(ScheduledEvent.CLASS_NAME)) {
      try (var result = session.query("select from " + ScheduledEvent.CLASS_NAME)) {
        while (result.hasNext()) {
          var d = result.next();
          scheduleEvent(session, new ScheduledEvent((EntityImpl) d.asEntity(), session));
        }
      }
    }
  }

  public void close() {
    for (var event : events.values()) {
      event.interrupt();
    }
    events.clear();
  }

  public static void create(DatabaseSessionInternal database) {
    if (database
        .getMetadata()
        .getImmutableSchemaSnapshot()
        .existsClass(ScheduledEvent.CLASS_NAME)) {
      return;
    }
    var f = (SchemaClassInternal) database.getMetadata().getSchema()
        .createClass(ScheduledEvent.CLASS_NAME);

    f.createProperty(ScheduledEvent.PROP_NAME, PropertyType.STRING, (PropertyType) null,
            true)
        .setMandatory(true)
        .setNotNull(true);
    f.createIndex(ScheduledEvent.PROP_NAME + "Index", SchemaClass.INDEX_TYPE.UNIQUE,
        ScheduledEvent.PROP_NAME);
    f.createProperty(ScheduledEvent.PROP_RULE, PropertyType.STRING, (PropertyType) null,
            true)
        .setMandatory(true)
        .setNotNull(true);
    f.createProperty(ScheduledEvent.PROP_ARGUMENTS, PropertyType.EMBEDDEDMAP,
        (PropertyType) null,
        true);
    f.createProperty(ScheduledEvent.PROP_STATUS, PropertyType.STRING, (PropertyType) null,
        true);
    f.createProperty(
            ScheduledEvent.PROP_FUNC,
            PropertyType.LINK,
            database.getMetadata().getSchema().getClass(Function.CLASS_NAME), true)
        .setMandatory(true)
        .setNotNull(true);
    f.createProperty(ScheduledEvent.PROP_STARTTIME, PropertyType.DATETIME,
        (PropertyType) null,
        true);
  }

  public void initScheduleRecord(EntityImpl entity) {
    String name = entity.field(ScheduledEvent.PROP_NAME);
    final var event = getEvent(name);
    if (event != null && event.getIdentity().equals(entity.getIdentity())) {
      throw new DatabaseException(
          "Scheduled event with name '" + name + "' already exists in database");
    }
    entity.field(ScheduledEvent.PROP_STATUS, Scheduler.STATUS.STOPPED.name());
  }

  public void preHandleUpdateScheduleInTx(DatabaseSessionInternal session, EntityImpl entity) {
    try {
      final String schedulerName = entity.field(ScheduledEvent.PROP_NAME);
      var event = getEvent(schedulerName);

      if (event != null) {
        // UPDATED EVENT
        final Set<String> dirtyFields;
        var dirtyProperties = entity.getDirtyPropertiesBetweenCallbacksInternal(false, false);

        if (dirtyProperties instanceof Set<String> dirtyFieldsSet) {
          dirtyFields = dirtyFieldsSet;
        } else {
          dirtyFields = new HashSet<>(dirtyProperties);
        }

        if (dirtyFields.contains(ScheduledEvent.PROP_NAME)) {
          throw new ValidationException(session.getDatabaseName(),
              "Scheduled event cannot change name");
        }

        if (dirtyFields.contains(ScheduledEvent.PROP_RULE)) {
          // RULE CHANGED, STOP CURRENT EVENT AND RESCHEDULE IT
          var tx = session.getTransactionInternal();

          @SuppressWarnings("unchecked")
          var rids = (Set<RID>) tx.getCustomData(RIDS_OF_EVENTS_TO_RESCHEDULE_KEY);
          if (rids == null) {
            rids = new HashSet<>();
            tx.setCustomData(RIDS_OF_EVENTS_TO_RESCHEDULE_KEY, rids);
          }

          rids.add(entity.getIdentity());
        }
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "Error on updating scheduled event", ex);
    }
  }

  public void postHandleUpdateScheduleAfterTxCommit(DatabaseSessionInternal session,
      EntityImpl entity) {
    try {
      var tx = session.getTransactionInternal();
      @SuppressWarnings("unchecked")
      var rids = (Set<RID>) tx.getCustomData(RIDS_OF_EVENTS_TO_RESCHEDULE_KEY);

      if (rids != null && rids.contains(entity.getIdentity())) {
        final String schedulerName = entity.field(ScheduledEvent.PROP_NAME);
        var event = getEvent(schedulerName);

        if (event != null) {
          // RULE CHANGED, STOP CURRENT EVENT AND RESCHEDULE IT
          updateEvent(session, new ScheduledEvent(entity, session));
        }
      }
    } catch (Exception ex) {
      LogManager.instance().error(this, "Error on updating scheduled event", ex);
    }
  }
}
