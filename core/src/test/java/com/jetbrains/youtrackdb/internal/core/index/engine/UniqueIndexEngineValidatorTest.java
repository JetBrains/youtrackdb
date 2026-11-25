package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.index.IndexUnique;
import java.util.HashMap;
import org.junit.Test;

/** Unique validation accepts a fresh key or the same record, but never a different record. */
public class UniqueIndexEngineValidatorTest {

  private static final RecordId RID_A = new RecordId(10, 1);
  private static final RecordId RID_B = new RecordId(10, 2);

  @Test
  public void freshInsertReturnsThePersistentIdentity() {
    var validator = new UniqueIndexEngineValidator(index());
    assertEquals(RID_A, validator.validate("key", null, RID_A));
  }

  @Test
  public void puttingTheSameRecordAgainIsIgnored() {
    var validator = new UniqueIndexEngineValidator(index());
    assertSame(IndexEngineValidator.IGNORE, validator.validate("key", RID_A, RID_A));
  }

  @Test
  public void differentRecordsAlwaysThrowEvenWithMergeKeysMetadata() {
    var index = index();
    var validator = new UniqueIndexEngineValidator(index);
    assertThrows(RecordDuplicatedException.class, () -> validator.validate("key", RID_A, RID_B));
    for (Object setting : new Object[] {true, false, null, "true"}) {
      var metadata = new HashMap<String, Object>();
      metadata.put("mergeKeys", setting);
      when(index.getMetadata()).thenReturn(metadata);
      var error = assertThrows(RecordDuplicatedException.class,
          () -> validator.validate("key", RID_A, RID_B));
      assertEquals("testIndex", error.getIndexName());
    }
  }

  private static IndexUnique index() {
    var index = mock(IndexUnique.class);
    when(index.getName()).thenReturn("testIndex");
    return index;
  }
}
