package com.jetbrains.youtrack.db.internal.server.plugin.gremlin;


import org.apache.tinkerpop.gremlin.driver.AuthProperties;
import org.apache.tinkerpop.gremlin.driver.AuthProperties.Property;
import org.apache.tinkerpop.gremlin.util.ser.Serializers;

public class YTDBGraphSONRemoteGraphProvider extends YTDBAbstractRemoteGraphProvider {

  public YTDBGraphSONRemoteGraphProvider() {
    super(createClusterBuilder(Serializers.GRAPHSON_V3).authProperties(new AuthProperties().with(
        Property.USERNAME, "root").with(Property.PASSWORD, "root")).create());
  }
}
