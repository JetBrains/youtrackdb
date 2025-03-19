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
package com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.collection.LazyIterator;
import com.jetbrains.youtrack.db.internal.common.collection.MultiCollectionIterator;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkList;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkMap;
import com.jetbrains.youtrack.db.internal.core.db.record.LinkSet;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedList;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedMap;
import com.jetbrains.youtrack.db.internal.core.db.record.TrackedSet;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.id.ChangeableRecordId;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.EntitySerializable;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.string.StringSerializerEmbedded;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.string.StringWriterSerializable;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

@SuppressWarnings({"unchecked", "serial"})
public abstract class RecordSerializerCSVAbstract extends RecordSerializerStringAbstract {

  public static final char FIELD_VALUE_SEPARATOR = ':';

  /**
   * Serialize the link.
   *
   * @param session
   * @param buffer
   * @param iParentRecord
   * @param iLinked       Can be an instance of RID or a Record<?>
   * @return
   */
  private static Identifiable linkToStream(
      DatabaseSessionInternal session, final StringWriter buffer, final EntityImpl iParentRecord,
      Object iLinked) {
    if (iLinked == null)
    // NULL REFERENCE
    {
      return null;
    }

    Identifiable resultRid = null;
    RecordId rid;

    if (iLinked instanceof RID) {
      // JUST THE REFERENCE
      rid = (RecordId) iLinked;

      assert ((RecordId) rid.getIdentity()).isValidPosition() || session.isRemote()
          : "Impossible to serialize invalid link " + rid.getIdentity();
      resultRid = rid;
    } else {
      if (iLinked instanceof String) {
        iLinked = new RecordId((String) iLinked);
      }

      if (!(iLinked instanceof Identifiable)) {
        throw new IllegalArgumentException(
            "Invalid object received. Expected a Identifiable but received type="
                + iLinked.getClass().getName()
                + " and value="
                + iLinked);
      }

      // RECORD
      var iLinkedRecord = ((Identifiable) iLinked).getRecord(session);
      rid = (RecordId) iLinkedRecord.getIdentity();

      assert ((RecordId) rid.getIdentity()).isValidPosition() || session.isRemote()
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

  public Object fieldFromStream(
      DatabaseSessionInternal session, final RecordAbstract iSourceRecord,
      final PropertyType iType,
      SchemaClass iLinkedClass,
      PropertyType iLinkedType,
      final String iName,
      final String iValue) {

    if (iValue == null) {
      return null;
    }

    switch (iType) {
      case EMBEDDEDLIST:
      case EMBEDDEDSET:
        return embeddedCollectionFromStream(session,
            (EntityImpl) iSourceRecord, iType, iLinkedClass, iLinkedType, iValue);

      case LINKSET:
      case LINKLIST: {
        if (iValue.length() == 0) {
          return null;
        }

        // REMOVE BEGIN & END COLLECTIONS CHARACTERS IF IT'S A COLLECTION
        final var value =
            iValue.startsWith("[") || iValue.startsWith("<")
                ? iValue.substring(1, iValue.length() - 1)
                : iValue;

        if (iType == PropertyType.LINKLIST) {
          return unserializeList(session, (EntityImpl) iSourceRecord, value);
        } else {
          return unserializeSet(session, (EntityImpl) iSourceRecord, value);
        }
      }

      case LINKMAP: {
        if (iValue.length() == 0) {
          return null;
        }

        // REMOVE BEGIN & END MAP CHARACTERS
        var value = iValue.substring(1, iValue.length() - 1);

        @SuppressWarnings("rawtypes") final Map map = new LinkMap((EntityImpl) iSourceRecord
        );

        if (value.length() == 0) {
          return map;
        }

        final var items =
            StringSerializerHelper.smartSplit(
                value, StringSerializerHelper.RECORD_SEPARATOR, true, false);

        // EMBEDDED LITERALS
        for (var item : items) {
          if (item != null && !item.isEmpty()) {
            final var entry =
                StringSerializerHelper.smartSplit(item, StringSerializerHelper.ENTRY_SEPARATOR);
            if (!entry.isEmpty()) {
              var mapValue = entry.get(1);
              if (mapValue != null && !mapValue.isEmpty()) {
                mapValue = mapValue.substring(1);
              }
              map.put(
                  fieldTypeFromStream(session, (EntityImpl) iSourceRecord, PropertyType.STRING,
                      entry.get(0)),
                  new RecordId(mapValue));
            }
          }
        }
        return map;
      }

      case EMBEDDEDMAP:
        return embeddedMapFromStream(session, (EntityImpl) iSourceRecord, iLinkedType, iValue,
            iName);

      case LINK:
        if (iValue.length() > 1) {
          var pos = iValue.indexOf(StringSerializerHelper.CLASS_SEPARATOR);
          if (pos > -1) {
            session.getMetadata()
                .getImmutableSchemaSnapshot()
                .getClass(iValue.substring(1, pos));
          } else {
            pos = 0;
          }

          final var linkAsString = iValue.substring(pos + 1);
          try {
            return new RecordId(linkAsString);
          } catch (IllegalArgumentException e) {
            LogManager.instance()
                .error(
                    this,
                    "Error on unmarshalling field '%s' of record '%s': value '%s' is not a link",
                    e,
                    iName,
                    iSourceRecord,
                    linkAsString);
            return new ChangeableRecordId();
          }
        } else {
          return null;
        }

      case EMBEDDED:
        if (iValue.length() > 2) {
          // REMOVE BEGIN & END EMBEDDED CHARACTERS
          final var value = iValue.substring(1, iValue.length() - 1);

          final var embeddedObject = StringSerializerEmbedded.INSTANCE.fromStream(session, value);
          if (embeddedObject instanceof EntityImpl) {
            ((EntityImpl) embeddedObject).setOwner(iSourceRecord);
          }

          // RECORD
          return embeddedObject;
        } else {
          return null;
        }
      case LINKBAG:
        final var value =
            iValue.charAt(0) == StringSerializerHelper.BAG_BEGIN
                ? iValue.substring(1, iValue.length() - 1)
                : iValue;
        return RidBag.fromStream(session, value);
      default:
        return fieldTypeFromStream(session, (EntityImpl) iSourceRecord, iType, iValue);
    }
  }

  public static Map<String, Object> embeddedMapFromStream(
      DatabaseSessionInternal session, final EntityImpl iSourceDocument,
      final PropertyType iLinkedType,
      final String iValue,
      final String iName) {
    if (iValue.length() == 0) {
      return null;
    }

    // REMOVE BEGIN & END MAP CHARACTERS
    var value = iValue.substring(1, iValue.length() - 1);

    @SuppressWarnings("rawtypes")
    Map map;
    if (iLinkedType == PropertyType.LINK || iLinkedType == PropertyType.EMBEDDED) {
      map = new LinkMap(iSourceDocument);
    } else {
      map = new TrackedMap<Object>(iSourceDocument);
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

            final PropertyType linkedType;

            if (iLinkedType == null) {
              if (!mapValue.isEmpty()) {
                linkedType = getType(mapValue);
                if ((iName == null
                    || iSourceDocument.getPropertyType(iName) == null
                    || iSourceDocument.getPropertyType(iName) != PropertyType.EMBEDDEDMAP)
                    && isConvertToLinkedMap(map, linkedType)) {
                  // CONVERT IT TO A LAZY MAP
                  map = new LinkMap(iSourceDocument);
                } else if (map instanceof LinkMap && linkedType != PropertyType.LINK) {
                  map = new TrackedMap<Object>(iSourceDocument, map, null);
                }
              } else {
                linkedType = PropertyType.EMBEDDED;
              }
            } else {
              linkedType = iLinkedType;
            }

            if (linkedType == PropertyType.EMBEDDED && mapValue.length() >= 2) {
              mapValue = mapValue.substring(1, mapValue.length() - 1);
            }

            mapValueObject = fieldTypeFromStream(session, iSourceDocument, linkedType, mapValue);

            if (mapValueObject != null && mapValueObject instanceof EntityImpl) {
              ((EntityImpl) mapValueObject).setOwner(iSourceDocument);
            }
          } else {
            mapValueObject = null;
          }

          final var key = fieldTypeFromStream(session, iSourceDocument, PropertyType.STRING,
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

  public void fieldToStream(
      DatabaseSessionInternal session, final EntityImpl iRecord,
      final StringWriter iOutput,
      final PropertyType iType,
      final SchemaClass iLinkedClass,
      final PropertyType iLinkedType,
      final String iName,
      final Object iValue) {
    if (iValue == null) {
      return;
    }

    switch (iType) {
      case LINK: {
        if (!(iValue instanceof Identifiable)) {
          throw new SerializationException(session,
              "Found an unexpected type during marshalling of a LINK where a Identifiable (RID"
                  + " or any Record) was expected. The string representation of the object is: "
                  + iValue);
        }

        if (!((RecordId) ((Identifiable) iValue).getIdentity()).isValidPosition()
            && iValue instanceof EntityImpl
            && ((EntityImpl) iValue).isEmbedded()) {
          // WRONG: IT'S EMBEDDED!
          fieldToStream(session,
              iRecord,
              iOutput,
              PropertyType.EMBEDDED,
              iLinkedClass,
              iLinkedType,
              iName,
              iValue);
        } else {
          final Object link = linkToStream(session, iOutput, iRecord, iValue);
          if (link != null)
          // OVERWRITE CONTENT
          {
            iRecord.setProperty(iName, link);
          }
        }
        break;
      }

      case LINKLIST: {
        iOutput.append(StringSerializerHelper.LIST_BEGIN);
        final LinkList coll;
        final Iterator<Identifiable> it;
        if (iValue instanceof MultiCollectionIterator<?>) {
          final var iterator =
              (MultiCollectionIterator<Identifiable>) iValue;
          iterator.reset();
          it = iterator;
          coll = null;
        } else if (!(iValue instanceof LinkList)) {
          // FIRST TIME: CONVERT THE ENTIRE COLLECTION
          coll = new LinkList(iRecord);

          if (iValue.getClass().isArray()) {
            var iterab = MultiValue.getMultiValueIterable(iValue);
            for (var i : iterab) {
              coll.add((Identifiable) i);
            }
          } else {
            coll.addAll((Collection<? extends Identifiable>) iValue);
            ((Collection<? extends Identifiable>) iValue).clear();
          }

          iRecord.setProperty(iName, coll);
          it = coll.iterator();
        } else {
          // LAZY LIST
          coll = (LinkList) iValue;
          it = coll.iterator();
        }

        if (it != null && it.hasNext()) {
          final var buffer = new StringWriter(128);
          for (var items = 0; it.hasNext(); items++) {
            if (items > 0) {
              buffer.append(StringSerializerHelper.RECORD_SEPARATOR);
            }

            final var item = it.next();

            final var newRid = linkToStream(session, buffer, iRecord, item);
            if (newRid != null) {
              ((LazyIterator<Identifiable>) it).update(newRid);
            }
          }

          iOutput.append(buffer.toString());
        }

        iOutput.append(StringSerializerHelper.LIST_END);
        break;
      }

      case LINKSET: {
        if (!(iValue instanceof StringWriterSerializable coll)) {
          final Collection<Identifiable> coll;
          // FIRST TIME: CONVERT THE ENTIRE COLLECTION
          if (!(iValue instanceof LinkSet)) {
            final var set = new LinkSet(iRecord);
            set.addAll((Collection<Identifiable>) iValue);
            iRecord.setProperty(iName, set);
            coll = set;
          } else {
            coll = (Collection<Identifiable>) iValue;
          }

          serializeSet(coll, iOutput);

        } else {
          // LAZY SET
          coll.toStream(session, iOutput);
        }
        break;
      }

      case LINKMAP: {
        iOutput.append(StringSerializerHelper.MAP_BEGIN);

        var map = (Map<String, Object>) iValue;

        var invalidMap = false;
        var items = 0;
        for (var entry : map.entrySet()) {
          if (items++ > 0) {
            iOutput.append(StringSerializerHelper.RECORD_SEPARATOR);
          }

          fieldTypeToString(session, iOutput, PropertyType.STRING, entry.getKey());
          iOutput.append(StringSerializerHelper.ENTRY_SEPARATOR);
          final Object link = linkToStream(session, iOutput, iRecord, entry.getValue());

          if (link != null && !invalidMap)
          // IDENTITY IS CHANGED, RE-SET INTO THE COLLECTION TO RECOMPUTE THE HASH
          {
            invalidMap = true;
          }
        }

        if (invalidMap) {
          final var newMap = new LinkMap(iRecord);

          // REPLACE ALL CHANGED ITEMS
          for (var entry : map.entrySet()) {
            newMap.put(entry.getKey(), (Identifiable) entry.getValue());
          }
          map.clear();
          iRecord.setProperty(iName, newMap);
        }

        iOutput.append(StringSerializerHelper.MAP_END);
        break;
      }

      case EMBEDDED:
        if (iValue instanceof DBRecord) {
          iOutput.append(StringSerializerHelper.EMBEDDED_BEGIN);
          toString(session, (DBRecord) iValue, iOutput, null, true);
          iOutput.append(StringSerializerHelper.EMBEDDED_END);
        } else if (iValue instanceof EntitySerializable) {
          final var entity = ((EntitySerializable) iValue).toEntity(session);
          entity.setProperty(EntitySerializable.CLASS_NAME, iValue.getClass().getName());

          iOutput.append(StringSerializerHelper.EMBEDDED_BEGIN);
          toString(session, entity, iOutput, null, true);
          iOutput.append(StringSerializerHelper.EMBEDDED_END);

        } else {
          iOutput.append(iValue.toString());
        }
        break;

      case EMBEDDEDLIST:
        embeddedCollectionToStream(
            session, iOutput, iLinkedClass, iLinkedType, iValue, false);
        break;

      case EMBEDDEDSET:
        embeddedCollectionToStream(
            session, iOutput, iLinkedClass, iLinkedType, iValue, true);
        break;

      case EMBEDDEDMAP: {
        embeddedMapToStream(session, iOutput, iLinkedType, iValue);
        break;
      }

      case LINKBAG: {
        iOutput.append(StringSerializerHelper.BAG_BEGIN);
        ((RidBag) iValue).toStream(session, iOutput);
        iOutput.append(StringSerializerHelper.BAG_END);
        break;
      }

      default:
        fieldTypeToString(session, iOutput, iType, iValue);
    }
  }

  public void embeddedMapToStream(
      DatabaseSessionInternal db,
      final StringWriter iOutput,
      PropertyType iLinkedType,
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
          fieldTypeToString(db, iOutput, PropertyType.STRING, o.getKey());
          iOutput.append(StringSerializerHelper.ENTRY_SEPARATOR);

          if (o.getValue() instanceof EntityImpl
              && ((EntityImpl) o.getValue()).getIdentity().isValidPosition()) {
            fieldTypeToString(db, iOutput, PropertyType.LINK, o.getValue());
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
            fieldTypeToString(db, iOutput, PropertyType.EMBEDDEDSET, o.getValue());
          } else if (o.getValue() instanceof Collection<?>) {
            // SUB LIST
            fieldTypeToString(db, iOutput, PropertyType.EMBEDDEDLIST, o.getValue());
          } else if (o.getValue() instanceof Map<?, ?>) {
            // SUB MAP
            fieldTypeToString(db, iOutput, PropertyType.EMBEDDEDMAP, o.getValue());
          } else {
            // EMBEDDED LITERALS
            if (iLinkedType == null && o.getValue() != null) {
              fieldTypeToString(db,
                  iOutput, PropertyType.getTypeByClass(o.getValue().getClass()), o.getValue());
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

  public Object embeddedCollectionFromStream(
      DatabaseSessionInternal session, final EntityImpl e,
      final PropertyType iType,
      SchemaClass iLinkedClass,
      final PropertyType iLinkedType,
      final String iValue) {
    if (iValue.length() == 0) {
      return null;
    }

    // REMOVE BEGIN & END COLLECTIONS CHARACTERS IF IT'S A COLLECTION
    final String value;
    if (iValue.charAt(0) == StringSerializerHelper.LIST_BEGIN
        || iValue.charAt(0) == StringSerializerHelper.SET_BEGIN) {
      value = iValue.substring(1, iValue.length() - 1);
    } else {
      value = iValue;
    }

    Collection<?> coll;
    if (iLinkedType == PropertyType.LINK) {
      if (e != null) {
        coll =
            (iType == PropertyType.EMBEDDEDLIST
                ? unserializeList(session, e, value)
                : unserializeSet(session, e, value));
      } else {
        if (iType == PropertyType.EMBEDDEDLIST) {
          coll = unserializeList(session, e, value);
        } else {
          return unserializeSet(session, e, value);
        }
      }
    } else {
      coll =
          iType == PropertyType.EMBEDDEDLIST
              ? new TrackedList<Object>(e)
              : new TrackedSet<Object>(e);
    }

    if (value.length() == 0) {
      return coll;
    }

    PropertyType linkedType;

    final var items =
        StringSerializerHelper.smartSplit(
            value, StringSerializerHelper.RECORD_SEPARATOR, true, false);
    for (var item : items) {
      Object objectToAdd = null;
      linkedType = null;

      if (item.equals("null"))
      // NULL VALUE
      {
        objectToAdd = null;
      } else if (item.length() > 2 && item.charAt(0) == StringSerializerHelper.EMBEDDED_BEGIN) {
        // REMOVE EMBEDDED BEGIN/END CHARS
        item = item.substring(1, item.length() - 1);

        if (!item.isEmpty()) {
          // EMBEDDED RECORD, EXTRACT THE CLASS NAME IF DIFFERENT BY THE PASSED (SUB-CLASS OR IT WAS
          // PASSED NULL)
          iLinkedClass = StringSerializerHelper.getRecordClassName(session, item, iLinkedClass);

          if (iLinkedClass != null) {
            var entity = new EntityImpl(session);
            objectToAdd = fromString(session, item, entity, null);
            entity.setClassNameWithoutPropertiesPostProcessing(iLinkedClass.getName());
          } else
          // EMBEDDED OBJECT
          {
            objectToAdd = fieldTypeFromStream(session, e, PropertyType.EMBEDDED, item);
          }
        }
      } else {
        if (linkedType == null) {
          final var begin = item.length() > 0 ? item.charAt(0) : StringSerializerHelper.LINK;

          // AUTO-DETERMINE LINKED TYPE
          if (begin == StringSerializerHelper.LINK) {
            linkedType = PropertyType.LINK;
          } else {
            linkedType = getType(item);
          }

          if (linkedType == null) {
            throw new IllegalArgumentException(
                "Linked type cannot be null. Probably the serialized type has not stored the type"
                    + " along with data");
          }
        }

        objectToAdd = fieldTypeFromStream(session, e, linkedType, item);
      }

      if (objectToAdd != null
          && objectToAdd instanceof EntityImpl
          && coll instanceof RecordElement) {
        ((EntityImpl) objectToAdd).setOwner((RecordElement) coll);
      }

      ((Collection<Object>) coll).add(objectToAdd);
    }

    return coll;
  }

  public void embeddedCollectionToStream(
      DatabaseSessionInternal session,
      final StringWriter iOutput,
      final SchemaClass iLinkedClass,
      final PropertyType iLinkedType,
      final Object iValue,
      final boolean iSet) {
    iOutput.append(iSet ? StringSerializerHelper.SET_BEGIN : StringSerializerHelper.LIST_BEGIN);

    final var iterator = (Iterator<Object>) MultiValue.getMultiValueIterator(iValue);

    var linkedType = iLinkedType;

    for (var i = 0; iterator.hasNext(); ++i) {
      final var o = iterator.next();

      if (i > 0) {
        iOutput.append(StringSerializerHelper.RECORD_SEPARATOR);
      }

      if (o == null) {
        iOutput.append("null");
        continue;
      }

      Identifiable id = null;
      EntityImpl entity = null;

      final SchemaClass linkedClass;
      if (!(o instanceof Identifiable)) {
        if (iLinkedType == null) {
          linkedType = PropertyType.getTypeByClass(o.getClass());
        }

        linkedClass = iLinkedClass;
      } else {
        id = (Identifiable) o;

        if (iLinkedType == null)
        // AUTO-DETERMINE LINKED TYPE
        {
          if (((RecordId) id.getIdentity()).isValidPosition()) {
            linkedType = PropertyType.LINK;
          } else {
            linkedType = PropertyType.EMBEDDED;
          }
        }

        if (id instanceof EntityImpl) {
          entity = (EntityImpl) id;

          if (entity.hasOwners()) {
            linkedType = PropertyType.EMBEDDED;
          }

          assert linkedType == PropertyType.EMBEDDED
              || ((RecordId) id.getIdentity()).isValidPosition()
              || session.isRemote()
              : "Impossible to serialize invalid link " + id.getIdentity();

          SchemaImmutableClass result = null;
          if (entity != null) {
            result = entity.getImmutableSchemaClass(session);
          }
          linkedClass = result;
        } else {
          linkedClass = null;
        }
      }

      if (id != null && linkedType != PropertyType.LINK) {
        iOutput.append(StringSerializerHelper.EMBEDDED_BEGIN);
      }

      if (linkedType == PropertyType.EMBEDDED && o instanceof Identifiable) {
        toString(session, ((Identifiable) o).getRecord(session), iOutput, null);
      } else if (linkedType != PropertyType.LINK && (linkedClass != null || entity != null)) {
        toString(session, entity, iOutput, null, true);
      } else {
        // EMBEDDED LITERALS
        if (iLinkedType == null) {
          linkedType = PropertyType.getTypeByClass(o.getClass());
        }

        fieldTypeToString(session, iOutput, linkedType, o);
      }

      if (id != null && linkedType != PropertyType.LINK) {
        iOutput.append(StringSerializerHelper.EMBEDDED_END);
      }
    }

    iOutput.append(iSet ? StringSerializerHelper.SET_END : StringSerializerHelper.LIST_END);
  }

  protected static boolean isConvertToLinkedMap(Map<?, ?> map, final PropertyType linkedType) {
    var convert = (linkedType == PropertyType.LINK && !(map instanceof LinkMap));
    if (convert) {
      for (var value : map.values()) {
        if (!(value instanceof Identifiable)) {
          return false;
        }
      }
    }
    return convert;
  }

  private void serializeSet(final Collection<Identifiable> coll, final StringWriter iOutput) {
    iOutput.append(StringSerializerHelper.SET_BEGIN);
    var i = 0;
    for (var rid : coll) {
      if (i++ > 0) {
        iOutput.append(',');
      }

      iOutput.append(rid.getIdentity().toString());
    }
    iOutput.append(StringSerializerHelper.SET_END);
  }

  private LinkList unserializeList(DatabaseSessionInternal db, final EntityImpl iSourceRecord,
      final String value) {
    final var coll = new LinkList(iSourceRecord);
    final var items =
        StringSerializerHelper.smartSplit(value, StringSerializerHelper.RECORD_SEPARATOR);
    for (var item : items) {
      if (item.isEmpty()) {
        coll.add(new ChangeableRecordId());
      } else {
        if (item.startsWith("#")) {
          coll.add(new RecordId(item));
        } else {
          final var entity = fromString(db, item);
          if (entity instanceof EntityImpl) {
            ((EntityImpl) entity).setOwner(iSourceRecord);
          }

          coll.add(entity);
        }
      }
    }
    return coll;
  }

  private LinkSet unserializeSet(DatabaseSessionInternal db, final EntityImpl iSourceRecord,
      final String value) {
    final var coll = new LinkSet(iSourceRecord);
    final var items =
        StringSerializerHelper.smartSplit(value, StringSerializerHelper.RECORD_SEPARATOR);
    for (var item : items) {
      if (item.isEmpty()) {
        coll.add(new ChangeableRecordId());
      } else {
        if (item.startsWith("#")) {
          coll.add(new RecordId(item));
        } else {
          final var entity = fromString(db, item);
          if (entity instanceof EntityImpl) {
            ((EntityImpl) entity).setOwner(iSourceRecord);
          }

          coll.add(entity);
        }
      }
    }
    return coll;
  }
}
