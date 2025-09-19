package com.jetbrains.youtrackdb.internal.core.db.hook;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.record.DBRecord;
import com.jetbrains.youtrackdb.api.record.RecordHook;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityPolicy;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import javax.annotation.Nonnull;
import org.junit.Test;

public class HookReadTest extends DbTestBase {

  @Test
  public void testSelectChangedInHook() {
    session.registerHook(
        new RecordHook() {
          @Override
          public void onUnregister() {
          }

          @Override
          public void onTrigger(@Nonnull TYPE iType,
              @Nonnull DBRecord iRecord) {
            if (iType == TYPE.READ
                && !((EntityImpl) iRecord)
                .getSchemaClassName()
                .equalsIgnoreCase(SecurityPolicy.class.getSimpleName())) {
              ((EntityImpl) iRecord).setProperty("read", "test");
            }
          }
        });

    session.getMetadata().getSlowMutableSchema().createClass("TestClass");
    session.begin();
    session.newEntity("TestClass");
    session.commit();

    session.begin();
    var res = session.query("select from TestClass");
    assertEquals("test", res.next().getProperty("read"));
    session.commit();
  }
}
