package com.jetbrains.youtrackdb.internal.core.record.impl;

import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;

public class DocumentSchemafullBinarySerializationTest
    extends DocumentSchemafullSerializationTest {

  public DocumentSchemafullBinarySerializationTest() {
    super(new RecordSerializerBinary());
  }
}
