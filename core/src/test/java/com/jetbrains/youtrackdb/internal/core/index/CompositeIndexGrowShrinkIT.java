package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.Arrays;
import org.junit.Test;

public class CompositeIndexGrowShrinkIT extends DbTestBase {
  @Test
  public void testCompositeGrowShrink() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("CompositeIndex",
            __.createSchemaProperty("id", PropertyType.INTEGER),
            __.createSchemaProperty("bar", PropertyType.INTEGER),
            __.createSchemaProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING),
            __.createSchemaProperty("name", PropertyType.STRING)
        ).createClassIndex("CompositeIndex_id_tags_name", IndexType.NOT_UNIQUE,
            "id", "tags", "name")
    );

    for (var i = 0; i < 150000; i++) {
      session.begin();
      var rec = session.newEntity("CompositeIndex");
      rec.setProperty("id", i);
      rec.setProperty("bar", i);

      rec.getOrCreateEmbeddedList(
          "tags").addAll(
          Arrays.asList(
              "soem long and more complex tezxt just un case it may be important", "two"));

      rec.setProperty("name", "name" + i);
      session.commit();
    }

    session.begin();
    session.execute("delete from CompositeIndex").close();
    session.commit();
  }

  @Test
  public void testCompositeGrowDrop() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("CompositeIndex",
            __.createSchemaProperty("id", PropertyType.INTEGER),
            __.createSchemaProperty("bar", PropertyType.INTEGER),
            __.createSchemaProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING),
            __.createSchemaProperty("name", PropertyType.STRING)
        ).createClassIndex("CompositeIndex_id_tags_name", IndexType.NOT_UNIQUE,
            "id", "tags", "name")
    );

    for (var i = 0; i < 150000; i++) {
      session.begin();
      var rec = session.newEntity("CompositeIndex");
      rec.setProperty("id", i);
      rec.setProperty("bar", i);
      rec.getOrCreateEmbeddedList(
          "tags").addAll(
          Arrays.asList(
              "soem long and more complex tezxt just un case it may be important", "two"));
      rec.setProperty("name", "name" + i);
      session.commit();
    }
    session.execute("drop index CompositeIndex_id_tags_name").close();
  }
}
