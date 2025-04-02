package com.jetbrains.youtrack.db.internal.client.remote.message.push;

import com.jetbrains.youtrack.db.internal.client.remote.StorageCollectionConfigurationRemote;
import com.jetbrains.youtrack.db.internal.core.config.StorageCollectionConfiguration;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrack.db.internal.core.config.StorageEntryConfiguration;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataInput;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelDataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

public class StorageConfigurationPayload {

  private String dateFormat;
  private String dateTimeFormat;
  private String name;
  private int version;
  private String directory;
  private List<StorageEntryConfiguration> properties;
  private RecordId schemaRecordId;
  private RecordId indexMgrRecordId;
  private String collectionSelection;
  private String conflictStrategy;
  private boolean validationEnabled;
  private String localeLanguage;
  private int minimumCollections;
  private boolean strictSql;
  private String charset;
  private TimeZone timeZone;
  private String localeCountry;
  private String recordSerializer;
  private int recordSerializerVersion;
  private int binaryFormatVersion;
  private List<StorageCollectionConfiguration> collections;

  public StorageConfigurationPayload(StorageConfiguration configuration) {
    this.dateFormat = configuration.getDateFormat();
    this.dateTimeFormat = configuration.getDateTimeFormat();
    this.name = configuration.getName();
    this.version = configuration.getVersion();
    this.directory = configuration.getDirectory();
    this.properties = configuration.getProperties();
    this.schemaRecordId = new RecordId(configuration.getSchemaRecordId());
    this.indexMgrRecordId = new RecordId(configuration.getIndexMgrRecordId());
    this.collectionSelection = configuration.getCollectionSelection();
    this.conflictStrategy = configuration.getConflictStrategy();
    this.validationEnabled = configuration.isValidationEnabled();
    this.localeLanguage = configuration.getLocaleLanguage();
    this.minimumCollections = configuration.getMinimumCollections();
    this.strictSql = configuration.isStrictSql();
    this.charset = configuration.getCharset();
    this.timeZone = configuration.getTimeZone();
    this.localeCountry = configuration.getLocaleCountry();
    this.recordSerializer = configuration.getRecordSerializer();
    this.recordSerializerVersion = configuration.getRecordSerializerVersion();
    this.binaryFormatVersion = configuration.getBinaryFormatVersion();
    this.collections = new ArrayList<>();
    for (var conf : configuration.getCollections()) {
      if (conf != null) {
        this.collections.add(conf);
      }
    }
  }

  public StorageConfigurationPayload() {
  }

  public void write(ChannelDataOutput channel) throws IOException {
    channel.writeString(this.dateFormat);
    channel.writeString(this.dateTimeFormat);
    channel.writeString(this.name);
    channel.writeInt(this.version);
    channel.writeString(this.directory);
    channel.writeInt(properties.size());
    for (var property : properties) {
      channel.writeString(property.name);
      channel.writeString(property.value);
    }
    channel.writeRID(this.schemaRecordId);
    channel.writeRID(this.indexMgrRecordId);
    channel.writeString(this.collectionSelection);
    channel.writeString(this.conflictStrategy);
    channel.writeBoolean(this.validationEnabled);
    channel.writeString(this.localeLanguage);
    channel.writeInt(this.minimumCollections);
    channel.writeBoolean(this.strictSql);
    channel.writeString(this.charset);
    channel.writeString(this.timeZone.getID());
    channel.writeString(this.localeCountry);
    channel.writeString(this.recordSerializer);
    channel.writeInt(this.recordSerializerVersion);
    channel.writeInt(this.binaryFormatVersion);
    channel.writeInt(collections.size());
    for (var collection : collections) {
      channel.writeInt(collection.getId());
      channel.writeString(collection.getName());
    }
  }

  public void read(ChannelDataInput network) throws IOException {
    this.dateFormat = network.readString();
    this.dateTimeFormat = network.readString();
    this.name = network.readString();
    this.version = network.readInt();
    this.directory = network.readString();
    var propSize = network.readInt();
    properties = new ArrayList<>(propSize);
    while (propSize-- > 0) {
      var name = network.readString();
      var value = network.readString();
      properties.add(new StorageEntryConfiguration(name, value));
    }
    this.schemaRecordId = network.readRID();
    this.indexMgrRecordId = network.readRID();
    this.collectionSelection = network.readString();
    this.conflictStrategy = network.readString();
    this.validationEnabled = network.readBoolean();
    this.localeLanguage = network.readString();
    this.minimumCollections = network.readInt();
    this.strictSql = network.readBoolean();
    this.charset = network.readString();
    this.timeZone = TimeZone.getTimeZone(network.readString());
    this.localeCountry = network.readString();
    this.recordSerializer = network.readString();
    this.recordSerializerVersion = network.readInt();
    this.binaryFormatVersion = network.readInt();
    var collectionsSize = network.readInt();
    collections = new ArrayList<>(collectionsSize);
    while (collectionsSize-- > 0) {
      var collectionId = network.readInt();
      var collectionName = network.readString();
      collections.add(new StorageCollectionConfigurationRemote(collectionId, collectionName));
    }
  }

  public String getDateFormat() {
    return dateFormat;
  }

  public String getDateTimeFormat() {
    return dateTimeFormat;
  }

  public String getName() {
    return name;
  }

  public int getVersion() {
    return version;
  }

  public String getDirectory() {
    return directory;
  }

  public List<StorageEntryConfiguration> getProperties() {
    return properties;
  }

  public RecordId getSchemaRecordId() {
    return schemaRecordId;
  }

  public RecordId getIndexMgrRecordId() {
    return indexMgrRecordId;
  }

  public String getCollectionSelection() {
    return collectionSelection;
  }

  public String getConflictStrategy() {
    return conflictStrategy;
  }

  public boolean isValidationEnabled() {
    return validationEnabled;
  }

  public String getLocaleLanguage() {
    return localeLanguage;
  }

  public int getMinimumCollections() {
    return minimumCollections;
  }

  public boolean isStrictSql() {
    return strictSql;
  }

  public String getCharset() {
    return charset;
  }

  public TimeZone getTimeZone() {
    return timeZone;
  }

  public String getLocaleCountry() {
    return localeCountry;
  }

  public String getRecordSerializer() {
    return recordSerializer;
  }

  public int getRecordSerializerVersion() {
    return recordSerializerVersion;
  }

  public int getBinaryFormatVersion() {
    return binaryFormatVersion;
  }

  public List<StorageCollectionConfiguration> getCollections() {
    return collections;
  }
}
