package com.jetbrains.youtrackdb.internal.server.plugin.gremlin;

import com.jetbrains.youtrackdb.internal.core.gremlin.io.YTDBIoRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import org.apache.tinkerpop.gremlin.driver.AuthProperties;
import org.apache.tinkerpop.gremlin.driver.AuthProperties.Property;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerIoRegistryV3;
import org.apache.tinkerpop.gremlin.util.MessageSerializer;
import org.apache.tinkerpop.gremlin.util.ser.AbstractMessageSerializer;
import org.apache.tinkerpop.gremlin.util.ser.GraphSONMessageSerializerV3;

public class YTDBGraphSONRemoteGraphProvider extends YTDBAbstractRemoteGraphProvider {

  public YTDBGraphSONRemoteGraphProvider() {
    super(createClusterBuilder(createSerializer()).authProperties(new AuthProperties().with(
        Property.USERNAME, "root").with(Property.PASSWORD, "root")).create());
  }

  private static MessageSerializer<?> createSerializer() {
    var graphSonSerializer = new GraphSONMessageSerializerV3();

    var config = new HashMap<String, Object>();
    var ytdbIoRegistry = YTDBIoRegistry.class.getName();
    var tinkerGraphIoRegistry = TinkerIoRegistryV3.class.getName();

    var registries = new ArrayList<String>();
    registries.add(ytdbIoRegistry);
    registries.add(tinkerGraphIoRegistry);

    config.put(AbstractMessageSerializer.TOKEN_IO_REGISTRIES, registries);
    graphSonSerializer.configure(config, null);

    return graphSonSerializer;
  }
}
