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
package com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.post;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.ServerCommandAuthenticatedServerAbstract;
import java.io.IOException;

public class ServerCommandPostServer extends ServerCommandAuthenticatedServerAbstract {

  private static final String[] NAMES = {"POST|server/*"};

  public ServerCommandPostServer() {
    super("server.settings");
  }

  @Override
  public boolean execute(final HttpRequest iRequest, final HttpResponse iResponse)
      throws Exception {
    final var urlParts =
        checkSyntax(iRequest.getUrl(), 3, "Syntax error: server/<setting-name>/<setting-value>");

    iRequest.getData().commandInfo = "Change server settings";

    if (urlParts[1] == null || urlParts.length == 0) {
      throw new IllegalArgumentException("setting-name is null or empty");
    }

    final var settingName = urlParts[1];
    final var settingValue = urlParts[2];

    if (settingName.startsWith("configuration.")) {
      changeConfiguration(
          iResponse, settingName.substring("configuration.".length()), settingValue);

    } else {
      iResponse.send(
          HttpUtils.STATUS_BADREQ_CODE,
          HttpUtils.STATUS_BADREQ_DESCRIPTION,
          HttpUtils.CONTENT_TEXT_PLAIN,
          "setting-name '" + settingName + "' is not supported",
          null);
    }

    return false;
  }

  private void changeConfiguration(
      final HttpResponse iResponse, final String settingName, final String settingValue)
      throws IOException {
    final var cfg = GlobalConfiguration.findByKey(settingName);
    if (cfg != null) {
      final var oldValue = cfg.getValue();

      cfg.setValue(settingValue);

      iResponse.send(
          HttpUtils.STATUS_OK_CODE,
          HttpUtils.STATUS_OK_DESCRIPTION,
          HttpUtils.CONTENT_TEXT_PLAIN,
          "Server global configuration '"
              + settingName
              + "' update successfully. Old value was '"
              + oldValue
              + "', new value is '"
              + settingValue
              + "'",
          null);
    } else {
      iResponse.send(
          HttpUtils.STATUS_BADREQ_CODE,
          HttpUtils.STATUS_BADREQ_DESCRIPTION,
          HttpUtils.CONTENT_TEXT_PLAIN,
          "Server global configuration '" + settingName + "' is invalid",
          null);
    }
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
