/*
 *
 * Copyright 2013 Geomatys.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrack.db.internal.core.sql.method.misc;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 *
 */
public class SQLMethodValues extends AbstractSQLMethod {

  public static final String NAME = "values";

  public SQLMethodValues() {
    super(NAME);
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      CommandContext iContext,
      Object ioResult,
      Object[] iParams) {
    if (ioResult instanceof Map) {
      return ((Map<?, ?>) ioResult).values();
    }
    if (ioResult instanceof EntityImpl entity) {
      var propertyNames = entity.getPropertyNames();
      var result = new ArrayList<>(propertyNames.size());

      for (var propertyName : propertyNames) {
        result.add(entity.getProperty(propertyName));
      }

      return result;
    }
    if (ioResult instanceof Result res) {
      return res.getPropertyNames().stream()
          .map(field -> res.getProperty(field))
          .collect(Collectors.toList());
    }
    if (ioResult instanceof Collection) {
      List result = new ArrayList();
      for (var o : (Collection) ioResult) {
        result.addAll((Collection) execute(iThis, iCurrentRecord, iContext, o, iParams));
      }
      return result;
    }
    return null;
  }
}
