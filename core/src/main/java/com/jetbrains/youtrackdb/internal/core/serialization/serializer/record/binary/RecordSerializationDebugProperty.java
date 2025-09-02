package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;

public class RecordSerializationDebugProperty {

  public String name;
  public int globalId;
  public PropertyTypeInternal type;
  public RuntimeException readingException;
  public boolean faildToRead;
  public int failPosition;
  public Object value;
  public int valuePos;
}
