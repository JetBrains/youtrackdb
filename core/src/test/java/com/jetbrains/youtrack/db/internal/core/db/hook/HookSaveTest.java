package com.jetbrains.youtrack.db.internal.core.db.hook;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import javax.annotation.Nonnull;
import org.junit.Test;

/**
 *
 */
public class HookSaveTest extends DbTestBase {

  @Test
  public void testCreatedLinkedInHook() {
    session.registerHook(
        new RecordHook() {
          @Override
          public void onTrigger(@Nonnull TYPE iType,
              @Nonnull DBRecord iRecord) {
            if (iType != TYPE.CREATE) {
              return;
            }

            if (iRecord instanceof Entity entity) {
              var cls = entity.getSchemaClass();
              if (cls != null && cls.getName().equals("test")) {
                var newEntity = session.getActiveTransaction().newEntity("another");
                entity.setProperty("testNewLinkedRecord", newEntity);
              }
            }
          }

        });

    session.getMetadata().getSchema().createClass("test");
    session.getMetadata().getSchema().createClass("another");

    session.begin();
    var entity = session.newEntity("test");
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    var newRef = activeTx.<Entity>load(entity).getEntity("testNewLinkedRecord");
    assertNotNull(newRef);
    assertTrue(newRef.getIdentity().isPersistent());
    session.commit();
  }

  @Test
  public void testCreatedBackLinkedInHook() {
    session.registerHook(
        new RecordHook() {
          @Override
          public void onTrigger(@Nonnull TYPE iType,
              @Nonnull DBRecord iRecord) {
            if (iType != TYPE.CREATE) {
              return;
            }

            if (iRecord instanceof Entity entity) {
              var cls = entity.getSchemaClass();
              if (cls != null && cls.getName().equals("test")) {
                var newEntity = HookSaveTest.this.session.newEntity("another");

                entity.setProperty("testNewLinkedRecord", newEntity);
                newEntity.setProperty("backLink", entity);
              }
            }
          }
        });

    session.getMetadata().getSchema().createClass("test");
    session.getMetadata().getSchema().createClass("another");

    session.begin();
    var doc = (EntityImpl) session.newEntity("test");
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    var entity = activeTx.<EntityImpl>load(doc);
    EntityImpl newRef = entity.getProperty("testNewLinkedRecord");
    assertNotNull(newRef);
    assertTrue(newRef.getIdentity().isPersistent());
    session.commit();
  }
}
