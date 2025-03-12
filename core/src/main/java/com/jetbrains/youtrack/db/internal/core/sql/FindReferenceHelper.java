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

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helper class to find reference in records.
 */
public class FindReferenceHelper {

  public static List<EntityImpl> findReferences(final Set<RID> iRecordIds,
      final String classList, DatabaseSessionInternal session) {
    final Map<RID, Set<RID>> map = new HashMap<RID, Set<RID>>();
    for (var rid : iRecordIds) {
      map.put(rid, new HashSet<>());
    }

    if (classList == null || classList.isEmpty()) {
      for (var clusterName : session.getClusterNames()) {
        browseCluster(session, iRecordIds, map, clusterName);
      }
    } else {
      final var classes = StringSerializerHelper.smartSplit(classList, ',');
      for (var clazz : classes) {
        if (clazz.startsWith("CLUSTER:")) {
          browseCluster(
              session,
              iRecordIds,
              map,
              clazz.substring(clazz.indexOf("CLUSTER:") + "CLUSTER:".length()));
        } else {
          browseClass(session, iRecordIds, map, clazz);
        }
      }
    }

    final List<EntityImpl> result = new ArrayList<EntityImpl>();
    for (var entry : map.entrySet()) {
      final var entity = new EntityImpl(session);
      result.add(entity);

      entity.field("rid", entry.getKey());
      entity.field("referredBy", entry.getValue());
    }

    return result;
  }

  private static void browseCluster(
      final DatabaseSessionInternal db,
      final Set<RID> iSourceRIDs,
      final Map<RID, Set<RID>> map,
      final String iClusterName) {
    var recordIterator = db.browseCluster(iClusterName);
    while (recordIterator.hasNext()) {
      var record = recordIterator.next();
      if (record instanceof EntityImpl) {
        try {
          for (var fieldName : ((EntityImpl) record).fieldNames()) {
            var value = ((EntityImpl) record).field(fieldName);
            checkObject(db, iSourceRIDs, map, value, record);
          }
        } catch (Exception e) {
          LogManager.instance()
              .debug(FindReferenceHelper.class, "Error reading record " + record.getIdentity(), e);
        }
      }
    }
  }

  private static void browseClass(
      final DatabaseSessionInternal session,
      Set<RID> iSourceRIDs,
      final Map<RID, Set<RID>> map,
      final String iClassName) {
    final var clazz = session.getMetadata().getImmutableSchemaSnapshot().getClass(iClassName);

    if (clazz == null) {
      throw new CommandExecutionException(session, "Class '" + iClassName + "' was not found");
    }

    for (var i : clazz.getClusterIds()) {
      browseCluster(session, iSourceRIDs, map, session.getClusterNameById(i));
    }
  }

  private static void checkObject(
      DatabaseSessionInternal db, final Set<RID> iSourceRIDs,
      final Map<RID, Set<RID>> map,
      final Object value,
      final DBRecord iRootObject) {
    if (value instanceof Identifiable) {
      checkRecord(db, iSourceRIDs, map, (Identifiable) value, iRootObject);
    } else if (value instanceof Collection<?>) {
      checkCollection(db, iSourceRIDs, map, (Collection<?>) value, iRootObject);
    } else if (value instanceof Map<?, ?>) {
      checkMap(db, iSourceRIDs, map, (Map<?, ?>) value, iRootObject);
    }
  }

  private static void checkCollection(
      DatabaseSessionInternal db, final Set<RID> iSourceRIDs,
      final Map<RID, Set<RID>> map,
      final Collection<?> values,
      final DBRecord iRootObject) {
    for (var value : values) {
      checkObject(db, iSourceRIDs, map, value, iRootObject);
    }
  }

  private static void checkMap(
      DatabaseSessionInternal db, final Set<RID> iSourceRIDs,
      final Map<RID, Set<RID>> map,
      final Map<?, ?> values,
      final DBRecord iRootObject) {
    for (var o : values.values()) {
      checkObject(db, iSourceRIDs, map, o, iRootObject);
    }
  }

  private static void checkRecord(
      DatabaseSessionInternal db, final Set<RID> iSourceRIDs,
      final Map<RID, Set<RID>> map,
      final Identifiable value,
      final DBRecord iRootObject) {
    if (iSourceRIDs.contains(value.getIdentity())) {
      map.get(value.getIdentity()).add(iRootObject.getIdentity());
    } else if (!((RecordId) value.getIdentity()).isValid()
        && value.getRecord(db) instanceof EntityImpl) {
      // embedded entity
      EntityImpl entity = value.getRecord(db);
      for (var fieldName : entity.fieldNames()) {
        var fieldValue = entity.field(fieldName);
        checkObject(db, iSourceRIDs, map, fieldValue, iRootObject);
      }
    }
  }
}
