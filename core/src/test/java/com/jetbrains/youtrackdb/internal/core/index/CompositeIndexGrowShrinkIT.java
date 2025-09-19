package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

public class CompositeIndexGrowShrinkIT extends DbTestBase {

  private final Random random = new Random();

  public String randomText() {
    var str = "";
    var count = random.nextInt(10);
    for (var i = 0; i < count; i++) {
      str += random.nextInt(10000) + " ";
    }
    return str;
  }

  @Test
  public void testCompositeGrowShirnk() {
    final Schema schema = session.getMetadata().getSlowMutableSchema();
    var clazz = schema.createClass("CompositeIndex");
    clazz.createProperty("id", PropertyType.INTEGER);
    clazz.createProperty("bar", PropertyType.INTEGER);
    clazz.createProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    clazz.createProperty("name", PropertyType.STRING);

    session.execute(
            "create index CompositeIndex_id_tags_name on CompositeIndex (id, tags, name) NOTUNIQUE")
        .close();
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

    final Schema schema = session.getMetadata().getSlowMutableSchema();
    var clazz = schema.createClass("CompositeIndex");
    clazz.createProperty("id", PropertyType.INTEGER);
    clazz.createProperty("bar", PropertyType.INTEGER);
    clazz.createProperty("tags", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    clazz.createProperty("name", PropertyType.STRING);

    session.execute(
            "create index CompositeIndex_id_tags_name on CompositeIndex (id, tags, name) NOTUNIQUE")
        .close();

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
