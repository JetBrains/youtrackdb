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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.put;

import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAuthenticatedServerAbstract;

public class ServerCommandPostConnection extends ServerCommandAuthenticatedServerAbstract {

  private static final String[] NAMES = {"POST|connection/*"};

  public ServerCommandPostConnection() {
    super("server.connection");
  }

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    final var urlParts =
        checkSyntax(iRequest.getUrl(), 3, "Syntax error: connection/<command>/<id>");

    iRequest.getData().commandInfo = "Interrupt command";
    iRequest.getData().commandDetail = urlParts[1];

    if ("KILL".equalsIgnoreCase(urlParts[1])) {
      server.getClientConnectionManager().kill(Integer.parseInt(urlParts[2]));
    } else if ("INTERRUPT".equalsIgnoreCase(urlParts[1])) {
      server.getClientConnectionManager().interrupt(Integer.parseInt(urlParts[2]));
    } else {
      throw new IllegalArgumentException(
          "Connection command '" + urlParts[1] + "' is unknown. Supported are: kill, interrupt");
    }

    iResponse.send(
        HttpUtils.STATUS_OK_NOCONTENT_CODE,
        HttpUtils.STATUS_OK_NOCONTENT_DESCRIPTION,
        HttpUtils.CONTENT_TEXT_PLAIN,
        null,
        null);
    return false;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
