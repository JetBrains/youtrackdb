package com.jetbrains.youtrack.db.internal.core.db.record;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.exception.ValidationException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import org.junit.Test;

public class DBRecordLazySetTest extends DbTestBase {

  private EntityImpl doc1;
  private EntityImpl doc2;

  public void beforeTest() throws Exception {
    super.beforeTest();
    session.begin();
    doc1 =
        ((EntityImpl) session.newEntity()).field("doc1", "doc1");
    doc2 =
        ((EntityImpl) session.newEntity()).field("doc2", "doc2");
    ((EntityImpl) session.newEntity()).field("doc3", "doc3");
    session.commit();
  }

  @Test
  public void testDocumentNotEmbedded() {
    session.begin();
    var set = new LinkSet((EntityImpl) session.newEntity());
    var doc = (EntityImpl) session.newEntity();
    set.add(doc);
    assertFalse(doc.isEmbedded());
    session.rollback();
  }

  @Test()
  public void testSetAddRemove() {
    session.begin();
    var set = new LinkSet((EntityImpl) session.newEntity());
    var doc = (EntityImpl) session.newEntity();
    set.add(doc);
    set.remove(doc);
    assertTrue(set.isEmpty());
    session.rollback();
  }

  @Test
  public void testSetRemoveNotPersistent() {
    session.begin();
    var set = new LinkSet((EntityImpl) session.newEntity());
    doc1 = session.bindToSession(doc1);
    doc2 = session.bindToSession(doc2);

    set.add(doc1);
    set.add(doc2);
    set.add(new RecordId(5, 1000));
    assertEquals(3, set.size());
    set.remove(new RecordId(5, 1000));
    assertEquals(2, set.size());
    session.rollback();
  }

  @Test(expected = ValidationException.class)
  public void testSetWithNotExistentRecordWithValidation() {
    var test = session.getMetadata().getSchema().createClass("test");
    var test1 = session.getMetadata().getSchema().createClass("test1");
    test.createProperty("fi", PropertyType.LINKSET).setLinkedClass(test1);

    session.begin();
    var doc = (EntityImpl) session.newEntity(test);
    var set = new LinkSet(doc);
    set.add(new RecordId(5, 1000));
    doc.field("fi", set);
    session.commit();
  }
}
