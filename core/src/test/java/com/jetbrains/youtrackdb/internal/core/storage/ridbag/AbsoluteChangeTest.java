package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.Test;

public class AbsoluteChangeTest {

  @Test(expected = NullPointerException.class)
  public void testConstructorRejectsNullSecondaryRid() {
    new AbsoluteChange(1, null);
  }

  @Test(expected = NullPointerException.class)
  public void testSetSecondaryRidRejectsNull() {
    var change = new AbsoluteChange(1, new RecordId(1, 1));
    change.setSecondaryRid(null);
  }

  @Test
  public void testConstructorAcceptsNonNullSecondaryRid() {
    var rid = new RecordId(1, 1);
    var change = new AbsoluteChange(1, rid);

    assert change.getSecondaryRid() == rid;
  }

  @Test
  public void testSetSecondaryRidAcceptsNonNull() {
    var rid = new RecordId(1, 1);
    var change = new AbsoluteChange(1, rid);

    var newRid = new RecordId(2, 2);
    change.setSecondaryRid(newRid);

    assert change.getSecondaryRid() == newRid;
  }
}
