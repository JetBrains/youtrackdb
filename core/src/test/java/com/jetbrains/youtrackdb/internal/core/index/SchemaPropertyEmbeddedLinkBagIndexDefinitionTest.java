package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import org.apache.commons.configuration2.Configuration;
import org.junit.Assert;

/**
 * Tests index definitions for embedded LinkBag properties with high embedding thresholds.
 *
 * @since 1/30/14
 */
public class SchemaPropertyEmbeddedLinkBagIndexDefinitionTest extends
    SchemaPropertyLinkBagAbstractIndexDefinition {

  @Override
  protected Configuration createConfig() {
    var config = super.createConfig();
    config.setProperty(GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.getKey(),
        Integer.MAX_VALUE);
    config.setProperty(
        GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.getKey(),
        Integer.MAX_VALUE);

    return config;
  }

  @Override
  void assertEmbedded(LinkBag linkBag) {
    Assert.assertTrue(linkBag.isEmbedded());
  }
}
