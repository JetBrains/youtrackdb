package com.jetbrains.youtrackdb.internal.core.exception;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.Test;

/**
 * Tests {@link RecordNotFoundException} construction and copy behavior including RID preservation.
 */
public class DBRecordNotFoundExceptionTest {

  @Test
  public void simpleExceptionCopyTest() {
    var ex = new RecordNotFoundException((String) null, new RecordId(1, 2));
    var ex1 = new RecordNotFoundException(ex);
    assertNotNull(ex1.getRid());
    assertEquals(1, ex1.getRid().getCollectionId());
    assertEquals(2, ex1.getRid().getCollectionPosition());
  }
}
