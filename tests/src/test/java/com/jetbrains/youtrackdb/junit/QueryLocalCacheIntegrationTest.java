package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class QueryLocalCacheIntegrationTest extends BaseDBJUnit5Test {

  @BeforeEach
  @Override
  void beforeEach() throws Exception {
    super.beforeEach();

    session.getMetadata().getSchema().createClass("FetchClass");

    session
        .getMetadata()
        .getSchema()
        .createClass("SecondFetchClass")
        .createProperty("surname", PropertyType.STRING)
        .setMandatory(true);
    session.getMetadata().getSchema().createClass("OutInFetchClass");

    session.begin();
    var singleLinked = ((EntityImpl) session.newEntity());
    var doc = ((EntityImpl) session.newEntity("FetchClass"));
    doc.setProperty("name", "first");
    var doc1 = ((EntityImpl) session.newEntity("FetchClass"));
    doc1.setProperty("name", "second");
    doc1.setProperty("linked", singleLinked);
    var doc2 = ((EntityImpl) session.newEntity("FetchClass"));
    doc2.setProperty("name", "third");
    var linkList = session.newLinkList();
    linkList.add(doc);
    linkList.add(doc1);
    doc2.setProperty("linkList", linkList);
    doc2.setProperty("linked", singleLinked);
    var linkSet = session.newLinkSet();
    linkSet.add(doc);
    linkSet.add(doc1);
    doc2.setProperty("linkSet", linkSet);

    var doc3 = ((EntityImpl) session.newEntity("FetchClass"));
    doc3.setProperty("name", "forth");
    doc3.setProperty("ref", doc2);
    doc3.setProperty("linkSet", session.newLinkSet(linkSet));
    doc3.setProperty("linkList", session.newLinkList(linkList));

    var doc4 = ((EntityImpl) session.newEntity("SecondFetchClass"));
    doc4.setProperty("name", "fifth");
    doc4.setProperty("surname", "test");

    var doc5 = ((EntityImpl) session.newEntity("SecondFetchClass"));
    doc5.setProperty("name", "sixth");
    doc5.setProperty("surname", "test");

    var doc6 = ((EntityImpl) session.newEntity("OutInFetchClass"));
    var out = new LinkBag(session);
    out.add(doc2.getIdentity());
    out.add(doc3.getIdentity());
    doc6.setProperty("out_friend", out);
    var in = new LinkBag(session);
    in.add(doc4.getIdentity());
    in.add(doc5.getIdentity());
    doc6.setProperty("in_friend", in);
    doc6.setProperty("name", "myName");

    session.commit();
  }

  @AfterEach
  @Override
  void afterEach() throws Exception {
    session.getMetadata().getSchema().dropClass("FetchClass");
    session.getMetadata().getSchema().dropClass("SecondFetchClass");
    session.getMetadata().getSchema().dropClass("OutInFetchClass");
    super.afterEach();
  }

  @Test
  void queryTest() {
    session.begin();

    var resultset =
        session.query("select * from FetchClass").toList();

    RID linked;
    for (var d : resultset) {
      linked = d.getLink("linked");
      if (linked != null) {
        assertNull(session.getLocalCache().findRecord(linked));
      }
    }
    session.commit();
  }
}
