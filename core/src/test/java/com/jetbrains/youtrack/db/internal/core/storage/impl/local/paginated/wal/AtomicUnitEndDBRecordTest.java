package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.RecordOperationMetadata;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationMetadata;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class AtomicUnitEndDBRecordTest {

  @Test
  public void recordMetadataSerializationTest() {
    var recordOperationMetadata = new RecordOperationMetadata();
    recordOperationMetadata.addRid(new RecordId(10, 42));
    recordOperationMetadata.addRid(new RecordId(42, 10));

    Map<String, AtomicOperationMetadata<?>> metadata = new LinkedHashMap<>();
    metadata.put(recordOperationMetadata.getKey(), recordOperationMetadata);

    var atomicUnitEndRecord = new AtomicUnitEndRecord(1, false, metadata);
    var arraySize = atomicUnitEndRecord.serializedSize() + 1;
    var content = new byte[arraySize];

    final var endOffset = atomicUnitEndRecord.toStream(content, 1);
    Assert.assertEquals(endOffset, content.length);

    var atomicUnitEndRecordD = new AtomicUnitEndRecord();
    final var dEndOffset = atomicUnitEndRecordD.fromStream(content, 1);
    Assert.assertEquals(dEndOffset, content.length);

    Assert.assertEquals(
        atomicUnitEndRecordD.getOperationUnitId(), atomicUnitEndRecord.getOperationUnitId());
    var recordOperationMetadataD =
        (RecordOperationMetadata)
            atomicUnitEndRecordD
                .getAtomicOperationMetadata()
                .get(RecordOperationMetadata.RID_METADATA_KEY);

    Assert.assertEquals(recordOperationMetadataD.getValue(), recordOperationMetadata.getValue());
  }

  @Test
  public void recordNoMetadataSerializationTest() {
    var atomicUnitEndRecord = new AtomicUnitEndRecord(1, false, null);
    var arraySize = atomicUnitEndRecord.serializedSize() + 1;
    var content = new byte[arraySize];

    final var endOffset = atomicUnitEndRecord.toStream(content, 1);
    Assert.assertEquals(endOffset, content.length);

    var atomicUnitEndRecordD = new AtomicUnitEndRecord();
    final var dEndOffset = atomicUnitEndRecordD.fromStream(content, 1);
    Assert.assertEquals(dEndOffset, content.length);
  }
}
