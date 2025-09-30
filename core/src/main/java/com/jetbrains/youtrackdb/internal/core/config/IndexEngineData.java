package com.jetbrains.youtrackdb.internal.core.config;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class IndexEngineData {
  private final int indexId;
  @Nonnull
  private final String name;
  private final String indexType;

  private final boolean multivalue;
  private final byte valueSerializerId;
  private final byte keySerializedId;
  private final PropertyTypeInternal[] keyTypes;
  private final boolean nullValuesSupport;
  private final int keySize;
  private final Map<String, String> engineProperties;
  private final String encryption;
  private final String encryptionOptions;

  public IndexEngineData(
      int indexId,
      @Nonnull final String name,
      String indexType,
      final boolean multivalue,
      final byte valueSerializerId,
      final byte keySerializedId,
      final PropertyTypeInternal[] keyTypes,
      final boolean nullValuesSupport,
      final int keySize,
      final String encryption,
      final String encryptionOptions,
      final Map<String, String> engineProperties) {
    this.indexId = indexId;
    this.name = name;
    this.indexType = indexType;
    this.multivalue = multivalue;
    this.valueSerializerId = valueSerializerId;
    this.keySerializedId = keySerializedId;
    this.keyTypes = keyTypes;
    this.nullValuesSupport = nullValuesSupport;
    this.keySize = keySize;
    this.encryption = encryption;
    this.encryptionOptions = encryptionOptions;

    if (engineProperties == null) {
      this.engineProperties = null;
    } else {
      this.engineProperties = new HashMap<>(engineProperties);
    }
  }

  public int getIndexId() {
    return indexId;
  }

  public int getKeySize() {
    return keySize;
  }

  @Nonnull
  public String getName() {
    return name;
  }
  public boolean isMultivalue() {
    return multivalue;
  }

  public byte getValueSerializerId() {
    return valueSerializerId;
  }

  public byte getKeySerializedId() {
    return keySerializedId;
  }

  public PropertyTypeInternal[] getKeyTypes() {
    return keyTypes;
  }

  public String getEncryption() {
    return encryption;
  }

  public String getEncryptionOptions() {
    return encryptionOptions;
  }

  public boolean isNullValuesSupport() {
    return nullValuesSupport;
  }

  @Nullable
  public Map<String, String> getEngineProperties() {
    if (engineProperties == null) {
      return null;
    }

    return Collections.unmodifiableMap(engineProperties);
  }

  public String getIndexType() {
    return indexType;
  }
}
