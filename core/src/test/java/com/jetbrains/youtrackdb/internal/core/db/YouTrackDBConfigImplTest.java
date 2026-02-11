/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.ATTRIBUTES;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class YouTrackDBConfigImplTest {

  @Test
  public void testBuildSettings() {
    var settings =
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.DB_POOL_MAX, 20)
            .addAttribute(ATTRIBUTES.LOCALE_COUNTRY, "US")
            .build();

    assertEquals(20, ((YouTrackDBConfigImpl) settings).getConfiguration()
        .getValue(GlobalConfiguration.DB_POOL_MAX));
    assertEquals("US", settings.getAttributes().get(ATTRIBUTES.LOCALE_COUNTRY));
  }

  @Test
  public void testBuildSettingsFromMap() {
    Map<String, Object> configs = new HashMap<>();
    configs.put(GlobalConfiguration.DB_POOL_MAX.getKey(), 20);
    var settings = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .fromMap(configs).build();
    assertEquals(20, settings.getConfiguration().getValue(GlobalConfiguration.DB_POOL_MAX));
  }

  @Test
  public void testBuildSettingsFromGlobalMap() {
    Map<GlobalConfiguration, Object> configs = new HashMap<>();
    configs.put(GlobalConfiguration.DB_POOL_MAX, 20);
    var settings = (YouTrackDBConfigImpl) YouTrackDBConfig.builder()
        .fromGlobalConfigurationParameters(configs).build();
    assertEquals(20, settings.getConfiguration().getValue(GlobalConfiguration.DB_POOL_MAX));
  }

  @Test
  public void testParentConfig() {
    var parent =
        YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.DB_POOL_MAX, 20)
            .addAttribute(ATTRIBUTES.LOCALE_LANGUAGE, "en")
            .build();

    var settings =
        (YouTrackDBConfigImpl) (YouTrackDBConfig.builder()
            .addGlobalConfigurationParameter(GlobalConfiguration.CLIENT_CONNECTION_STRATEGY,
                "ROUND_ROBIN_CONNECT")
            .addAttribute(ATTRIBUTES.LOCALE_LANGUAGE, "en")
            .build());

    settings.setParent((YouTrackDBConfigImpl) parent);

    assertEquals(20, settings.getConfiguration().getValue(GlobalConfiguration.DB_POOL_MAX));
    assertEquals(
        "ROUND_ROBIN_CONNECT",
        settings.getConfiguration().getValue(GlobalConfiguration.CLIENT_CONNECTION_STRATEGY));
    assertEquals("en", settings.getAttributes().get(ATTRIBUTES.LOCALE_LANGUAGE));
  }
}
