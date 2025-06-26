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

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.function.Function;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds a OSchedulerEvent with a fluent interface
 *
 * @since v2.2
 */
public class ScheduledEventBuilder {

  public Map<String, Object> properties = new HashMap<>();

  public ScheduledEventBuilder() {
  }

  public ScheduledEventBuilder setFunction(final Function function) {
    properties.put(ScheduledEvent.PROP_FUNC, function);
    return this;
  }

  public ScheduledEventBuilder setRule(final String rule) {
    properties.put(ScheduledEvent.PROP_RULE, rule);
    return this;
  }

  public ScheduledEventBuilder setArguments(final Map<Object, Object> arguments) {
    properties.put(ScheduledEvent.PROP_ARGUMENTS, arguments);
    return this;
  }

  public ScheduledEventBuilder setStartTime(final Date startTime) {
    properties.put(ScheduledEvent.PROP_STARTTIME, startTime);
    return this;
  }

  public ScheduledEventBuilder setName(final String name) {
    properties.put(ScheduledEvent.PROP_NAME, name);
    return this;
  }

  public ScheduledEvent build(DatabaseSessionInternal session) {
    var entity = (EntityImpl) session.newEntity(ScheduledEvent.CLASS_NAME);
    entity.updateFromMap(properties);

    return new ScheduledEvent(entity, session);
  }

  @Override
  public String toString() {
    return "ScheduledEventBuilder{" +
        "properties=" + properties +
        '}';
  }
}
