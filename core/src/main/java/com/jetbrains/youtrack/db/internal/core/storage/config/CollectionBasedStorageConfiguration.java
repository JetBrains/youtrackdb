package com.jetbrains.youtrack.db.internal.core.storage.config;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.common.util.RawPairObjectInteger;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.core.config.IndexEngineData;
import com.jetbrains.youtrack.db.internal.core.config.StorageCollectionConfiguration;
import com.jetbrains.youtrack.db.internal.core.config.StorageConfiguration;
import com.jetbrains.youtrack.db.internal.core.config.StorageConfigurationUpdateListener;
import com.jetbrains.youtrack.db.internal.core.config.StorageEntryConfiguration;
import com.jetbrains.youtrack.db.internal.core.config.StorageFileConfiguration;
import com.jetbrains.youtrack.db.internal.core.config.StoragePaginatedCollectionConfiguration;
import com.jetbrains.youtrack.db.internal.core.config.StorageSegmentConfiguration;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.exception.StorageException;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.storage.PhysicalPosition;
import com.jetbrains.youtrack.db.internal.core.storage.RawBuffer;
import com.jetbrains.youtrack.db.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrack.db.internal.core.storage.collection.PaginatedCollection;
import com.jetbrains.youtrack.db.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.StorageCollectionFactory;
import com.jetbrains.youtrack.db.internal.core.storage.index.sbtree.singlevalue.v3.BTree;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public final class CollectionBasedStorageConfiguration implements StorageConfiguration {

  public static final String MAP_FILE_EXTENSION = ".ccm";
  public static final String FREE_MAP_FILE_EXTENSION = ".fcm";
  public static final String DATA_FILE_EXTENSION = ".cd";

  public static final String TREE_DATA_FILE_EXTENSION = ".bd";
  public static final String TREE_NULL_FILE_EXTENSION = ".nd";

  public static final String COMPONENT_NAME = "config";
  private static final String VERSION_PROPERTY = "version";
  private static final String SCHEMA_RECORD_ID_PROPERTY = "schemaRecordId";
  private static final String INDEX_MANAGER_RECORD_ID_PROPERTY = "indexManagerRecordId";

  private static final String LOCALE_LANGUAGE_PROPERTY = "localeLanguage";
  private static final String LOCALE_COUNTRY_PROPERTY = "localeCountry";
  private static final String LOCALE_PROPERTY_INSTANCE = "localeInstance";

  private static final String DATE_FORMAT_PROPERTY = "dateFormat";
  private static final String DATE_TIME_FORMAT_PROPERTY = "dateTimeFormat";

  private static final String TIME_ZONE_PROPERTY = "timeZone";
  private static final String CHARSET_PROPERTY = "charset";
  private static final String CONFLICT_STRATEGY_PROPERTY = "conflictStrategy";
  private static final String BINARY_FORMAT_VERSION_PROPERTY = "binaryFormatVersion";
  private static final String COLLECTION_SELECTION_PROPERTY = "collectionSelection";
  private static final String MINIMUM_COLLECTIONS_PROPERTY = "minimumCollections";
  private static final String RECORD_SERIALIZER_PROPERTY = "recordSerializer";
  private static final String RECORD_SERIALIZER_VERSION_PROPERTY = "recordSerializerVersion";
  private static final String CONFIGURATION_PROPERTY = "configuration";
  private static final int CONFIGURATION_PROPERTY_VERSION = 0;
  private static final String CREATED_AT_VERSION_PROPERTY = "createAtVersion";
  private static final String PAGE_SIZE_PROPERTY = "pageSize";
  private static final String FREE_LIST_BOUNDARY_PROPERTY = "freeListBoundary";
  private static final String MAX_KEY_SIZE_PROPERTY = "maxKeySize";

  private static final String COLLECTIONS_PREFIX_PROPERTY = "collection_";
  private static final int COLLECTIONS_PROPERTY_VERSION = 0;
  private static final String PROPERTY_PREFIX_PROPERTY = "property_";

  private static final String ENGINE_PREFIX_PROPERTY = "engine_";
  private static final int INDEX_ENGINE_PROPERTY_VERSION = 1;

  private static final String PROPERTIES = "properties";
  private static final String COLLECTIONS = "collections";
  private static final String UUID = "UUID";

  private static final String[] INT_PROPERTIES =
      new String[]{
          MINIMUM_COLLECTIONS_PROPERTY,
          VERSION_PROPERTY,
          BINARY_FORMAT_VERSION_PROPERTY,
          RECORD_SERIALIZER_VERSION_PROPERTY,
          PAGE_SIZE_PROPERTY,
          FREE_LIST_BOUNDARY_PROPERTY,
          MAX_KEY_SIZE_PROPERTY
      };

  private static final String[] STRING_PROPERTIES =
      new String[]{
          SCHEMA_RECORD_ID_PROPERTY,
          INDEX_MANAGER_RECORD_ID_PROPERTY,
          LOCALE_LANGUAGE_PROPERTY,
          LOCALE_COUNTRY_PROPERTY,
          DATE_FORMAT_PROPERTY,
          DATE_TIME_FORMAT_PROPERTY,
          TIME_ZONE_PROPERTY,
          CHARSET_PROPERTY,
          CONFLICT_STRATEGY_PROPERTY,
          COLLECTION_SELECTION_PROPERTY,
          RECORD_SERIALIZER_PROPERTY,
          CREATED_AT_VERSION_PROPERTY,
          UUID
      };

  private ContextConfiguration configuration;
  private boolean validation;

  private final BTree<String> btree;
  private final PaginatedCollection collection;

  private final AbstractStorage storage;
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  private final HashMap<String, Object> cache = new HashMap<>();

  private StorageConfigurationUpdateListener updateListener;

  private final ThreadLocal<PausedNotificationsState> pauseNotifications =
      ThreadLocal.withInitial(PausedNotificationsState::new);

  public static boolean exists(final WriteCache writeCache) {
    return writeCache.exists(COMPONENT_NAME + DATA_FILE_EXTENSION);
  }

  public CollectionBasedStorageConfiguration(final AbstractStorage storage) {
    collection =
        StorageCollectionFactory.createCollection(
            COMPONENT_NAME,
            PaginatedCollection.getLatestBinaryVersion(),
            storage,
            DATA_FILE_EXTENSION,
            MAP_FILE_EXTENSION,
            FREE_MAP_FILE_EXTENSION);
    btree =
        new BTree<>(COMPONENT_NAME, TREE_DATA_FILE_EXTENSION, TREE_NULL_FILE_EXTENSION, storage,
            null);
    this.storage = storage;
  }

  public void create(
      final AtomicOperation atomicOperation, final ContextConfiguration contextConfiguration)
      throws IOException {
    lock.writeLock().lock();
    try {
      collection.create(atomicOperation);
      btree.create(atomicOperation, StringSerializer.INSTANCE, null, 1);

      this.configuration = contextConfiguration;

      init(atomicOperation);

      preloadIntProperties();
      preloadStringProperties();
      preloadCollections();
      preloadConfigurationProperties();
      setValidation(
          atomicOperation,
          getContextConfiguration().getValueAsBoolean(GlobalConfiguration.DB_VALIDATION));
      recalculateLocale();
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void create(
      final AtomicOperation atomicOperation,
      final ContextConfiguration contextConfiguration,
      final StorageConfiguration source)
      throws IOException {
    lock.writeLock().lock();
    try {
      create(atomicOperation, contextConfiguration);
      copy(atomicOperation, source);
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void delete(AtomicOperation atomicOperation) throws IOException {
    lock.writeLock().lock();
    try {
      updateListener = null;

      final var firstPosition = collection.getFirstPosition();
      var positions =
          collection.ceilingPositions(new PhysicalPosition(firstPosition), Integer.MAX_VALUE);
      while (positions.length > 0) {
        for (var position : positions) {
          collection.deleteRecord(atomicOperation, position.collectionPosition);
        }

        positions = collection.higherPositions(positions[positions.length - 1], Integer.MAX_VALUE);
      }

      collection.delete(atomicOperation);

      try (var keyStream = btree.keyStream()) {
        keyStream.forEach((key) -> btree.remove(atomicOperation, key));
      }

      btree.delete(atomicOperation);

      cache.clear();
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void close(final AtomicOperation atomicOperation) {
    lock.writeLock().lock();
    try {
      updateListener = null;

      updateConfigurationProperty(atomicOperation);
      updateMinimumCollections(atomicOperation);

      cache.clear();

      // tree and collection will be closed by storage automatically
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void load(
      final ContextConfiguration configuration, final AtomicOperation atomicOperation)
      throws SerializationException, IOException {
    lock.writeLock().lock();
    try {
      this.configuration = configuration;

      collection.open(atomicOperation);
      btree.load(COMPONENT_NAME, 1, null, StringSerializer.INSTANCE);

      readConfiguration();
      readMinimumCollections();

      preloadIntProperties();
      preloadStringProperties();
      preloadConfigurationProperties();
      preloadCollections();
      recalculateLocale();

      validation = "true".equalsIgnoreCase(getProperty("validation"));
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void pauseUpdateNotifications() {
    lock.writeLock().lock();
    try {
      final var pausedNotificationsState = pauseNotifications.get();
      pausedNotificationsState.notificationsPaused = true;
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void fireUpdateNotifications() {
    lock.writeLock().lock();
    try {
      final var pausedNotificationsState = pauseNotifications.get();

      if (pausedNotificationsState.pendingChanges > 0 && updateListener != null) {
        updateListener.onUpdate(this);
        pausedNotificationsState.pendingChanges = 0;
      }

      pausedNotificationsState.notificationsPaused = false;
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void setMinimumCollections(final int minimumCollections) {
    lock.writeLock().lock();
    try {
      getContextConfiguration()
          .setValue(GlobalConfiguration.CLASS_COLLECTIONS_COUNT, minimumCollections);
      autoInitCollections();
    } finally {
      lock.writeLock().unlock();
    }
  }

  private void updateMinimumCollections(AtomicOperation atomicOperation) {
    updateIntProperty(atomicOperation, MINIMUM_COLLECTIONS_PROPERTY, getMinimumCollections());
  }

  private void readMinimumCollections() {
    if (containsProperty(MINIMUM_COLLECTIONS_PROPERTY)) {
      setMinimumCollections(readIntProperty(MINIMUM_COLLECTIONS_PROPERTY, false));
    }
  }

  @Override
  public int getMinimumCollections() {
    lock.readLock().lock();
    try {
      final var mc =
          getContextConfiguration().getValueAsInteger(GlobalConfiguration.CLASS_COLLECTIONS_COUNT);
      if (mc == 0) {
        autoInitCollections();
        return (Integer)
            getContextConfiguration().getValue(GlobalConfiguration.CLASS_COLLECTIONS_COUNT);
      }
      return mc;
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public ContextConfiguration getContextConfiguration() {
    lock.readLock().lock();
    try {
      return configuration;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Added version used for managed Network Versioning.
   */
  public byte[] toStream(final int iNetworkVersion, final Charset charset)
      throws SerializationException {
    lock.readLock().lock();
    try {
      final var buffer = new StringBuilder(8192);

      write(buffer, CURRENT_VERSION);
      write(buffer, null);

      write(buffer, getSchemaRecordId());
      write(buffer, "");
      write(buffer, getIndexMgrRecordId());

      write(buffer, getLocaleLanguage());
      write(buffer, getLocaleCountry());
      write(buffer, getDateFormat());
      write(buffer, getDateFormat());

      final var timeZone = getTimeZone();
      assert timeZone != null;

      write(buffer, timeZone);
      write(buffer, charset);
      if (iNetworkVersion > 24) {
        write(buffer, getConflictStrategy());
      }

      phySegmentToStream(buffer, new StorageSegmentConfiguration());

      final var collections = getCollections();
      write(buffer, collections.size());
      for (final var c : collections) {
        if (c == null) {
          write(buffer, -1);
          continue;
        }

        write(buffer, c.getId());
        write(buffer, c.getName());
        write(buffer, c.getDataSegmentId());

        if (c instanceof StoragePaginatedCollectionConfiguration paginatedCollectionConfiguration) {
          write(buffer, "d");

          write(buffer, paginatedCollectionConfiguration.useWal);
          write(buffer, paginatedCollectionConfiguration.recordOverflowGrowFactor);
          write(buffer, paginatedCollectionConfiguration.recordGrowFactor);
          write(buffer, paginatedCollectionConfiguration.compression);

          if (iNetworkVersion >= 31) {
            write(buffer, paginatedCollectionConfiguration.encryption);
          }
          if (iNetworkVersion > 24) {
            write(buffer, paginatedCollectionConfiguration.conflictStrategy);
          }

          if (iNetworkVersion >= Integer.MAX_VALUE) {
            write(buffer, paginatedCollectionConfiguration.getBinaryVersion());
          }
        }
      }
      if (iNetworkVersion <= 25) {
        // dataSegment array
        write(buffer, 0);
        // tx Segment File
        write(buffer, "");
        write(buffer, "");
        write(buffer, 0);
        // tx segment flags
        write(buffer, false);
        write(buffer, false);
      }

      final var properties = getProperties();
      write(buffer, properties.size());
      for (final var e : properties) {
        entryToStream(buffer, e);
      }

      write(buffer, getBinaryFormatVersion());
      write(buffer, getCollectionSelection());
      write(buffer, getMinimumCollections());

      if (iNetworkVersion > 24) {
        write(buffer, getRecordSerializer());
        write(buffer, getRecordSerializerVersion());

        // WRITE CONFIGURATION
        write(buffer, configuration.getContextSize());
        for (final var k : configuration.getContextKeys()) {
          final var cfg = GlobalConfiguration.findByKey(k);
          write(buffer, k);
          if (cfg != null) {
            write(buffer, cfg.isHidden() ? null : configuration.getValueAsString(cfg));
          } else {
            write(buffer, null);
            LogManager.instance()
                .warn(
                    this,
                    "Storing configuration for property:'"
                        + k
                        + "' not existing in current version");
          }
        }
      }

      final var engines = loadIndexEngines();
      write(buffer, engines.size());
      for (final var engineData : engines) {
        write(buffer, engineData.getName());
        write(buffer, engineData.getAlgorithm());
        write(buffer, engineData.getIndexType() == null ? "" : engineData.getIndexType());

        write(buffer, engineData.getValueSerializerId());
        write(buffer, engineData.getKeySerializedId());

        write(buffer, engineData.isAutomatic());
        write(buffer, engineData.getDurableInNonTxMode());

        write(buffer, engineData.getVersion());
        write(buffer, engineData.isNullValuesSupport());
        write(buffer, engineData.getKeySize());
        write(buffer, engineData.getEncryption());
        write(buffer, engineData.getEncryptionOptions());

        if (engineData.getKeyTypes() != null) {
          write(buffer, engineData.getKeyTypes().length);
          for (final var type : engineData.getKeyTypes()) {
            write(buffer, type.name());
          }
        } else {
          write(buffer, 0);
        }

        if (engineData.getEngineProperties() == null) {
          write(buffer, 0);
        } else {
          write(buffer, engineData.getEngineProperties().size());
          for (final var property :
              engineData.getEngineProperties().entrySet()) {
            write(buffer, property.getKey());
            write(buffer, property.getValue());
          }
        }

        write(buffer, engineData.getApiVersion());
        write(buffer, engineData.isMultivalue());
      }

      write(buffer, getCreatedAtVersion());
      write(buffer, getPageSize());
      write(buffer, getFreeListBoundary());
      write(buffer, getMaxKeySize());

      // PLAIN: ALLOCATE ENOUGH SPACE TO REUSE IT EVERY TIME
      buffer.append("|");

      return buffer.toString().getBytes(charset);
    } finally {
      lock.readLock().unlock();
    }
  }

  private static void entryToStream(
      final StringBuilder buffer, final StorageEntryConfiguration entry) {
    write(buffer, entry.name);
    write(buffer, entry.value);
  }

  private static void phySegmentToStream(
      final StringBuilder buffer, final StorageSegmentConfiguration segment) {
    write(buffer, segment.getLocation());
    write(buffer, segment.maxSize);
    write(buffer, segment.fileType);
    write(buffer, segment.fileStartSize);
    write(buffer, segment.fileMaxSize);
    write(buffer, segment.fileIncrementSize);
    write(buffer, segment.defrag);

    write(buffer, segment.infoFiles.length);
    for (final var f : segment.infoFiles) {
      fileToStream(buffer, f);
    }
  }

  private static void fileToStream(
      final StringBuilder iBuffer, final StorageFileConfiguration iFile) {
    write(iBuffer, iFile.path);
    write(iBuffer, iFile.type);
    write(iBuffer, iFile.maxSize);
  }

  private static void write(final StringBuilder buffer, final Object value) {
    if (!buffer.isEmpty()) {
      buffer.append('|');
    }

    buffer.append(value != null ? value.toString() : ' ');
  }

  private void updateVersion(final AtomicOperation atomicOperation) {
    updateIntProperty(atomicOperation, VERSION_PROPERTY, CURRENT_VERSION);
  }

  @Override
  public int getVersion() {
    lock.readLock().lock();
    try {
      return readIntProperty(VERSION_PROPERTY, true);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  @Nullable
  public String getName() {
    return null;
  }

  public void setSchemaRecordId(AtomicOperation atomicOperation, final String schemaRecordId) {
    lock.writeLock().lock();
    try {
      updateStringProperty(atomicOperation, SCHEMA_RECORD_ID_PROPERTY, schemaRecordId, true);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public String getSchemaRecordId() {
    lock.readLock().lock();
    try {
      return readStringProperty(SCHEMA_RECORD_ID_PROPERTY);
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setIndexMgrRecordId(AtomicOperation atomicOperation, final String indexMgrRecordId) {
    lock.writeLock().lock();
    try {
      updateStringProperty(
          atomicOperation, INDEX_MANAGER_RECORD_ID_PROPERTY, indexMgrRecordId, true);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public String getIndexMgrRecordId() {
    lock.readLock().lock();
    try {
      return readStringProperty(INDEX_MANAGER_RECORD_ID_PROPERTY);
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setLocaleLanguage(final AtomicOperation atomicOperation, final String value) {
    lock.writeLock().lock();
    try {
      updateStringProperty(atomicOperation, LOCALE_LANGUAGE_PROPERTY, value, true);

      recalculateLocale();
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public String getLocaleLanguage() {
    lock.readLock().lock();
    try {
      return readStringProperty(LOCALE_LANGUAGE_PROPERTY);
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setLocaleCountry(AtomicOperation atomicOperation, final String value) {
    lock.writeLock().lock();
    try {
      updateStringProperty(atomicOperation, LOCALE_COUNTRY_PROPERTY, value, true);

      recalculateLocale();
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public String getLocaleCountry() {
    lock.readLock().lock();
    try {
      return readStringProperty(LOCALE_COUNTRY_PROPERTY);
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setDateFormat(final AtomicOperation atomicOperation, final String dateFormat) {
    lock.writeLock().lock();
    try {
      updateStringProperty(atomicOperation, DATE_FORMAT_PROPERTY, dateFormat, true);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public String getDateFormat() {
    lock.readLock().lock();
    try {
      final var dateFormat = readStringProperty(DATE_FORMAT_PROPERTY);
      assert dateFormat != null;

      return dateFormat;
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public SimpleDateFormat getDateFormatInstance() {
    lock.readLock().lock();
    try {
      final var dateFormatInstance = new SimpleDateFormat(getDateFormat());
      dateFormatInstance.setLenient(false);
      final var timeZone = getTimeZone();
      if (timeZone != null) {
        dateFormatInstance.setTimeZone(timeZone);
      }

      return dateFormatInstance;
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public String getDateTimeFormat() {
    lock.readLock().lock();
    try {
      final var dateTimeFormat = readStringProperty(DATE_TIME_FORMAT_PROPERTY);
      assert dateTimeFormat != null;

      return dateTimeFormat;
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setDateTimeFormat(
      final AtomicOperation atomicOperation, final String dateTimeFormat) {
    lock.writeLock().lock();
    try {
      updateStringProperty(atomicOperation, DATE_TIME_FORMAT_PROPERTY, dateTimeFormat, true);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public void setUuid(AtomicOperation atomicOperation, final String uuid) {
    lock.writeLock().lock();
    try {
      updateStringProperty(atomicOperation, UUID, uuid, true);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public String getUuid() {
    lock.readLock().lock();
    try {
      return readStringProperty(UUID);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public SimpleDateFormat getDateTimeFormatInstance() {
    lock.readLock().lock();
    try {
      final var dateTimeFormatInstance = new SimpleDateFormat(getDateTimeFormat());
      dateTimeFormatInstance.setLenient(false);
      final var timeZone = getTimeZone();
      if (timeZone != null) {
        dateTimeFormatInstance.setTimeZone(timeZone);
      }

      return dateTimeFormatInstance;
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setTimeZone(final AtomicOperation atomicOperation, final TimeZone timeZone) {
    lock.writeLock().lock();
    try {
      updateStringProperty(atomicOperation, TIME_ZONE_PROPERTY, timeZone.getID(), true);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  @Nullable
  public TimeZone getTimeZone() {
    lock.readLock().lock();
    try {
      final var timeZone = readStringProperty(TIME_ZONE_PROPERTY);
      if (timeZone == null) {
        return null;
      }

      return TimeZone.getTimeZone(timeZone);
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setCharset(final AtomicOperation atomicOperation, final String charset) {
    lock.writeLock().lock();
    try {
      updateStringProperty(atomicOperation, CHARSET_PROPERTY, charset, true);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public String getCharset() {
    lock.readLock().lock();
    try {
      return readStringProperty(CHARSET_PROPERTY);
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setConflictStrategy(AtomicOperation atomicOperation, final String conflictStrategy) {
    lock.writeLock().lock();
    try {
      updateStringProperty(atomicOperation, CONFLICT_STRATEGY_PROPERTY, conflictStrategy, true);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public String getConflictStrategy() {
    lock.readLock().lock();
    try {
      return readStringProperty(CONFLICT_STRATEGY_PROPERTY);
    } finally {
      lock.readLock().unlock();
    }
  }

  private void updateBinaryFormatVersion(final AtomicOperation atomicOperation) {
    updateIntProperty(
        atomicOperation, BINARY_FORMAT_VERSION_PROPERTY, CURRENT_BINARY_FORMAT_VERSION);
  }

  @Override
  public int getBinaryFormatVersion() {
    lock.readLock().lock();
    try {
      return readIntProperty(BINARY_FORMAT_VERSION_PROPERTY, true);
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setCollectionSelection(
      final AtomicOperation atomicOperation, final String collectionSelection) {
    lock.writeLock().lock();
    try {
      updateStringProperty(atomicOperation, COLLECTION_SELECTION_PROPERTY, collectionSelection, true);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public String getCollectionSelection() {
    lock.readLock().lock();
    try {
      return readStringProperty(COLLECTION_SELECTION_PROPERTY);
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setRecordSerializer(
      final AtomicOperation atomicOperation, final String recordSerializer) {
    lock.writeLock().lock();
    try {
      updateStringProperty(atomicOperation, RECORD_SERIALIZER_PROPERTY, recordSerializer, true);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public String getRecordSerializer() {
    lock.readLock().lock();
    try {
      return readStringProperty(RECORD_SERIALIZER_PROPERTY);
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setRecordSerializerVersion(
      final AtomicOperation atomicOperation, final int recordSerializerVersion) {
    lock.writeLock().lock();
    try {
      updateIntProperty(
          atomicOperation, RECORD_SERIALIZER_VERSION_PROPERTY, recordSerializerVersion);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public int getRecordSerializerVersion() {
    lock.readLock().lock();
    try {
      return readIntProperty(RECORD_SERIALIZER_VERSION_PROPERTY, true);
    } finally {
      lock.readLock().unlock();
    }
  }

  private void updateConfigurationProperty(AtomicOperation atomicOperation) {
    final List<byte[]> entries = new ArrayList<>(8);
    var totalSize = 0;

    final var contextSize = new byte[IntegerSerializer.INT_SIZE];
    totalSize += contextSize.length;
    entries.add(contextSize);

    IntegerSerializer.serializeNative(configuration.getContextSize(), contextSize, 0);

    for (final var k : configuration.getContextKeys()) {
      final var cfg = GlobalConfiguration.findByKey(k);
      final var key = serializeStringValue(k);
      totalSize += key.length;
      entries.add(key);

      if (cfg != null) {
        final var value =
            serializeStringValue(cfg.isHidden() ? null : configuration.getValueAsString(cfg));
        totalSize += value.length;
        entries.add(value);
      } else {
        final var value = serializeStringValue(null);
        totalSize += value.length;
        entries.add(value);

        LogManager.instance()
            .warn(
                this,
                "Storing configuration for property:'" + k + "' not existing in current version");
      }
    }

    final var property = mergeBinaryEntries(totalSize, entries);
    storeProperty(
        atomicOperation, CONFIGURATION_PROPERTY, property, CONFIGURATION_PROPERTY_VERSION);
  }

  private void readConfiguration() {
    final var pair = readProperty(CONFIGURATION_PROPERTY);
    if (pair == null) {
      return;
    }

    final var property = pair.first;

    var pos = 0;
    final var size = IntegerSerializer.deserializeNative(property, pos);
    pos += IntegerSerializer.INT_SIZE;

    for (var i = 0; i < size; i++) {
      final var key = deserializeStringValue(property, pos);
      pos += getSerializedStringSize(property, pos);

      final var value = deserializeStringValue(property, pos);
      pos += getSerializedStringSize(property, pos);

      final var cfg = GlobalConfiguration.findByKey(key);
      if (cfg != null) {
        if (value != null) {
          configuration.setValue(key, PropertyTypeInternal.convert(null, value, cfg.getType()));
        }
      } else {
        LogManager.instance()
            .warn(this, "Ignored storage configuration because not supported: %s=%s", key, value);
      }
    }
  }

  public void setCreationVersion(final AtomicOperation atomicOperation, final String version) {
    lock.writeLock().lock();
    try {
      updateStringProperty(atomicOperation, CREATED_AT_VERSION_PROPERTY, version, true);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public String getCreatedAtVersion() {
    lock.readLock().lock();
    try {
      return readStringProperty(CREATED_AT_VERSION_PROPERTY);
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setPageSize(final AtomicOperation atomicOperation, final int pageSize) {
    lock.writeLock().lock();
    try {
      updateIntProperty(atomicOperation, PAGE_SIZE_PROPERTY, pageSize);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public int getPageSize() {
    lock.readLock().lock();
    try {
      return readIntProperty(PAGE_SIZE_PROPERTY, true);
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setFreeListBoundary(
      final AtomicOperation atomicOperation, final int freeListBoundary) {
    lock.writeLock().lock();
    try {
      updateIntProperty(atomicOperation, FREE_LIST_BOUNDARY_PROPERTY, freeListBoundary);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public int getFreeListBoundary() {
    lock.readLock().lock();
    try {
      return readIntProperty(FREE_LIST_BOUNDARY_PROPERTY, true);
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setMaxKeySize(final AtomicOperation atomicOperation, final int maxKeySize) {
    lock.writeLock().lock();
    try {
      updateIntProperty(atomicOperation, MAX_KEY_SIZE_PROPERTY, maxKeySize);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public int getMaxKeySize() {
    lock.readLock().lock();
    try {
      return readIntProperty(MAX_KEY_SIZE_PROPERTY, true);
    } finally {
      lock.readLock().unlock();
    }
  }

  public void setProperty(
      final AtomicOperation atomicOperation, final String name, final String value) {
    lock.writeLock().lock();
    try {
      if ("validation".equalsIgnoreCase(name)) {
        validation = "true".equalsIgnoreCase(value);
      }

      final var key = PROPERTY_PREFIX_PROPERTY + name;
      updateStringProperty(atomicOperation, key, value, false);

      @SuppressWarnings("unchecked") final var properties = (Map<String, String>) cache.get(
          PROPERTIES);
      properties.put(name, value);
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void setValidation(final AtomicOperation atomicOperation, final boolean validation) {
    setProperty(atomicOperation, "validation", validation ? "true" : "false");
  }

  @Override
  public boolean isValidationEnabled() {
    lock.readLock().lock();
    try {
      return validation;
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  @Nullable
  public String getDirectory() {
    if (storage instanceof DiskStorage) {
      return ((DiskStorage) storage).getStoragePath().toString();
    } else {
      return null;
    }
  }

  @Override
  public String getProperty(final String name) {
    lock.readLock().lock();
    try {
      @SuppressWarnings("unchecked") final var properties = (Map<String, String>) cache.get(
          PROPERTIES);
      return properties.get(name);
    } finally {
      lock.readLock().unlock();
    }
  }

  @Override
  public List<StorageEntryConfiguration> getProperties() {
    lock.readLock().lock();
    try {
      @SuppressWarnings("unchecked") final var properties = (Map<String, String>) cache.get(
          PROPERTIES);

      final List<StorageEntryConfiguration> result = new ArrayList<>(8);

      for (final var entry : properties.entrySet()) {
        result.add(new StorageEntryConfiguration(entry.getKey(), entry.getValue()));
      }

      return result;
    } finally {
      lock.readLock().unlock();
    }
  }

  private void preloadConfigurationProperties() {
    final Map<String, String> properties;
    try (var stream =
        btree.iterateEntriesMajor(PROPERTY_PREFIX_PROPERTY, false, true)) {
      properties =
          stream
              .filter((pair) -> pair.first().startsWith(PROPERTY_PREFIX_PROPERTY))
              .map(
                  (entry) -> {
                    final RawBuffer buffer;
                    try {
                      buffer = collection.readRecord(entry.second().getCollectionPosition());
                      return new RawPair<>(
                          entry.first().substring(PROPERTY_PREFIX_PROPERTY.length()),
                          deserializeStringValue(buffer.buffer, 0));
                    } catch (IOException e) {
                      throw BaseException.wrapException(
                          new StorageException(storage.getName(),
                              "Can not preload configuration properties"), e, storage.getName());
                    }
                  })
              .collect(Collectors.toMap(RawPair::first, RawPair::second));
    }

    cache.put(PROPERTIES, properties);
  }

  @Override
  public Locale getLocaleInstance() {
    lock.readLock().lock();
    try {
      var locale = (Locale) cache.get(LOCALE_PROPERTY_INSTANCE);
      if (locale == null) {
        locale = Locale.getDefault();
      }
      return locale;
    } finally {
      lock.readLock().unlock();
    }
  }

  private void recalculateLocale() {
    Locale locale;
    try {
      final var localeLanguage = getLocaleLanguage();
      final var localeCountry = getLocaleCountry();

      if (localeLanguage == null || localeCountry == null) {
        locale = Locale.getDefault();
      } else {
        locale = new Locale(getLocaleLanguage(), getLocaleCountry());
      }
    } catch (final RuntimeException e) {
      locale = Locale.getDefault();
    }

    cache.put(LOCALE_PROPERTY_INSTANCE, locale);
  }

  @Override
  public boolean isStrictSql() {
    return true;
  }

  public void clearProperties(AtomicOperation atomicOperation) {
    lock.writeLock().lock();
    try {
      final List<String> keysToRemove;
      final List<RID> ridsToRemove;
      try (var stream =
          btree.iterateEntriesMajor(PROPERTY_PREFIX_PROPERTY, false, true)) {

        keysToRemove = new ArrayList<>(8);
        ridsToRemove = new ArrayList<>(8);

        stream
            .filter((entry) -> entry.first().startsWith(PROPERTY_PREFIX_PROPERTY))
            .forEach(
                (entry) -> {
                  keysToRemove.add(entry.first());
                  ridsToRemove.add(entry.second());
                });
      }

      for (final var key : keysToRemove) {
        btree.remove(atomicOperation, key);
      }

      for (final var rid : ridsToRemove) {
        collection.deleteRecord(atomicOperation, rid.getCollectionPosition());
      }

      @SuppressWarnings("unchecked") final var properties = (Map<String, String>) cache.get(
          PROPERTIES);
      properties.clear();
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void removeProperty(AtomicOperation atomicOperation, final String name) {
    lock.writeLock().lock();
    try {
      dropProperty(atomicOperation, PROPERTY_PREFIX_PROPERTY + name);
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void addIndexEngine(
      final AtomicOperation atomicOperation, final String name, final IndexEngineData engineData) {
    lock.writeLock().lock();
    try {
      final var identifiable = btree.get(ENGINE_PREFIX_PROPERTY + name);
      if (identifiable != null) {
        LogManager.instance()
            .warn(
                this,
                "Index engine with name '"
                    + engineData.getName()
                    + "' already contained in database configuration");
      } else {
        storeProperty(
            atomicOperation,
            ENGINE_PREFIX_PROPERTY + name,
            serializeIndexEngineProperty(engineData),
            INDEX_ENGINE_PROPERTY_VERSION);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void deleteIndexEngine(AtomicOperation atomicOperation, final String name) {
    lock.writeLock().lock();
    try {
      dropProperty(atomicOperation, ENGINE_PREFIX_PROPERTY + name);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public Set<String> indexEngines() {
    lock.readLock().lock();
    try {
      try (var stream =
          btree.iterateEntriesMajor(ENGINE_PREFIX_PROPERTY, false, true)) {
        return stream
            .filter((entry) -> entry.first().startsWith(ENGINE_PREFIX_PROPERTY))
            .map((entry) -> entry.first().substring(ENGINE_PREFIX_PROPERTY.length()))
            .collect(Collectors.toSet());
      }
    } finally {
      lock.readLock().unlock();
    }
  }

  private List<IndexEngineData> loadIndexEngines() {
    try (var stream =
        btree.iterateEntriesMajor(ENGINE_PREFIX_PROPERTY, false, true)) {
      return stream
          .filter((entry) -> entry.first().startsWith(ENGINE_PREFIX_PROPERTY))
          .map(
              (entry) -> {
                String name = null;
                try {
                  name = entry.first().substring(ENGINE_PREFIX_PROPERTY.length());
                  final var buffer =
                      collection.readRecord(entry.second().getCollectionPosition());
                  return deserializeIndexEngineProperty(
                      name, buffer.buffer, Integer.MIN_VALUE, entry.second().getCollectionId());
                } catch (IOException e) {
                  throw BaseException.wrapException(
                      new StorageException(storage.getName(),
                          "Can not load data for index "
                              + name
                              + " for storage "
                              + storage.getName()),
                      e, storage.getName());
                }
              })
          .collect(Collectors.toList());
    }
  }

  @Override
  @Nullable
  public IndexEngineData getIndexEngine(final String name, int defaultIndexId) {
    lock.readLock().lock();
    try {
      final var pair = readProperty(ENGINE_PREFIX_PROPERTY + name);
      if (pair == null) {
        return null;
      }

      final var property = pair.first;
      return deserializeIndexEngineProperty(name, property, defaultIndexId, pair.second);
    } finally {
      lock.readLock().unlock();
    }
  }

  public void updateCollection(
      AtomicOperation atomicOperation, final StorageCollectionConfiguration config) {
    lock.writeLock().lock();
    try {
      @SuppressWarnings("unchecked") final var collections =
          (List<StorageCollectionConfiguration>) cache.get(COLLECTIONS);
      if (config.getId() < collections.size()) {
        collections.set(config.getId(), config);
      } else {
        final var diff = config.getId() - collections.size();
        for (var i = 0; i < diff; i++) {
          collections.add(null);
        }

        collections.add(config);
        assert collections.size() - 1 == config.getId();
      }

      storeProperty(
          atomicOperation,
          COLLECTIONS_PREFIX_PROPERTY + config.getId(),
          updateCollectionConfig(config),
          COLLECTIONS_PROPERTY_VERSION);
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public List<StorageCollectionConfiguration> getCollections() {
    lock.readLock().lock();
    try {
      //noinspection unchecked
      return Collections.unmodifiableList((List<StorageCollectionConfiguration>) cache.get(COLLECTIONS));
    } finally {
      lock.readLock().unlock();
    }
  }

  private void preloadCollections() {
    final List<StorageCollectionConfiguration> collections = new ArrayList<>(1024);
    try (var stream =
        btree.iterateEntriesMajor(COLLECTIONS_PREFIX_PROPERTY, false, true)) {

      stream
          .filter((entry) -> entry.first().startsWith(COLLECTIONS_PREFIX_PROPERTY))
          .forEach(
              (entry) -> {
                final var id =
                    Integer.parseInt(entry.first().substring(COLLECTIONS_PREFIX_PROPERTY.length()));

                try {
                  final var buffer =
                      collection.readRecord(entry.second().getCollectionPosition());

                  if (collections.size() <= id) {
                    final var diff = id - collections.size();

                    for (var i = 0; i < diff; i++) {
                      collections.add(null);
                    }

                    collections.add(deserializeStorageCollectionConfig(id, buffer.buffer));
                    assert id == collections.size() - 1;
                  } else {
                    collections.set(id, deserializeStorageCollectionConfig(id, buffer.buffer));
                  }
                } catch (final IOException e) {
                  throw BaseException.wrapException(
                      new StorageException(storage.getName(),
                          "Can not load data for collection with id="
                              + id
                              + " for storage "
                              + storage.getName()),
                      e, storage.getName());
                }
              });
    }

    cache.put(COLLECTIONS, collections);
  }

  public void dropCollection(final AtomicOperation atomicOperation, final int collectionId) {
    lock.writeLock().lock();
    try {
      @SuppressWarnings("unchecked") final var collections =
          (List<StorageCollectionConfiguration>) cache.get(COLLECTIONS);
      if (collectionId < collections.size()) {
        collections.set(collectionId, null);
      }

      dropProperty(atomicOperation, COLLECTIONS_PREFIX_PROPERTY + collectionId);
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void setConfigurationUpdateListener(
      final StorageConfigurationUpdateListener updateListener) {
    lock.writeLock().lock();
    try {
      this.updateListener = updateListener;
    } finally {
      lock.writeLock().unlock();
    }
  }

  private static byte[] serializeIndexEngineProperty(final IndexEngineData indexEngineData) {
    var totalSize = 0;
    final List<byte[]> entries = new ArrayList<>(16);

    final var numericProperties =
        new byte[4 * IntegerSerializer.INT_SIZE + 5 * ByteSerializer.BYTE_SIZE];
    totalSize += numericProperties.length;
    entries.add(numericProperties);
    {
      var pos = 0;
      IntegerSerializer.serializeNative(
          indexEngineData.getVersion(), numericProperties, pos);
      pos += IntegerSerializer.INT_SIZE;
      IntegerSerializer.serializeNative(
          indexEngineData.getApiVersion(), numericProperties, pos);
      pos += IntegerSerializer.INT_SIZE;

      numericProperties[pos] = indexEngineData.getValueSerializerId();
      pos++;
      numericProperties[pos] = indexEngineData.getKeySerializedId();
      pos++;
      numericProperties[pos] = indexEngineData.isAutomatic() ? (byte) 1 : 0;
      pos++;
      numericProperties[pos] = indexEngineData.isNullValuesSupport() ? (byte) 1 : 0;
      pos++;
      numericProperties[pos] = indexEngineData.isMultivalue() ? (byte) 1 : 0;
      pos++;

      IntegerSerializer.serializeNative(
          indexEngineData.getKeySize(), numericProperties, pos);
      pos += IntegerSerializer.INT_SIZE;

      IntegerSerializer.serializeNative(
          indexEngineData.getIndexId(), numericProperties, pos);
    }

    final var algorithm = serializeStringValue(indexEngineData.getAlgorithm());
    totalSize += algorithm.length;
    entries.add(algorithm);

    final var indexType =
        serializeStringValue(
            indexEngineData.getIndexType() == null ? "" : indexEngineData.getIndexType());
    entries.add(indexType);
    totalSize += indexType.length;

    final var encryption = serializeStringValue(indexEngineData.getEncryption());
    totalSize += encryption.length;
    entries.add(encryption);

    final var keyTypesValue = indexEngineData.getKeyTypes();
    final var keyTypesSize = new byte[4];
    IntegerSerializer.serializeNative(keyTypesValue.length, keyTypesSize, 0);
    totalSize += keyTypesSize.length;
    entries.add(keyTypesSize);

    for (final var typeValue : keyTypesValue) {
      final var keyTypeName = serializeStringValue(typeValue.name());
      totalSize += keyTypeName.length;
      entries.add(keyTypeName);
    }

    final var engineProperties = indexEngineData.getEngineProperties();
    final var enginePropertiesSize = new byte[IntegerSerializer.INT_SIZE];
    totalSize += enginePropertiesSize.length;
    entries.add(enginePropertiesSize);

    if (engineProperties != null) {
      IntegerSerializer.serializeNative(engineProperties.size(), enginePropertiesSize, 0);

      for (final var engineProperty : engineProperties.entrySet()) {
        final var key = serializeStringValue(engineProperty.getKey());
        totalSize += key.length;
        entries.add(key);

        final var value = serializeStringValue(engineProperty.getValue());
        totalSize += value.length;
        entries.add(value);
      }
    }

    return mergeBinaryEntries(totalSize, entries);
  }

  private IndexEngineData deserializeIndexEngineProperty(
      final String name, final byte[] property, final int defaultIndexId, final int binaryVersion) {
    var pos = 0;

    final var version = IntegerSerializer.deserializeNative(property, pos);
    pos += IntegerSerializer.INT_SIZE;

    final var apiVersion = IntegerSerializer.deserializeNative(property, pos);
    pos += IntegerSerializer.INT_SIZE;

    final var valueSerializerId = property[pos];
    pos++;

    final var keySerializerId = property[pos];
    pos++;

    final var isAutomatic = property[pos] == 1;
    pos++;

    final var isNullValueSupport = property[pos] == 1;
    pos++;

    final var isMultiValue = property[pos] == 1;
    pos++;

    final var keySize = IntegerSerializer.deserializeNative(property, pos);
    pos += IntegerSerializer.INT_SIZE;

    final int indexId;
    if (getVersion() >= 23 || binaryVersion >= 1) {
      final var iid = IntegerSerializer.deserializeNative(property, pos);
      if (iid == Integer.MIN_VALUE) {
        indexId = defaultIndexId;
      } else {
        indexId = iid;
      }

      pos += IntegerSerializer.INT_SIZE;
    } else {
      indexId = defaultIndexId;
    }

    final var algorithm = deserializeStringValue(property, pos);
    pos += getSerializedStringSize(property, pos);

    final var indexType = deserializeStringValue(property, pos);
    pos += getSerializedStringSize(property, pos);

    final var encryption = deserializeStringValue(property, pos);
    pos += getSerializedStringSize(property, pos);

    final var keyTypesSize = IntegerSerializer.deserializeNative(property, pos);
    pos += IntegerSerializer.INT_SIZE;

    final var keyTypes = new PropertyTypeInternal[keyTypesSize];
    for (var i = 0; i < keyTypesSize; i++) {
      final var typeName = deserializeStringValue(property, pos);
      pos += getSerializedStringSize(property, pos);

      keyTypes[i] = PropertyTypeInternal.valueOf(typeName);
    }

    final Map<String, String> engineProperties = new HashMap<>(8);
    final var enginePropertiesSize = IntegerSerializer.deserializeNative(property, pos);
    pos += IntegerSerializer.INT_SIZE;

    for (var i = 0; i < enginePropertiesSize; i++) {
      final var key = deserializeStringValue(property, pos);
      pos += getSerializedStringSize(property, pos);

      final var value = deserializeStringValue(property, pos);
      pos += getSerializedStringSize(property, pos);

      engineProperties.put(key, value);
    }

    return new IndexEngineData(
        indexId,
        name,
        algorithm,
        indexType,
        true,
        version,
        apiVersion,
        isMultiValue,
        valueSerializerId,
        keySerializerId,
        isAutomatic,
        keyTypes,
        isNullValueSupport,
        keySize,
        encryption,
        configuration.getValueAsString(GlobalConfiguration.STORAGE_ENCRYPTION_KEY),
        engineProperties);
  }

  private static byte[] mergeBinaryEntries(final int totalSize, final List<byte[]> entries) {
    final var property = new byte[totalSize];
    var pos = 0;
    for (final var entry : entries) {
      System.arraycopy(entry, 0, property, pos, entry.length);
      pos += entry.length;
    }

    assert pos == property.length;
    return property;
  }

  private static byte[] updateCollectionConfig(final StorageCollectionConfiguration collection) {
    var totalSize = 0;
    final List<byte[]> entries = new ArrayList<>(8);

    final var name = serializeStringValue(collection.getName());
    totalSize += name.length;
    entries.add(name);

    final var paginatedCollectionConfiguration =
        (StoragePaginatedCollectionConfiguration) collection;
    final var numericData = new byte[IntegerSerializer.INT_SIZE + ByteSerializer.BYTE_SIZE];
    totalSize += numericData.length;
    entries.add(numericData);

    numericData[0] = paginatedCollectionConfiguration.useWal ? (byte) 1 : 0;

    IntegerSerializer.serializeNative(
        paginatedCollectionConfiguration.getBinaryVersion(), numericData, 1);

    final var encryption = serializeStringValue(paginatedCollectionConfiguration.encryption);
    totalSize += encryption.length;
    entries.add(encryption);

    final var conflictStrategy =
        serializeStringValue(paginatedCollectionConfiguration.conflictStrategy);
    totalSize += conflictStrategy.length;
    entries.add(conflictStrategy);

    final var compression = serializeStringValue(paginatedCollectionConfiguration.compression);
    entries.add(compression);
    totalSize += compression.length;

    return mergeBinaryEntries(totalSize, entries);
  }

  private StorageCollectionConfiguration deserializeStorageCollectionConfig(
      final int id, final byte[] property) {
    var pos = 0;

    final var name = deserializeStringValue(property, pos);
    pos += getSerializedStringSize(property, pos);

    final var useWal = (property[pos] == 1);
    pos++;

    final var binaryVersion = IntegerSerializer.deserializeNative(property, pos);
    pos += IntegerSerializer.INT_SIZE;

    final var encryption = deserializeStringValue(property, pos);
    pos += getSerializedStringSize(property, pos);

    final var conflictStrategy = deserializeStringValue(property, pos);
    pos += getSerializedStringSize(property, pos);

    final var compression = deserializeStringValue(property, pos);

    return new StoragePaginatedCollectionConfiguration(
        id,
        name,
        null,
        useWal,
        0,
        0,
        compression,
        encryption,
        configuration.getValueAsString(GlobalConfiguration.STORAGE_ENCRYPTION_KEY),
        conflictStrategy,
        binaryVersion);
  }

  private void dropProperty(final AtomicOperation atomicOperation, final String name) {
    final var identifiable = btree.remove(atomicOperation, name);

    if (identifiable != null) {
      collection.deleteRecord(atomicOperation, identifiable.getCollectionPosition());
    }

    final var pausedNotificationsState = pauseNotifications.get();
    if (updateListener != null) {
      if (!pausedNotificationsState.notificationsPaused) {
        updateListener.onUpdate(this);
        pausedNotificationsState.pendingChanges = 0;
      } else {
        pausedNotificationsState.pendingChanges++;
      }
    }
  }

  private void updateStringProperty(
      final AtomicOperation atomicOperation,
      final String name,
      final String value,
      final boolean useCache) {
    if (useCache) {
      cache.put(name, value);
    }

    final var property = serializeStringValue(value);

    storeProperty(atomicOperation, name, property, 0);
  }

  private static byte[] serializeStringValue(final String value) {
    final byte[] property;
    if (value == null) {
      property = new byte[1];
    } else {
      final var rawString = value.getBytes(StandardCharsets.UTF_16);
      property = new byte[rawString.length + 1 + IntegerSerializer.INT_SIZE];
      property[0] = 1;

      IntegerSerializer.serializeNative(rawString.length, property, 1);

      System.arraycopy(rawString, 0, property, 5, rawString.length);
    }

    return property;
  }

  @Nullable
  private static String deserializeStringValue(final byte[] raw, final int start) {
    if (raw[start] == 0) {
      return null;
    }

    final var stringSize = IntegerSerializer.deserializeNative(raw, start + 1);
    return new String(raw, start + 5, stringSize, StandardCharsets.UTF_16);
  }

  private static int getSerializedStringSize(final byte[] raw, final int start) {
    if (raw[start] == 0) {
      return 1;
    }

    return IntegerSerializer.deserializeNative(raw, start + 1) + 5;
  }

  private void updateIntProperty(
      AtomicOperation atomicOperation, final String name, final int value) {
    cache.put(name, value);

    final var property = new byte[IntegerSerializer.INT_SIZE];
    IntegerSerializer.serializeNative(value, property, 0);

    storeProperty(atomicOperation, name, property, 0);
  }

  private void storeProperty(
      AtomicOperation atomicOperation,
      final String name,
      final byte[] property,
      final int propertyBinaryVersion) {
    var identity = btree.get(name);

    if (identity == null) {
      final var position =
          collection.createRecord(property, 0, (byte) 0, null, atomicOperation);
      identity = new RecordId(propertyBinaryVersion, position.collectionPosition);
      btree.put(atomicOperation, name, identity);
    } else {
      collection.updateRecord(identity.getCollectionPosition(), property, -1, (byte) 0, atomicOperation);
    }

    final var pausedNotificationsState = pauseNotifications.get();
    if (updateListener != null) {
      if (!pausedNotificationsState.notificationsPaused) {
        pausedNotificationsState.pendingChanges = 0;
        updateListener.onUpdate(this);
      } else {
        pausedNotificationsState.pendingChanges++;
      }
    }
  }

  @Nullable
  private RawPairObjectInteger<byte[]> readProperty(final String name) {
    try {
      final var rid = btree.get(name);
      if (rid == null) {
        return null;
      }

      final var buffer = collection.readRecord(rid.getCollectionPosition());
      return new RawPairObjectInteger<>(buffer.buffer, rid.getCollectionId());
    } catch (final IOException e) {
      throw BaseException.wrapException(
          new StorageException(storage.getName(),
              "Error during read of configuration property " + name), e, storage.getName());
    }
  }

  private boolean containsProperty(@SuppressWarnings("SameParameterValue") final String name) {
    return btree.get(name) != null;
  }

  private String readStringProperty(final String name) {
    return (String) cache.get(name);
  }

  private int readIntProperty(final String name, final boolean useCache) {
    if (useCache) {
      final var cachedValue = cache.get(name);
      return (int) cachedValue;
    }

    var pair = readProperty(name);
    if (pair == null) {
      throw new IllegalStateException("Property " + name + " is absent");
    }

    final var property = pair.first;

    if (property.length < 4) {
      throw new IllegalStateException(
          "Invalid length of property " + name + " len = " + property.length);
    }

    return IntegerSerializer.deserializeNative(property, 0);
  }

  private void preloadIntProperties() {
    for (final var name : INT_PROPERTIES) {
      final var pair = readProperty(name);

      if (pair != null) {
        cache.put(name, IntegerSerializer.deserializeNative(pair.first, 0));
      }
    }
  }

  private void preloadStringProperties() {
    for (final var name : STRING_PROPERTIES) {
      final var property = readProperty(name);
      if (property != null) {
        cache.put(name, deserializeStringValue(property.first, 0));
      }
    }
  }

  private void init(final AtomicOperation atomicOperation) {
    updateVersion(atomicOperation);
    updateBinaryFormatVersion(atomicOperation);

    setCharset(atomicOperation, DEFAULT_CHARSET);
    setDateFormat(atomicOperation, DEFAULT_DATE_FORMAT);
    setDateTimeFormat(atomicOperation, DEFAULT_DATETIME_FORMAT);
    setLocaleLanguage(atomicOperation, Locale.getDefault().getLanguage());
    setLocaleCountry(atomicOperation, Locale.getDefault().getCountry());
    setTimeZone(atomicOperation, TimeZone.getDefault());

    setPageSize(atomicOperation, -1);
    setFreeListBoundary(atomicOperation, -1);
    setMaxKeySize(atomicOperation, -1);

    if (!configuration
        .getContextKeys()
        .contains(GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey())) {
      configuration.setValue(
          GlobalConfiguration.CLASS_COLLECTIONS_COUNT,
          GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getValueAsInteger()); // 0 = AUTOMATIC
    }
    autoInitCollections();

    updateMinimumCollections(atomicOperation); // store inside of configuration

    setRecordSerializerVersion(atomicOperation, 0);
  }

  private void copy(
      AtomicOperation atomicOperation, final StorageConfiguration storageConfiguration) {
    setCharset(atomicOperation, storageConfiguration.getCharset());
    setSchemaRecordId(atomicOperation, storageConfiguration.getSchemaRecordId());
    setIndexMgrRecordId(atomicOperation, storageConfiguration.getIndexMgrRecordId());

    final var timeZone = storageConfiguration.getTimeZone();
    assert timeZone != null;

    setTimeZone(atomicOperation, timeZone);
    setDateFormat(atomicOperation, storageConfiguration.getDateFormat());
    setDateTimeFormat(atomicOperation, storageConfiguration.getDateTimeFormat());

    this.configuration = storageConfiguration.getContextConfiguration();

    setMinimumCollections(storageConfiguration.getMinimumCollections());

    setLocaleCountry(atomicOperation, storageConfiguration.getLocaleCountry());
    setLocaleLanguage(atomicOperation, storageConfiguration.getLocaleLanguage());

    final var properties = storageConfiguration.getProperties();
    for (final var property : properties) {
      setProperty(atomicOperation, property.name, property.value);
    }

    setCollectionSelection(atomicOperation, storageConfiguration.getCollectionSelection());
    setConflictStrategy(atomicOperation, storageConfiguration.getConflictStrategy());
    setValidation(atomicOperation, storageConfiguration.isValidationEnabled());

    var counter = 0;
    final var indexEngines = storageConfiguration.indexEngines();

    for (final var engine : indexEngines) {
      addIndexEngine(atomicOperation, engine, storageConfiguration.getIndexEngine(engine, counter));
      counter++;
    }

    setRecordSerializer(atomicOperation, storageConfiguration.getRecordSerializer());
    setRecordSerializerVersion(atomicOperation, storageConfiguration.getRecordSerializerVersion());

    final var collections = storageConfiguration.getCollections();
    for (final var collection : collections) {
      if (collection != null) {
        updateCollection(atomicOperation, collection);
      }
    }

    setCreationVersion(atomicOperation, storageConfiguration.getCreatedAtVersion());
    setPageSize(atomicOperation, storageConfiguration.getPageSize());
    setFreeListBoundary(atomicOperation, storageConfiguration.getFreeListBoundary());
    setMaxKeySize(atomicOperation, storageConfiguration.getMaxKeySize());
  }

  private void autoInitCollections() {
    if (getContextConfiguration().getValueAsInteger(GlobalConfiguration.CLASS_COLLECTIONS_COUNT)
        == 0) {
      getContextConfiguration().setValue(GlobalConfiguration.CLASS_COLLECTIONS_COUNT, 8);
    }
  }

  private static final class PausedNotificationsState {

    private boolean notificationsPaused;
    private long pendingChanges;
  }
}
