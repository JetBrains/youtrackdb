package com.jetbrains.youtrack.db.internal.core.db.hook;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.junit.Test;

public class HookRegisterRemoveTest extends DbTestBase {

  @Test
  public void addAndRemoveHookTest() {
    final var integer = new AtomicInteger(0);
    var iHookImpl =
        new RecordHook() {

          @Override
          public void onUnregister() {
          }

          @Override
          public RESULT onTrigger(@Nonnull TYPE iType,
              @Nonnull DBRecord iRecord) {
            integer.incrementAndGet();
            return null;
          }

        };
    session.registerHook(iHookImpl);

    session.begin();
    var entity = session.newEntity();
    entity.setProperty("test", "test");
    session.commit();

    assertEquals(3, integer.get());
    session.unregisterHook(iHookImpl);

    session.begin();
    session.newEntity();
    session.commit();

    assertEquals(3, integer.get());
  }
}
