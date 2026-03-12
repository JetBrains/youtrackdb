package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public class EmbeddedLinkBagTest extends LinkBagJUnit5Test {

  private int topThreshold;
  private int bottomThreshold;

  @Override
  @BeforeEach
  void beforeEach() throws Exception {
    topThreshold =
        GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.getValueAsInteger();
    bottomThreshold =
        GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.getValueAsInteger();

    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(Integer.MAX_VALUE);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(Integer.MAX_VALUE);

    super.beforeEach();
  }

  @Override
  @AfterEach
  void afterEach() throws Exception {
    super.afterEach();
    GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD.setValue(topThreshold);
    GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD.setValue(bottomThreshold);
  }

  @Override
  protected void assertEmbedded(boolean isEmbedded) {
    assertTrue(isEmbedded);
  }
}
