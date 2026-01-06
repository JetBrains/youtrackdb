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
package com.jetbrains.youtrackdb.internal.server.config;

import com.jetbrains.youtrackdb.internal.server.plugin.gremlin.YTDBSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.tinkerpop.gremlin.server.Settings.ScriptEngineSettings;

public class ServerConfigurationLoader {
  private long fileLastModified = -1;
  private final String filePath;

  public ServerConfigurationLoader(String filePath) {
    this.filePath = filePath;
  }

  public YTDBSettings load() throws IOException {
    YTDBSettings ytdbSettings;
    if (filePath.startsWith("classpath:")) {
      var configFile = filePath.substring("classpath:".length());
      var configStream = this.getClass().getClassLoader().getResourceAsStream(configFile);
      if (configStream != null) {
        ytdbSettings = YTDBSettings.read(configStream);
      } else {
        throw new IllegalStateException("Gremlin server configuration file not found");
      }
    } else {
      var configFilePath = Path.of(filePath);
      if (Files.exists(configFilePath)) {
        ytdbSettings = YTDBSettings.read(Files.newInputStream(configFilePath));
        fileLastModified = Files.getLastModifiedTime(configFilePath).toMillis();
      } else {
        throw new IllegalStateException("Gremlin server configuration file not found");
      }
    }

    augmentServerSettings(ytdbSettings);
    return ytdbSettings;
  }

  public boolean checkForAutoReloading() {
    if (filePath != null && !filePath.startsWith("classpath:") && Files.exists(Path.of(filePath))) {
      try {
        return Files.getLastModifiedTime(Path.of(filePath)).toMillis() > fileLastModified;
      } catch (IOException e) {
        return false;
      }
    }

    return false;
  }

  private static void augmentServerSettings(YTDBSettings ytdbSettings) {
    ytdbSettings.scriptEngines.clear();
    ytdbSettings.scriptEngines.put("gremlin-lang", new ScriptEngineSettings());
  }
}
