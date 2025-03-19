package com.jetbrains.youtrack.db.internal.core.config;

import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

public interface StorageConfiguration {

  String DEFAULT_CHARSET = "UTF-8";
  String DEFAULT_DATE_FORMAT = "yyyy-MM-dd";
  String DEFAULT_DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
  int CURRENT_VERSION = 23;
  int CURRENT_BINARY_FORMAT_VERSION = 13;

  SimpleDateFormat getDateTimeFormatInstance();

  SimpleDateFormat getDateFormatInstance();

  String getCharset();

  Locale getLocaleInstance();

  String getSchemaRecordId();

  int getMinimumClusters();

  boolean isStrictSql();

  String getIndexMgrRecordId();

  TimeZone getTimeZone();

  String getDateFormat();

  String getDateTimeFormat();

  ContextConfiguration getContextConfiguration();

  String getLocaleCountry();

  String getLocaleLanguage();

  List<StorageEntryConfiguration> getProperties();

  String getClusterSelection();

  String getConflictStrategy();

  boolean isValidationEnabled();

  IndexEngineData getIndexEngine(String name, int defaultIndexId);

  String getRecordSerializer();

  int getRecordSerializerVersion();

  int getBinaryFormatVersion();

  int getVersion();

  String getName();

  String getProperty(String graphConsistencyMode);

  String getDirectory();

  List<StorageClusterConfiguration> getClusters();

  String getCreatedAtVersion();

  Set<String> indexEngines();

  int getPageSize();

  int getFreeListBoundary();

  int getMaxKeySize();

  void setUuid(AtomicOperation atomicOperation, final String uuid);

  String getUuid();
}
