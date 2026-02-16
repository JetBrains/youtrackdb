package com.jetbrains.youtrackdb.internal.core.config;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import javax.annotation.Nullable;

public interface StorageConfiguration {

  String DEFAULT_CHARSET = "UTF-8";
  String DEFAULT_DATE_FORMAT = "yyyy-MM-dd";
  String DEFAULT_DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
  int CURRENT_VERSION = 23;
  int CURRENT_BINARY_FORMAT_VERSION = 14;

  SimpleDateFormat getDateTimeFormatInstance();

  SimpleDateFormat getDateFormatInstance();

  String getCharset();

  Locale getLocaleInstance();

  String getSchemaRecordId();

  int getMinimumCollections();

  String getIndexMgrRecordId();

  @Nullable
  TimeZone getTimeZone();

  String getDateFormat();

  String getDateTimeFormat();

  ContextConfiguration getContextConfiguration();

  String getLocaleCountry();

  String getLocaleLanguage();

  List<StorageEntryConfiguration> getProperties();

  String getCollectionSelection();

  String getConflictStrategy();

  boolean isValidationEnabled();

  @Nullable
  IndexEngineData getIndexEngine(String name, int defaultIndexId, AtomicOperation atomicOperation);

  String getRecordSerializer();

  int getRecordSerializerVersion();

  @SuppressWarnings("unused")
  int getBinaryFormatVersion(AtomicOperation atomicOperation);

  @SuppressWarnings("unused")
  int getVersion(AtomicOperation atomicOperation);

  @Nullable
  String getName();

  String getProperty(String graphConsistencyMode);

  @Nullable
  String getDirectory();

  List<StorageCollectionConfiguration> getCollections();

  String getCreatedAtVersion();

  Set<String> indexEngines(AtomicOperation atomicOperation);

  @SuppressWarnings("unused")
  int getPageSize(AtomicOperation atomicOperation);

  @SuppressWarnings("unused")
  int getFreeListBoundary(AtomicOperation atomicOperation);

  @SuppressWarnings("unused")
  int getMaxKeySize(AtomicOperation atomicOperation);

  void setUuid(AtomicOperation atomicOperation, final String uuid);

  String getUuid();
}
