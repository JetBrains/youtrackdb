package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBConfigBuilderImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import org.junit.Assert;

/**
 * @since 1/30/14
 */
public class SchemaPropertyEmbeddedLinkBagIndexDefinitionTest extends
    SchemaPropertyLinkBagAbstractIndexDefinition {

  @Override
  protected YouTrackDBConfig createConfig(YouTrackDBConfigBuilderImpl builder) {
    builder.addGlobalConfigurationParameter(
        GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD,
        Integer.MAX_VALUE);
    builder.addGlobalConfigurationParameter(
        GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD,
        Integer.MAX_VALUE);

    return builder.build();
  }

  @Override
  void assertEmbedded(LinkBag linkBag) {
    Assert.assertTrue(linkBag.isEmbedded());
  }
}
