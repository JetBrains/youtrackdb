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
package com.jetbrains.youtrackdb.internal.core.iterator;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Iterator;
import javax.annotation.Nonnull;

/**
 * Iterator class to browse forward and backward the records of a collection. Once browsed in a
 * direction, the iterator cannot change it. This iterator with "live updates" set is able to catch
 * updates to the collection sizes while browsing. This is the case when concurrent clients/threads
 * insert and remove item in any collection the iterator is browsing. If the collection are hot removed by
 * from the database the iterator could be invalid and throw exception of collection not found.
 */
public class RecordIteratorClass implements Iterator<EntityImpl> {

  @Nonnull
  private final RecordIteratorCollections<EntityImpl> iterator;

  public RecordIteratorClass(
      @Nonnull final DatabaseSessionInternal session,
      @Nonnull final String className,
      final boolean polymorphic, boolean forwardDirection) {
    this(session, getSchemaClassInternal(session, className), polymorphic, forwardDirection);
  }

  public RecordIteratorClass(@Nonnull final DatabaseSessionInternal session,
      @Nonnull final SchemaClassInternal targetClass,
      final boolean polymorphic, boolean forwardDirection) {
    var collectionIds = polymorphic ? targetClass.getPolymorphicCollectionIds()
        : targetClass.getCollectionIds();
    collectionIds = SchemaClassImpl.readableCollections(session, collectionIds,
        targetClass.getName());

    iterator = new RecordIteratorCollections<>(session, collectionIds, forwardDirection);
  }

  @Override
  public boolean hasNext() {
    return iterator.hasNext();
  }

  @Override
  public EntityImpl next() {
    return iterator.next();
  }

  private static SchemaClassInternal getSchemaClassInternal(DatabaseSessionInternal session,
      String className) {
    var targetClass = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
        .getClass(className);
    if (targetClass == null) {
      throw new IllegalArgumentException(
          "Class '" + className + "' was not found in database schema");
    }

    return targetClass;
  }
}
