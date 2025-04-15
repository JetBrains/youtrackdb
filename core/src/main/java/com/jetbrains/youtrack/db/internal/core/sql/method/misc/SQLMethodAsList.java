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
import com.jetbrains.youtrack.db.internal.common.util.Sizeable;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Transforms current value in a List.
 */
public class SQLMethodAsList extends AbstractSQLMethod {

  public static final String NAME = "aslist";

  public SQLMethodAsList() {
    super(NAME);
  }

  @SuppressWarnings("unchecked")
  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      CommandContext iContext,
      Object ioResult,
      Object[] iParams) {
    if (ioResult instanceof List)
    // ALREADY A LIST
    {
      return ioResult;
    }

    if (ioResult == null)
    // NULL VALUE, RETURN AN EMPTY LIST
    {
      return Collections.EMPTY_LIST;
    }

    if (ioResult instanceof Collection<?>) {
      return new ArrayList<Object>((Collection<Object>) ioResult);
    } else if (!(ioResult instanceof EntityImpl) && ioResult instanceof Iterable<?>) {
      ioResult = ((Iterable<?>) ioResult).iterator();
    }

    if (ioResult instanceof Iterator<?>) {
      final List<Object> list;
      if (ioResult instanceof Sizeable sizeable && sizeable.isSizeable()) {
        list = new ArrayList<>(sizeable.size());
      } else {
        list = new ArrayList<>();
      }

      for (var iter = (Iterator<Object>) ioResult; iter.hasNext(); ) {
        list.add(iter.next());
      }
      return list;
    }

    // SINGLE ITEM: ADD IT AS UNIQUE ITEM
    return Collections.singletonList(ioResult);
  }
}
