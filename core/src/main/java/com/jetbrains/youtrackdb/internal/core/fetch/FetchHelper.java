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
package com.jetbrains.youtrackdb.internal.core.fetch;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.collection.MultiCollectionIterator;
import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.string.JSONSerializerJackson.FormatSettings;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Helper class for fetching.
 */
public class FetchHelper {

  public static final String DEFAULT = "*:0";
  public static final FetchPlan DEFAULT_FETCHPLAN = new FetchPlan(DEFAULT);

  @Nullable
  public static FetchPlan buildFetchPlan(final String iFetchPlan) {
    if (iFetchPlan == null) {
      return null;
    }

    if (DEFAULT.equals(iFetchPlan)) {
      return DEFAULT_FETCHPLAN;
    }

    return new FetchPlan(iFetchPlan);
  }

  public static void fetch(
      DatabaseSessionEmbedded db, final DBRecord rootRecord,
      final Object userObject,
      final FetchPlan fetchPlan,
      final FetchListener listener,
      final FetchContext context,
      final String format) {
    try {
      if (rootRecord instanceof EntityImpl record) {
        // SCHEMA AWARE
        final var parsedRecords = new Object2IntOpenHashMap<RID>();
        parsedRecords.defaultReturnValue(-1);

        final var isEmbedded = record.isEmbedded() || !record.getIdentity().isPersistent();
        if (!isEmbedded) {
          parsedRecords.put(rootRecord.getIdentity(), 0);
        }

        if (!format.contains("shallow")) {
          processRecordRidMap(db, record, fetchPlan, 0, 0, -1, parsedRecords, "", context);
        }
        processRecord(db,
            record, userObject, fetchPlan, 0, 0, -1, parsedRecords, "", listener, context, format);
      }
    } catch (final Exception e) {
      LogManager.instance()
          .error(FetchHelper.class, "Fetching error on record %s", e, rootRecord.getIdentity());
    }
  }

  public static void checkFetchPlanValid(final String iFetchPlan) {

    if (iFetchPlan != null && !iFetchPlan.isEmpty()) {
      // CHECK IF THERE IS SOME FETCH-DEPTH
      final var planParts = StringSerializerHelper.split(iFetchPlan, ' ');
      if (!planParts.isEmpty()) {
        for (var planPart : planParts) {
          final var parts = StringSerializerHelper.split(planPart, ':');
          if (parts.size() != 2) {
            throw new IllegalArgumentException("Fetch plan '" + iFetchPlan + "' is invalid");
          }
        }
      } else {
        throw new IllegalArgumentException("Fetch plan '" + iFetchPlan + "' is invalid");
      }
    }
  }

  private static int getDepthLevel(
      final FetchPlan iFetchPlan, final String iFieldPath, final int iCurrentLevel) {
    if (iFetchPlan == null) {
      return 0;
    }
    return iFetchPlan.getDepthLevel(iFieldPath, iCurrentLevel);
  }

