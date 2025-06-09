package com.jetbrains.youtrack.db.internal.core.record.impl;


import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Tests that {@link EntityImpl} is serializable.
 *
 * @since 12/20/12
 */
public class DocumentSerializationPersistentTest extends BaseMemoryInternalDatabase {

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();

    session.begin();
    final var doc = (EntityImpl) session.newEntity();
    doc.setProperty("name", "Artem");

    final var linkedDoc = (EntityImpl) session.newEntity();

    doc.setProperty("country", linkedDoc, PropertyType.LINK);
    doc.newEmbeddedList("numbers").addAll(Arrays.asList(0, 1, 2, 3, 4, 5));

    session.commit();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testRidBagInEmbeddedDocument() {
    session.executeInTx(transaction -> {
      var doc = (EntityImpl) session.newEntity();
      var rids = new LinkBag(session);
      rids.add(new RecordId(2, 3));
      rids.add(new RecordId(2, 4));
      rids.add(new RecordId(2, 5));
      rids.add(new RecordId(2, 6));
      List<EntityImpl> docs = new ArrayList<EntityImpl>();
      var doc1 = (EntityImpl) session.newEntity();
      doc1.setProperty("rids", rids);
      docs.add(doc1);
      var doc2 = (EntityImpl) session.newEntity();
      doc2.setProperty("text", "text");
      docs.add(doc2);
      doc.setProperty("emb", docs, PropertyType.EMBEDDEDLIST);
      doc.setProperty("some", "test");

      var res = session.getSerializer().toStream(session, doc);
      session.getSerializer()
          .fromStream(session, res, (EntityImpl) session.newEntity(), new String[]{});
    });
  }
}
