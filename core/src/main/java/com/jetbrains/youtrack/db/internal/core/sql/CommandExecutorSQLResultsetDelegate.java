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
package com.jetbrains.youtrack.db.internal.core.sql;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Iterator;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * SQL UPDATE command.
 */
@SuppressWarnings("unchecked")
public class CommandExecutorSQLResultsetDelegate extends CommandExecutorSQLDelegate
    implements IterableRecordSource {

  @Nullable
  @Override
  public Iterator<Identifiable> iterator(DatabaseSessionInternal session,
      final Map<Object, Object> iArgs) {
    return null;
  }

  public Iterable<Identifiable> toIterable(DatabaseSessionInternal session,
      Map<Object, Object> iArgs) {
    return new Iterable<>() {
      @Nullable
      @Override
      public Iterator<Identifiable> iterator() {
        return null;
      }
    };
  }
}
