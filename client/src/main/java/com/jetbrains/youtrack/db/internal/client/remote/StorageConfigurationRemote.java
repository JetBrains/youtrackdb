package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.internal.client.remote.message.push.StorageConfigurationPayload;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.core.config.IndexEngineData;
import com.jetbrains.youtrack.db.internal.core.config.StorageClusterConfiguration;
import com.jetbrains.youtrack.db.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrack.db.internal.core.config.StorageEntryConfiguration;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

public class StorageConfigurationRemote implements StorageConfiguration {

  private ContextConfiguration contextConfiguration;

  private final String dateFormat;
  private final String dateTimeFormat;
  private final String name;
  private final int version;
  private final String directory;
  private final Map<String, StorageEntryConfiguration> properties;
  private final String schemaRecordId;
  private final String indexMgrRecordId;
  private final String clusterSelection;
  private final String conflictStrategy;
  private final boolean validationEnabled;
  private final String localeLanguage;
  private final int minimumClusters;
  private final boolean strictSql;
  private final String charset;
  private final TimeZone timeZone;
  private final String localeCountry;
  private final String recordSerializer;
  private final int recordSerializerVersion;
  private final int binaryFormatVersion;
  private final List<StorageClusterConfiguration> clusters;
  private final String networkRecordSerializer;

  public StorageConfigurationRemote(
      String networkRecordSerializer,
      StorageConfigurationPayload payload,
      ContextConfiguration contextConfiguration) {
    this.networkRecordSerializer = networkRecordSerializer;
    this.contextConfiguration = contextConfiguration;
    this.dateFormat = payload.getDateFormat();
    this.dateTimeFormat = payload.getDateTimeFormat();
    this.name = payload.getName();
    this.version = payload.getVersion();
    this.directory = payload.getDirectory();
    this.properties = new HashMap<>();
    for (var conf : payload.getProperties()) {
      this.properties.put(conf.name, conf);
    }
    this.schemaRecordId = payload.getSchemaRecordId().toString();
    this.indexMgrRecordId = payload.getIndexMgrRecordId().toString();
    this.clusterSelection = payload.getClusterSelection();
    this.conflictStrategy = payload.getConflictStrategy();
    this.validationEnabled = payload.isValidationEnabled();
    this.localeLanguage = payload.getLocaleLanguage();
    this.minimumClusters = payload.getMinimumClusters();
    this.strictSql = payload.isStrictSql();
    this.charset = payload.getCharset();
    this.timeZone = payload.getTimeZone();
    this.localeCountry = payload.getLocaleCountry();
    this.recordSerializer = payload.getRecordSerializer();
    this.recordSerializerVersion = payload.getRecordSerializerVersion();
    this.binaryFormatVersion = payload.getBinaryFormatVersion();
    this.clusters = payload.getClusters();
  }

  @Override
  public SimpleDateFormat getDateTimeFormatInstance() {
    return new SimpleDateFormat(dateTimeFormat);
  }

  @Override
  public SimpleDateFormat getDateFormatInstance() {
    return new SimpleDateFormat(dateFormat);
  }

  @Override
  public String getCharset() {
    return charset;
  }

  @Override
  public Locale getLocaleInstance() {
    return Locale.forLanguageTag(localeCountry);
  }

  @Override
  public String getSchemaRecordId() {
    return schemaRecordId;
  }

  @Override
  public int getMinimumClusters() {
    return minimumClusters;
  }

  @Override
  public boolean isStrictSql() {
    return strictSql;
  }

  public StorageConfiguration load(ContextConfiguration contextConfiguration) {
    this.contextConfiguration = contextConfiguration;
    return null;
  }

  @Override
  public String getIndexMgrRecordId() {
    return indexMgrRecordId;
  }

  @Override
  public TimeZone getTimeZone() {
    return timeZone;
  }

  @Override
  public String getDateFormat() {
    return dateFormat;
  }

  @Override
  public String getDateTimeFormat() {
    return dateTimeFormat;
  }

  @Override
  public ContextConfiguration getContextConfiguration() {
    return contextConfiguration;
  }

  @Override
  public String getLocaleCountry() {
    return localeCountry;
  }

  @Override
  public String getLocaleLanguage() {
    return localeLanguage;
  }

  @Override
  public List<StorageEntryConfiguration> getProperties() {
    return new ArrayList<>(properties.values());
  }

  @Override
  public String getClusterSelection() {
    return clusterSelection;
  }

  @Override
  public String getConflictStrategy() {
    return conflictStrategy;
  }

  @Override
  public boolean isValidationEnabled() {
    return validationEnabled;
  }

  @Override
  public IndexEngineData getIndexEngine(String name, int defaultIndexId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getRecordSerializer() {
    return networkRecordSerializer;
  }

  @Override
  public int getRecordSerializerVersion() {
    return recordSerializerVersion;
  }

  @Override
  public int getBinaryFormatVersion() {
    return binaryFormatVersion;
  }

  public void dropCluster(int iClusterId) {
    // this just remove it locally before a proper update from the push arrive
    if (clusters.size() > iClusterId) {
      clusters.set(iClusterId, null);
    }
  }

  @Override
  public int getVersion() {
    return version;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getProperty(String graphConsistencyMode) {
    return properties.get(graphConsistencyMode).value;
  }

  @Override
  public String getDirectory() {
    return directory;
  }

  @Override
  public List<StorageClusterConfiguration> getClusters() {
    return clusters;
  }

  @Override
  public String getCreatedAtVersion() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<String> indexEngines() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getPageSize() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getFreeListBoundary() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getMaxKeySize() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getUuid() {
    throw new UnsupportedOperationException(
        "Current version of the binary protocol do not support uuid");
  }

  @Override
  public void setUuid(AtomicOperation atomicOperation, String uuid) {
    throw new UnsupportedOperationException(
        "Current version of the binary protocol do not support uuid");
  }
}