  public static void processRecordRidMap(
      DatabaseSessionEmbedded db, final EntityImpl record,
      final FetchPlan iFetchPlan,
      final int iCurrentLevel,
      final int iLevelFromRoot,
      final int iFieldDepthLevel,
      final Object2IntOpenHashMap<RID> parsedRecords,
      final String iFieldPathFromRoot,
      final FetchContext iContext) {
    if (iFetchPlan == null) {
      return;
    }

    if (iFetchPlan == FetchHelper.DEFAULT_FETCHPLAN) {
      return;
    }

    Object fieldValue;
    for (var fieldName : record.getPropertyNamesInternal(false, true)) {
      int depthLevel;
      final var fieldPath =
          !iFieldPathFromRoot.isEmpty() ? iFieldPathFromRoot + "." + fieldName : fieldName;

      depthLevel = getDepthLevel(iFetchPlan, fieldPath, iCurrentLevel);
      if (depthLevel == -2) {
        continue;
      }
      if (iFieldDepthLevel > -1) {
        depthLevel = iFieldDepthLevel;
      }

      fieldValue = record.getProperty(fieldName);
      if (fieldValue == null
          || !(fieldValue instanceof Identifiable)
          && (!(fieldValue instanceof Iterable<?>)
          || !((Iterable<?>) fieldValue).iterator().hasNext()
          || ((Iterable<?>) fieldValue).iterator().next() == null)
          && (!(fieldValue instanceof Collection<?>)
          || ((Collection<?>) fieldValue).isEmpty()
          || !(((Collection<?>) fieldValue).iterator().next() instanceof Identifiable))
          && (!(fieldValue.getClass().isArray())
          || Array.getLength(fieldValue) == 0
          || !(Array.get(fieldValue, 0) instanceof Identifiable))
          && (!(fieldValue instanceof MultiCollectionIterator<?>))
          && (!(fieldValue instanceof Map<?, ?>)
          || ((Map<?, ?>) fieldValue).isEmpty()
          || !(((Map<?, ?>) fieldValue).values().iterator().next()
          instanceof Identifiable))) {
        //noinspection UnnecessaryContinue
        continue;
      } else {
        try {
          final var isEmbedded = isEmbedded(fieldValue);
          if (!(isEmbedded && iContext.fetchEmbeddedDocuments())
              && !iFetchPlan.has(fieldPath, iCurrentLevel)
              && depthLevel > -1
              && iCurrentLevel >= depthLevel)
          // MAX DEPTH REACHED: STOP TO FETCH THIS FIELD
          {
            continue;
          }

          final var nextLevel = isEmbedded ? iLevelFromRoot : iLevelFromRoot + 1;

          if (fieldValue instanceof RecordIdInternal) {
            var transaction = db.getActiveTransaction();
            fieldValue = transaction.load(((RecordIdInternal) fieldValue));
          }

          fetchRidMap(db,
              iFetchPlan,
              fieldValue,
              iCurrentLevel,
              nextLevel,
              iFieldDepthLevel,
              parsedRecords,
              fieldPath, iContext);
        } catch (Exception e) {
          LogManager.instance()
              .error(FetchHelper.class, "Fetching error on record %s", e, record.getIdentity());
        }
      }
    }
  }

  private static void fetchRidMap(
      DatabaseSessionEmbedded db, final FetchPlan iFetchPlan,
      final Object fieldValue,
      final int iCurrentLevel,
      final int iLevelFromRoot,
      final int iFieldDepthLevel,
      final Object2IntOpenHashMap<RID> parsedRecords,
      final String iFieldPathFromRoot,
      final FetchContext iContext) {
    if (fieldValue == null) {
      //noinspection UnnecessaryReturnStatement
      return;
    } else if (fieldValue instanceof EntityImpl) {
      fetchDocumentRidMap(db,
          iFetchPlan,
          fieldValue,
          iCurrentLevel,
          iLevelFromRoot,
          iFieldDepthLevel,
          parsedRecords,
          iFieldPathFromRoot, iContext);
    } else if (fieldValue instanceof Iterable<?>) {
      fetchCollectionRidMap(db,
          iFetchPlan,
          fieldValue,
          iCurrentLevel,
          iLevelFromRoot,
          iFieldDepthLevel,
          parsedRecords,
          iFieldPathFromRoot, iContext);
    } else if (fieldValue.getClass().isArray()) {
      fetchArrayRidMap(db,
          iFetchPlan,
          fieldValue,
          iCurrentLevel,
          iLevelFromRoot,
          iFieldDepthLevel,
          parsedRecords,
          iFieldPathFromRoot, iContext);
    } else if (fieldValue instanceof Map<?, ?>) {
      fetchMapRidMap(db,
          iFetchPlan,
          fieldValue,
          iCurrentLevel,
          iLevelFromRoot,
          iFieldDepthLevel,
          parsedRecords,
          iFieldPathFromRoot, iContext);
    }
  }

  private static void fetchDocumentRidMap(
      DatabaseSessionEmbedded db, final FetchPlan iFetchPlan,
      Object fieldValue,
      final int iCurrentLevel,
      final int iLevelFromRoot,
      final int iFieldDepthLevel,
      final Object2IntOpenHashMap<RID> parsedRecords,
      final String iFieldPathFromRoot,
      final FetchContext iContext) {
    updateRidMap(db,
        iFetchPlan,
        (EntityImpl) fieldValue,
        iCurrentLevel,
        iLevelFromRoot,
        iFieldDepthLevel,
        parsedRecords,
        iFieldPathFromRoot, iContext);
  }

