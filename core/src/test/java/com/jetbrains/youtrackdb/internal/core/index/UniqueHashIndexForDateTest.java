package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.text.ParseException;
import org.junit.Assert;
import org.junit.Test;

public class UniqueHashIndexForDateTest extends DbTestBase {

  @Test
  public void testSimpleUniqueDateIndex() throws ParseException {
    graph.autoExecuteInTx(g ->
        g.addSchemaClass("test_edge").addSchemaProperty("date", PropertyType.DATE).addPropertyIndex(
            IndexType.NOT_UNIQUE));
    session.begin();
    var doc = (EntityImpl) session.newEntity("test_edge");
    doc.setProperty("date", "2015-03-24 08:54:49");

    var doc1 = (EntityImpl) session.newEntity("test_edge");
    doc1.setProperty("date", "2015-03-24 08:54:49");

    try {
      session.commit();
      Assert.fail("expected exception for duplicate ");
    } catch (BaseException e) {

    }
  }
}
