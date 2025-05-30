package com.youtrack.db.gremlin.internal;


import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.jetbrain.youtrack.db.gremlin.internal.io.YTDBIoRegistry;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import java.io.StringWriter;
import org.apache.tinkerpop.gremlin.structure.io.graphson.GraphSONIo;
import org.apache.tinkerpop.shaded.jackson.databind.Module;
import org.apache.tinkerpop.shaded.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;

public class IoRegistryTest extends GraphBaseTest {

  private ObjectMapper objectMapper;

  @Before
  public void setup() {
    var modules = YTDBIoRegistry.instance().find(
        GraphSONIo.class);
    objectMapper = new ObjectMapper();
    modules.forEach(module -> objectMapper.registerModule((Module) module.getValue1()));
  }

  @Test
  public void serializeORecordID747() throws Exception {
    var sw = new StringWriter();
    objectMapper.writeValue(sw, new RecordId(7, 47));

    var result = sw.toString();
    assertThat(result, equalTo("{\"collectionId\":7,\"collectionPosition\":47}"));
  }

  @Test
  public void serializeORecordID00() throws Exception {
    var sw = new StringWriter();
    objectMapper.writeValue(sw, new RecordId(0, 0));

    var result = sw.toString();
    assertThat(result, equalTo("{\"collectionId\":0,\"collectionPosition\":0}"));
  }

  @Test
  public void serializeORecordIDMaxMax() throws Exception {
    var sw = new StringWriter();
    objectMapper.writeValue(sw, new RecordId(RID.COLLECTION_MAX, Long.MAX_VALUE));

    var result = sw.toString();
    assertThat(result,
        equalTo("{\"collectionId\":32767,\"collectionPosition\":9223372036854775807}"));
  }

  @Test
  public void serializeVertex() throws Exception {
    graph.addVertex();
    var sw = new StringWriter();
    objectMapper.writeValue(sw, new RecordId(RID.COLLECTION_MAX, Long.MAX_VALUE));

    var result = sw.toString();
    assertThat(result,
        equalTo("{\"collectionId\":32767,\"collectionPosition\":9223372036854775807}"));
  }
}
