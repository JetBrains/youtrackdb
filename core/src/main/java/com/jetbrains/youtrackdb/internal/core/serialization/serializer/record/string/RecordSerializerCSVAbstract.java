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
package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.record.DBRecord;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityEmbeddedMapImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkMapIml;
import com.jetbrains.youtrackdb.internal.core.exception.SerializationException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.EntitySerializable;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

@SuppressWarnings({"unchecked", "serial"})
public abstract class RecordSerializerCSVAbstract extends RecordSerializerStringAbstract {

  /**
   * Serialize the link.
   *
   * @param session
   * @param buffer
   * @param iParentRecord
   * @param iLinked       Can be an instance of RID or a Record<?>
   * @return
   */
  @Nullable
  private static Identifiable linkToStream(
      DatabaseSessionInternal session, final StringWriter buffer, final EntityImpl iParentRecord,
      Object iLinked) {
    if (iLinked == null)
    // NULL REFERENCE
    {
      return null;
    }

    Identifiable resultRid = null;
    RecordIdInternal rid;

    if (iLinked instanceof RID) {
      // JUST THE REFERENCE
      rid = (RecordIdInternal) iLinked;

      assert ((RecordIdInternal) rid.getIdentity()).isValidPosition()
          : "Impossible to serialize invalid link " + rid.getIdentity();
      resultRid = rid;
    } else {
      if (iLinked instanceof String) {
        iLinked = RecordIdInternal.fromString((String) iLinked, false);
      }

      if (!(iLinked instanceof Identifiable)) {
        throw new IllegalArgumentException(
            "Invalid object received. Expected a Identifiable but received type="
                + iLinked.getClass().getName()
                + " and value="
                + iLinked);
      }

      // RECORD
      var transaction = session.getActiveTransaction();
      var iLinkedRecord = transaction.load(((Identifiable) iLinked));
      rid = (RecordIdInternal) iLinkedRecord.getIdentity();

      assert ((RecordIdInternal) rid.getIdentity()).isValidPosition()
          : "Impossible to serialize invalid link " + rid.getIdentity();

      if (iParentRecord != null) {
        if (!session.isRetainRecords())
        // REPLACE CURRENT RECORD WITH ITS ID: THIS SAVES A LOT OF MEMORY
        {
          resultRid = iLinkedRecord.getIdentity();
        }
      }
    }

    if (rid.isValidPosition()) {
      buffer.append(rid.toString());
    }

    return resultRid;
  }

  @Nullable
  public static Map<String, Object> embeddedMapFromStream(
      DatabaseSessionEmbedded session, final EntityImpl iSourceDocument,
      final PropertyTypeInternal iLinkedType,
      final String iValue,
      final String iName) {
    if (iValue.length() == 0) {
      return null;
    }

    // REMOVE BEGIN & END MAP CHARACTERS
    var value = iValue.substring(1, iValue.length() - 1);

    @SuppressWarnings("rawtypes")
    Map map;
    if (iLinkedType == PropertyTypeInternal.LINK || iLinkedType == PropertyTypeInternal.EMBEDDED) {
      map = new EntityLinkMapIml(iSourceDocument);
    } else {
      map = new EntityEmbeddedMapImpl<Object>(iSourceDocument);
    }

    if (value.length() == 0) {
      return map;
    }

    final var items =
        StringSerializerHelper.smartSplit(
            value, StringSerializerHelper.RECORD_SEPARATOR, true, false);

    // EMBEDDED LITERALS

    for (var item : items) {
      if (item != null && !item.isEmpty()) {
        final var entries =
            StringSerializerHelper.smartSplit(
                item, StringSerializerHelper.ENTRY_SEPARATOR, true, false);
        if (!entries.isEmpty()) {
          final Object mapValueObject;
          if (entries.size() > 1) {
            var mapValue = entries.get(1);

            final PropertyTypeInternal linkedType;

            if (iLinkedType == null) {
              if (!mapValue.isEmpty()) {
                linkedType = getType(mapValue);
                if ((iName == null
                    || iSourceDocument.getPropertyType(iName) == null
                    || iSourceDocument.getPropertyType(iName) != PropertyType.EMBEDDEDMAP)
                    && isConvertToLinkedMap(map, linkedType)) {
                  // CONVERT IT TO A LAZY MAP
                  map = new EntityLinkMapIml(iSourceDocument);
                } else if (map instanceof EntityLinkMapIml
                    && linkedType != PropertyTypeInternal.LINK) {
                  map = new EntityEmbeddedMapImpl<Object>(iSourceDocument, map);
                }
              } else {
                linkedType = PropertyTypeInternal.EMBEDDED;
              }
            } else {
              linkedType = iLinkedType;
            }

            if (linkedType == PropertyTypeInternal.EMBEDDED && mapValue.length() >= 2) {
              mapValue = mapValue.substring(1, mapValue.length() - 1);
            }

            mapValueObject = fieldTypeFromStream(session, iSourceDocument, linkedType, mapValue);

            if (mapValueObject != null && mapValueObject instanceof EntityImpl) {
              ((EntityImpl) mapValueObject).setOwner(iSourceDocument);
            }
          } else {
            mapValueObject = null;
          }

          final var key = fieldTypeFromStream(session, iSourceDocument, PropertyTypeInternal.STRING,
              entries.get(0));
          try {
            map.put(key, mapValueObject);
          } catch (ClassCastException e) {
            throw BaseException.wrapException(
                new SerializationException(session,
                    "Cannot load map because the type was not the expected: key="
                        + key
                        + "(type "
                        + key.getClass()
                        + "), value="
                        + mapValueObject
                        + "(type "
                        + key.getClass()
                        + ")"),
                e, session);
          }
        }
      }
    }

    return map;
  }

