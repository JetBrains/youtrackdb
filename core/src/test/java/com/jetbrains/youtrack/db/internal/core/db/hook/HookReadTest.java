package com.jetbrains.youtrack.db.internal.core.db.hook;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityPolicy;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
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

    session.getMetadata().getSchema().createClass("TestClass");
    session.begin();
    session.newEntity("TestClass");
    session.commit();

    session.begin();
    var res = session.query("select from TestClass");
    assertEquals("test", res.next().getProperty("read"));
    session.commit();
  }
}