  @SuppressWarnings("unchecked")
  private static void fetchCollectionRidMap(
      DatabaseSessionEmbedded db, final FetchPlan iFetchPlan,
      final Object fieldValue,
      final int iCurrentLevel,
      final int iLevelFromRoot,
      final int iFieldDepthLevel,
      final Object2IntOpenHashMap<RID> parsedRecords,
      final String iFieldPathFromRoot,
      final FetchContext iContext) {
    final var linked = (Iterable<Identifiable>) fieldValue;
    for (var d : linked) {
      if (d != null) {
        // GO RECURSIVELY
        var transaction = db.getActiveTransaction();
        d = transaction.load(d);

        updateRidMap(db,
            iFetchPlan,
            (EntityImpl) d,
            iCurrentLevel,
            iLevelFromRoot,
            iFieldDepthLevel,
            parsedRecords,
            iFieldPathFromRoot, iContext);
      }
    }
  }

  private static void fetchArrayRidMap(
      DatabaseSessionEmbedded db, final FetchPlan iFetchPlan,
      final Object fieldValue,
      final int iCurrentLevel,
      final int iLevelFromRoot,
      final int iFieldDepthLevel,
      final Object2IntOpenHashMap<RID> parsedRecords,
      final String iFieldPathFromRoot,
      final FetchContext iContext) {
    if (fieldValue instanceof EntityImpl[] linked) {
      for (var d : linked)
      // GO RECURSIVELY
      {
        updateRidMap(db,
            iFetchPlan,
            d,
            iCurrentLevel,
            iLevelFromRoot,
            iFieldDepthLevel,
            parsedRecords,
            iFieldPathFromRoot, iContext);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static void fetchMapRidMap(
      DatabaseSessionEmbedded db, final FetchPlan iFetchPlan,
      Object fieldValue,
      final int iCurrentLevel,
      final int iLevelFromRoot,
      final int iFieldDepthLevel,
      final Object2IntOpenHashMap<RID> parsedRecords,
      final String iFieldPathFromRoot,
      final FetchContext iContext) {
    final var linked = (Map<String, EntityImpl>) fieldValue;
    for (var d : (linked).values())
    // GO RECURSIVELY
    {
      updateRidMap(db,
          iFetchPlan,
          d,
          iCurrentLevel,
          iLevelFromRoot,
          iFieldDepthLevel,
          parsedRecords,
          iFieldPathFromRoot, iContext);
    }
  }

  private static void updateRidMap(
      DatabaseSessionEmbedded db, final FetchPlan iFetchPlan,
      final EntityImpl fieldValue,
      final int iCurrentLevel,
      final int iLevelFromRoot,
      final int iFieldDepthLevel,
      final Object2IntOpenHashMap<RID> parsedRecords,
      final String iFieldPathFromRoot,
      final FetchContext iContext) {
    if (fieldValue == null) {
      return;
    }

    final var fetchedLevel = parsedRecords.getInt(fieldValue.getIdentity());
    var currentLevel = iCurrentLevel + 1;
    var fieldDepthLevel = iFieldDepthLevel;
    if (iFetchPlan != null && iFetchPlan.has(iFieldPathFromRoot, iCurrentLevel)) {
      currentLevel = 1;
      fieldDepthLevel = iFetchPlan.getDepthLevel(iFieldPathFromRoot, iCurrentLevel);
    }

    final var isEmbedded = isEmbedded(fieldValue);

    if (isEmbedded || fetchedLevel == -1) {
      if (!isEmbedded) {
        parsedRecords.put(fieldValue.getIdentity(), iLevelFromRoot);
      }

      processRecordRidMap(db,
          fieldValue,
          iFetchPlan,
          currentLevel,
          iLevelFromRoot,
          fieldDepthLevel,
          parsedRecords,
          iFieldPathFromRoot, iContext);
    }
  }

  private static void processRecord(
      DatabaseSessionEmbedded db, final EntityImpl record,
      final Object userObject,
      final FetchPlan fetchPlan,
      final int currentLevel,
      final int levelFromRoot,
      final int fieldDepthLevel,
      final Object2IntOpenHashMap<RID> parsedRecords,
      final String fieldPathFromRoot,
      final FetchListener fetchListener,
      final FetchContext fetchContext,
      final String format) {
    if (record == null) {
      return;
    }
    if (!fetchListener.requireFieldProcessing() && fetchPlan == FetchHelper.DEFAULT_FETCHPLAN) {
      return;
    }
    final var settings =
        new FormatSettings(format);

    // Pre-process to gather fieldTypes
    fetchContext.onBeforeFetch(record);
    if (settings.keepTypes) {
      for (final var fieldName : record.getPropertyNamesInternal(false, true)) {
        processFieldTypes(
            record,
            userObject,
            fetchPlan,
            currentLevel,
            fieldDepthLevel,
            fieldPathFromRoot,
            fetchContext,
            format,
            new HashSet<>(),
            fieldName);
      }
      fetchContext.onAfterFetch(db, record);
    }

    fetchContext.onBeforeFetch(record);
    final Set<String> toRemove = new HashSet<>();
    for (final var fieldName : record.getPropertyNamesInternal(false, true)) {
      process(db,
          record,
          userObject,
          fetchPlan,
          currentLevel,
          levelFromRoot,
          fieldDepthLevel,
          parsedRecords,
          fieldPathFromRoot,
          fetchListener,
          fetchContext,
          format,
          toRemove, fieldName);
    }
    for (final var fieldName : toRemove) {
      fetchListener.skipStandardField(record, fieldName, fetchContext, userObject, format);
    }
    if (settings.keepTypes) {
      fetchContext.onAfterFetch(db, record);
    }
  }

  private static void processFieldTypes(
      EntityImpl record,
      Object userObject,
      FetchPlan fetchPlan,
      int currentLevel,
      int fieldDepthLevel,
      String fieldPathFromRoot,
      FetchContext fetchContext,
      String format,
      Set<String> toRemove,
      String fieldName) {
    Object fieldValue;
    final var fieldPath =
        !fieldPathFromRoot.isEmpty() ? fieldPathFromRoot + "." + fieldName : fieldName;
    int depthLevel;
    depthLevel = getDepthLevel(fetchPlan, fieldPath, currentLevel);
    if (depthLevel == -2) {
      toRemove.add(fieldName);
      return;
    }
    if (fieldDepthLevel > -1) {
      depthLevel = fieldDepthLevel;
    }

    Object result = null;
    if (record != null) {
      result = record.getProperty(fieldName);
    }
    fieldValue = result;
    final var fieldType = PropertyTypeInternal.convertFromPublicType(
        record.getPropertyType(fieldName));
    var fetch =
        !format.contains("shallow")
            && (!(fieldValue instanceof Identifiable)
            || depthLevel == -1
            || currentLevel <= depthLevel
            || (fetchPlan != null && fetchPlan.has(fieldPath, currentLevel)));
    final var isEmbedded = isEmbedded(fieldValue);

    if (!fetch && isEmbedded && fetchContext.fetchEmbeddedDocuments()) {
      // EMBEDDED, GO DEEPER
      fetch = true;
    }

    if (format.contains("shallow")
        || fieldValue == null
        || (!fetch && fieldValue instanceof Identifiable)
        || !(fieldValue instanceof Identifiable)
        && (!(fieldValue.getClass().isArray())
        || Array.getLength(fieldValue) == 0
        || !(Array.get(fieldValue, 0) instanceof Identifiable))
        && !containsIdentifiers(fieldValue)) {
      fetchContext.onBeforeStandardField(fieldValue, fieldName, userObject, fieldType);
    }
  }

  private static void process(
      DatabaseSessionEmbedded db, final EntityImpl record,
      final Object userObject,
      final FetchPlan fetchPlan,
      final int currentLevel,
      final int levelFromRoot,
      final int fieldDepthLevel,
      final Object2IntOpenHashMap<RID> parsedRecords,
      final String fieldPathFromRoot,
      final FetchListener fetchListener,
      final FetchContext fetchContext,
      final String format,
      final Set<String> toRemove,
      final String fieldName) {
    final var settings =
        new FormatSettings(format);

    Object fieldValue;
    final var fieldPath =
        !fieldPathFromRoot.isEmpty() ? fieldPathFromRoot + "." + fieldName : fieldName;
    int depthLevel;
    depthLevel = getDepthLevel(fetchPlan, fieldPath, currentLevel);
    if (depthLevel == -2) {
      toRemove.add(fieldName);
      return;
    }
    if (fieldDepthLevel > -1) {
      depthLevel = fieldDepthLevel;
    }

    Object result = null;
    if (record != null) {
      result = record.getProperty(fieldName);
    }
    fieldValue = result;
    final var fieldType = record.getPropertyType(fieldName);
    var fetch =
        !format.contains("shallow")
            && (!(fieldValue instanceof Identifiable)
            || depthLevel == -1
            || currentLevel <= depthLevel
            || (fetchPlan != null && fetchPlan.has(fieldPath, currentLevel)));
    final var isEmbedded = isEmbedded(fieldValue);

    if (!fetch && isEmbedded && fetchContext.fetchEmbeddedDocuments()) {
      // EMBEDDED, GO DEEPER
      fetch = true;
    }

    if (format.contains("shallow")
        || fieldValue == null
        || (!fetch && fieldValue instanceof Identifiable)
        || !(fieldValue instanceof Identifiable)
        && (!(fieldValue instanceof Iterable<?>)
        || !((Iterable<?>) fieldValue).iterator().hasNext()
        || !(((Iterable<?>) fieldValue).iterator().next() instanceof Identifiable))
        && (!(fieldValue.getClass().isArray())
        || Array.getLength(fieldValue) == 0
        || !(Array.get(fieldValue, 0) instanceof Identifiable))
        && !containsIdentifiers(fieldValue)) {
      fetchContext.onBeforeStandardField(fieldValue, fieldName, userObject,
          PropertyTypeInternal.convertFromPublicType(fieldType));
      fetchListener.processStandardField(db,
          record, fieldValue, fieldName, fetchContext, userObject, format,
          PropertyTypeInternal.convertFromPublicType(fieldType));
      fetchContext.onAfterStandardField(fieldValue, fieldName, userObject,
          PropertyTypeInternal.convertFromPublicType(fieldType));
    } else {
      try {
        if (fetch) {
          final var nextLevel = isEmbedded ? levelFromRoot : levelFromRoot + 1;
          fetch(db,
              record,
              userObject,
              fetchPlan,
              fieldValue,
              fieldName,
              currentLevel,
              nextLevel,
              fieldDepthLevel,
              parsedRecords,
              fieldPath,
              fetchListener,
              fetchContext, settings);
        }
      } catch (final Exception e) {
        LogManager.instance()
            .error(FetchHelper.class, "Fetching error on record %s", e, record.getIdentity());
      }
    }
  }

  private static boolean containsIdentifiers(Object fieldValue) {
    if (!MultiValue.isMultiValue(fieldValue)) {
      return false;
    }
    for (var item : MultiValue.getMultiValueIterable(fieldValue)) {
      if (item instanceof Identifiable) {
        return true;
      }
      if (containsIdentifiers(item)) {
        return true;
      }
    }
    return false;
  }

  public static boolean isEmbedded(Object fieldValue) {
    var isEmbedded =
        fieldValue instanceof EntityImpl
            && (((EntityImpl) fieldValue).isEmbedded()
            || !((EntityImpl) fieldValue).getIdentity().isPersistent());

    // ridbag can contain only edges no embedded documents are allowed.
    if (fieldValue instanceof LinkBag) {
      return false;
    }
    if (!isEmbedded) {
      try {
        final var f = MultiValue.getFirstValue(fieldValue);
        isEmbedded =
            f != null
                && (f instanceof EntityImpl
                && (((EntityImpl) f).isEmbedded()
                || !((EntityImpl) f).getIdentity().isPersistent()));
      } catch (Exception e) {
        LogManager.instance().error(FetchHelper.class, "", e);
        // IGNORE IT
      }
    }
    return isEmbedded;
  }

  private static void fetch(
      DatabaseSessionEmbedded db, final EntityImpl iRootRecord,
      final Object iUserObject,
      final FetchPlan iFetchPlan,
      final Object fieldValue,
      final String fieldName,
      final int iCurrentLevel,
      final int iLevelFromRoot,
      final int iFieldDepthLevel,
      final Object2IntOpenHashMap<RID> parsedRecords,
      final String iFieldPathFromRoot,
      final FetchListener iListener,
      final FetchContext iContext,
      final FormatSettings settings)
      throws IOException {
    var currentLevel = iCurrentLevel + 1;
    var fieldDepthLevel = iFieldDepthLevel;
    if (iFetchPlan != null && iFetchPlan.has(iFieldPathFromRoot, iCurrentLevel)) {
      currentLevel = 0;
      fieldDepthLevel = iFetchPlan.getDepthLevel(iFieldPathFromRoot, iCurrentLevel);
    }

    if (fieldValue == null) {
      iListener.processStandardField(db, iRootRecord, null, fieldName, iContext, iUserObject, "",
          null);
    } else if (fieldValue instanceof Identifiable) {
      fetchEntity(db,
          iRootRecord,
          iUserObject,
          iFetchPlan,
          (Identifiable) fieldValue,
          fieldName,
          currentLevel,
          iLevelFromRoot,
          fieldDepthLevel,
          parsedRecords,
          iFieldPathFromRoot,
          iListener,
          iContext, settings);
    } else if (fieldValue instanceof Map<?, ?>) {
      fetchMap(db,
          iRootRecord,
          iUserObject,
          iFetchPlan,
          fieldValue,
          fieldName,
          currentLevel,
          iLevelFromRoot,
          fieldDepthLevel,
          parsedRecords,
          iFieldPathFromRoot,
          iListener,
          iContext, settings);
    } else if (MultiValue.isMultiValue(fieldValue)) {
      fetchCollection(db,
          iRootRecord,
          iUserObject,
          iFetchPlan,
          fieldValue,
          fieldName,
          currentLevel,
          iLevelFromRoot,
          fieldDepthLevel,
          parsedRecords,
          iFieldPathFromRoot,
          iListener,
          iContext, settings);
    } else if (fieldValue.getClass().isArray()) {
      fetchArray(db,
          iRootRecord,
          iUserObject,
          iFetchPlan,
          fieldValue,
          fieldName,
          currentLevel,
          iLevelFromRoot,
          fieldDepthLevel,
          parsedRecords,
          iFieldPathFromRoot,
          iListener,
          iContext, settings);
    }
  }

  @SuppressWarnings("unchecked")
  private static void fetchMap(
      DatabaseSessionEmbedded db, final EntityImpl iRootRecord,
      final Object iUserObject,
      final FetchPlan iFetchPlan,
      Object fieldValue,
      String fieldName,
      final int iCurrentLevel,
      final int iLevelFromRoot,
      final int iFieldDepthLevel,
      final Object2IntOpenHashMap<RID> parsedRecords,
      final String iFieldPathFromRoot,
      final FetchListener iListener,
      final FetchContext iContext,
      final FormatSettings settings) {
    final var linked = (Map<String, Object>) fieldValue;
    iContext.onBeforeMap(db, iRootRecord, fieldName, iUserObject);

    for (Object key : linked.keySet()) {
      final var o = linked.get(key.toString());

      if (o instanceof Identifiable identifiable) {
        var transaction = db.getActiveTransaction();
        var r = transaction.load(identifiable);
        if (r instanceof EntityImpl d) {
          // GO RECURSIVELY
          final var fieldDepthLevel = parsedRecords.getInt(d.getIdentity());
          if (!d.getIdentity().isValidPosition()
              || (fieldDepthLevel > -1 && fieldDepthLevel == iLevelFromRoot)) {
            removeParsedFromMap(parsedRecords, d);
            iContext.onBeforeDocument(db, iRootRecord, d, key.toString(), iUserObject);
            final var userObject =
                iListener.fetchLinkedMapEntry(
                    iRootRecord, iUserObject, fieldName, key.toString(), d, iContext);
            processRecord(db,
                d,
                userObject,
                iFetchPlan,
                iCurrentLevel,
                iLevelFromRoot,
                iFieldDepthLevel,
                parsedRecords,
                iFieldPathFromRoot,
                iListener,
                iContext, getTypesFormat(settings.keepTypes)); // ""
            iContext.onAfterDocument(db, iRootRecord, d, key.toString(), iUserObject);
          } else {
            iListener.parseLinked(db, iRootRecord, d, iUserObject, key.toString(), iContext);
          }
        } else {
          iListener.parseLinked(db, iRootRecord, r, iUserObject, key.toString(), iContext);
        }

      } else {
        iListener.processStandardField(db,
            iRootRecord, o, key.toString(), iContext, iUserObject, "", null);
      }
    }
    iContext.onAfterMap(db, iRootRecord, fieldName, iUserObject);
  }

  private static void fetchArray(
      DatabaseSessionEmbedded db, final EntityImpl rootRecord,
      final Object iUserObject,
      final FetchPlan iFetchPlan,
      Object fieldValue,
      String fieldName,
      final int iCurrentLevel,
      final int iLevelFromRoot,
      final int iFieldDepthLevel,
      final Object2IntOpenHashMap<RID> parsedRecords,
      final String iFieldPathFromRoot,
      final FetchListener iListener,
      final FetchContext context,
      FormatSettings settings) {
    if (fieldValue instanceof EntityImpl[] linked) {
      context.onBeforeArray(db, rootRecord, fieldName, iUserObject, linked);
      for (final var entity : linked) {
        // GO RECURSIVELY
        final var fieldDepthLevel = parsedRecords.getInt(entity.getIdentity());
        if (!entity.getIdentity().isValidPosition()
            || (fieldDepthLevel > -1 && fieldDepthLevel == iLevelFromRoot)) {
          removeParsedFromMap(parsedRecords, entity);
          context.onBeforeDocument(db, rootRecord, entity, fieldName, iUserObject);
          final var userObject =
              iListener.fetchLinked(rootRecord, iUserObject, fieldName, entity, context);
          processRecord(db,
              entity,
              userObject,
              iFetchPlan,
              iCurrentLevel,
              iLevelFromRoot,
              iFieldDepthLevel,
              parsedRecords,
              iFieldPathFromRoot,
              iListener,
              context, getTypesFormat(settings.keepTypes)); // ""
          context.onAfterDocument(db, rootRecord, entity, fieldName, iUserObject);
        } else {
          iListener.parseLinkedCollectionValue(db,
              rootRecord, entity, iUserObject, fieldName, context);
        }
      }
      context.onAfterArray(db, rootRecord, fieldName, iUserObject);
    } else {
      iListener.processStandardField(db,
          rootRecord, fieldValue, fieldName, context, iUserObject, "", null);
    }
  }

  @SuppressWarnings("unchecked")
  private static void fetchCollection(
      DatabaseSessionEmbedded db, final EntityImpl iRootRecord,
      final Object iUserObject,
      final FetchPlan iFetchPlan,
      final Object fieldValue,
      final String fieldName,
      final int iCurrentLevel,
      final int iLevelFromRoot,
      final int iFieldDepthLevel,
      final Object2IntOpenHashMap<RID> parsedRecords,
      final String iFieldPathFromRoot,
      final FetchListener iListener,
      final FetchContext context,
      final FormatSettings settings)
      throws IOException {
    final Iterable<?> linked;
    if (fieldValue instanceof Iterable<?>) {
      linked = (Iterable<Identifiable>) fieldValue;
      context.onBeforeCollection(db, iRootRecord, fieldName, iUserObject, linked);
    } else if (fieldValue.getClass().isArray()) {
      linked = MultiValue.getMultiValueIterable(fieldValue);
      context.onBeforeCollection(db, iRootRecord, fieldName, iUserObject, linked);
    } else if (fieldValue instanceof Map<?, ?>) {
      linked = ((Map<?, ?>) fieldValue).values();
      context.onBeforeMap(db, iRootRecord, fieldName, iUserObject);
    } else {
      throw new IllegalStateException("Unrecognized type: " + fieldValue.getClass());
    }

    final var iter = linked.iterator();

    try {
      while (iter.hasNext()) {
        final var recordLazyMultiValue = iter.next();
        if (recordLazyMultiValue == null) {
          continue;
        }

        if (recordLazyMultiValue instanceof Identifiable identifiable) {
          // GO RECURSIVELY
          final var fieldDepthLevel = parsedRecords.getInt(identifiable.getIdentity());
          if (!identifiable.getIdentity().isPersistent()
              || (fieldDepthLevel > -1 && fieldDepthLevel == iLevelFromRoot)) {
            removeParsedFromMap(parsedRecords, identifiable);
            try {
              var transaction = db.getActiveTransaction();
              identifiable = transaction.load(identifiable);
              if (!(identifiable instanceof EntityImpl)) {
                iListener.processStandardField(db,
                    null, identifiable, fieldName, context, iUserObject, "", null);
              } else {
                context.onBeforeDocument(db,
                    iRootRecord, (EntityImpl) identifiable, fieldName, iUserObject);
                final var userObject =
                    iListener.fetchLinkedCollectionValue(
                        iRootRecord, iUserObject, fieldName, (EntityImpl) identifiable, context);
                processRecord(db,
                    (EntityImpl) identifiable,
                    userObject,
                    iFetchPlan,
                    iCurrentLevel,
                    iLevelFromRoot,
                    iFieldDepthLevel,
                    parsedRecords,
                    iFieldPathFromRoot,
                    iListener,
                    context, getTypesFormat(settings.keepTypes)); // ""
                context.onAfterDocument(db,
                    iRootRecord, (EntityImpl) identifiable, fieldName, iUserObject);
              }
            } catch (RecordNotFoundException rnf) {
              iListener.processStandardField(db,
                  null, identifiable, null, context, iUserObject, "", null);
            }
          } else {
            iListener.parseLinkedCollectionValue(db,
                iRootRecord, identifiable, iUserObject, fieldName, context);
          }
        } else if (recordLazyMultiValue instanceof Map<?, ?>) {
          fetchMap(db,
              iRootRecord,
              iUserObject,
              iFetchPlan,
              recordLazyMultiValue,
              null,
              iCurrentLevel + 1,
              iLevelFromRoot,
              iFieldDepthLevel,
              parsedRecords,
              iFieldPathFromRoot,
              iListener,
              context, settings);
        } else if (MultiValue.isMultiValue(recordLazyMultiValue)) {
          fetchCollection(db,
              iRootRecord,
              iUserObject,
              iFetchPlan,
              recordLazyMultiValue,
              null,
              iCurrentLevel + 1,
              iLevelFromRoot,
              iFieldDepthLevel,
              parsedRecords,
              iFieldPathFromRoot,
              iListener,
              context, settings);
        }
      }
    } finally {
      if (fieldValue instanceof Iterable<?>) {
        context.onAfterCollection(db, iRootRecord, fieldName, iUserObject);
      } else if (fieldValue.getClass().isArray()) {
        context.onAfterCollection(db, iRootRecord, fieldName, iUserObject);
      } else if (fieldValue instanceof Map<?, ?>) {
        context.onAfterMap(db, iRootRecord, fieldName, iUserObject);
      }
    }
  }

  private static void fetchEntity(
      DatabaseSessionEmbedded db, final EntityImpl iRootRecord,
      final Object iUserObject,
      final FetchPlan iFetchPlan,
      final Identifiable fieldValue,
      final String fieldName,
      final int iCurrentLevel,
      final int iLevelFromRoot,
      final int iFieldDepthLevel,
      final Object2IntOpenHashMap<RID> parsedRecords,
      final String iFieldPathFromRoot,
      final FetchListener iListener,
      final FetchContext iContext,
      final FormatSettings settings) {
    if (fieldValue instanceof RID && !((RecordIdInternal) fieldValue).isValidPosition()) {
      // RID NULL: TREAT AS "NULL" VALUE
      iContext.onBeforeStandardField(fieldValue, fieldName, iRootRecord, null);
      iListener.parseLinked(db, iRootRecord, fieldValue, iUserObject, fieldName, iContext);
      iContext.onAfterStandardField(fieldValue, fieldName, iRootRecord, null);
      return;
    }

    final var fieldDepthLevel = parsedRecords.getInt(fieldValue.getIdentity());
    if (!((RecordIdInternal) fieldValue.getIdentity()).isValidPosition()
        || (fieldDepthLevel > -1 && fieldDepthLevel == iLevelFromRoot)) {
      removeParsedFromMap(parsedRecords, fieldValue);
      var transaction = db.getActiveTransaction();
      final EntityImpl linked = transaction.load(fieldValue);

      iContext.onBeforeDocument(db, iRootRecord, linked, fieldName, iUserObject);
      var userObject =
          iListener.fetchLinked(iRootRecord, iUserObject, fieldName, linked, iContext);
      processRecord(db,
          linked,
          userObject,
          iFetchPlan,
          iCurrentLevel,
          iLevelFromRoot,
          iFieldDepthLevel,
          parsedRecords,
          iFieldPathFromRoot,
          iListener,
          iContext, getTypesFormat(settings.keepTypes)); // ""
      iContext.onAfterDocument(db, iRootRecord, linked, fieldName, iUserObject);
    } else {
      iContext.onBeforeStandardField(fieldValue, fieldName, iRootRecord, null);
      iListener.parseLinked(db, iRootRecord, fieldValue, iUserObject, fieldName, iContext);
      iContext.onAfterStandardField(fieldValue, fieldName, iRootRecord, null);
    }
  }

  private static String getTypesFormat(final boolean keepTypes) {
    final var sb = new StringBuilder();
    if (keepTypes) {
      sb.append("keepTypes");
    }
    return sb.toString();
  }

  protected static void removeParsedFromMap(
      final Object2IntOpenHashMap<RID> parsedRecords, Identifiable d) {
    parsedRecords.removeInt(d.getIdentity());
  }
}