  public void embeddedMapToStream(
      DatabaseSessionInternal db,
      final StringWriter iOutput,
      PropertyTypeInternal iLinkedType,
      final Object iValue) {
    iOutput.append(StringSerializerHelper.MAP_BEGIN);

    if (iValue != null) {
      var items = 0;
      // EMBEDDED OBJECTS
      for (var o : ((Map<String, Object>) iValue).entrySet()) {
        if (items > 0) {
          iOutput.append(StringSerializerHelper.RECORD_SEPARATOR);
        }

        if (o != null) {
          fieldTypeToString(db, iOutput, PropertyTypeInternal.STRING, o.getKey());
          iOutput.append(StringSerializerHelper.ENTRY_SEPARATOR);

          if (o.getValue() instanceof EntityImpl
              && ((EntityImpl) o.getValue()).getIdentity().isValidPosition()) {
            fieldTypeToString(db, iOutput, PropertyTypeInternal.LINK, o.getValue());
          } else if (o.getValue() instanceof DBRecord
              || o.getValue() instanceof EntitySerializable) {
            final EntityImpl record;
            if (o.getValue() instanceof EntityImpl) {
              record = (EntityImpl) o.getValue();
            } else if (o.getValue() instanceof EntitySerializable) {
              record = ((EntitySerializable) o.getValue()).toEntity(db);
              record.setProperty(EntitySerializable.CLASS_NAME, o.getValue().getClass().getName());
            } else {
              record = null;
            }
            iOutput.append(StringSerializerHelper.EMBEDDED_BEGIN);
            toString(db, record, iOutput, null, true);
            iOutput.append(StringSerializerHelper.EMBEDDED_END);
          } else if (o.getValue() instanceof Set<?>) {
            // SUB SET
            fieldTypeToString(db, iOutput, PropertyTypeInternal.EMBEDDEDSET, o.getValue());
          } else if (o.getValue() instanceof Collection<?>) {
            // SUB LIST
            fieldTypeToString(db, iOutput, PropertyTypeInternal.EMBEDDEDLIST, o.getValue());
          } else if (o.getValue() instanceof Map<?, ?>) {
            // SUB MAP
            fieldTypeToString(db, iOutput, PropertyTypeInternal.EMBEDDEDMAP, o.getValue());
          } else {
            // EMBEDDED LITERALS
            if (iLinkedType == null && o.getValue() != null) {
              fieldTypeToString(db,
                  iOutput, PropertyTypeInternal.getTypeByClass(o.getValue().getClass()),
                  o.getValue());
            } else {
              fieldTypeToString(db, iOutput, iLinkedType, o.getValue());
            }
          }
        }
        items++;
      }
    }

    iOutput.append(StringSerializerHelper.MAP_END);
  }

  protected static boolean isConvertToLinkedMap(Map<?, ?> map,
      final PropertyTypeInternal linkedType) {
    var convert = (linkedType == PropertyTypeInternal.LINK && !(map instanceof EntityLinkMapIml));
    if (convert) {
      for (var value : map.values()) {
        if (!(value instanceof Identifiable)) {
          return false;
        }
      }
    }
    return convert;
  }

}
