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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get;

import com.jetbrains.youtrack.db.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;

public class ServerCommandGetServerVersion extends ServerCommandGetConnections {

  private static final String[] NAMES = {"GET|server/version"};

  public ServerCommandGetServerVersion() {
    super("server.info");
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }

  @Override
  public boolean authenticate(
      final HttpRequest iRequest,
      final HttpResponse iResponse,
      final boolean iAskForAuthentication) {
    // return always true, as authentication is not needed
    return true;
  }

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    checkSyntax(iRequest.getUrl(), 1, "Syntax error: server");

    iRequest.getData().commandInfo = "Server status";

    final var result = YouTrackDBConstants.getRawVersion();

    iResponse.send(
        HttpUtils.STATUS_OK_CODE,
        HttpUtils.STATUS_OK_DESCRIPTION,
        HttpUtils.CONTENT_JSON,
        result,
        null);

    return false;
  }
}
