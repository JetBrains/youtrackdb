/*
 *
 *
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.post;

import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.NetworkProtocolHttpAbstract;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.ServerCommandAuthenticatedDbAbstract;
import java.io.IOException;

public class ServerCommandPostKillDbConnection extends ServerCommandAuthenticatedDbAbstract {

  private static final String[] NAMES = {"POST|dbconnection/*"};

  @Override
  public boolean execute(HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    final var urlParts =
        checkSyntax(iRequest.getUrl(), 2, "Syntax error: dbconnection/<database>");

    doPost(iRequest, iResponse, urlParts[1], iRequest.getContent());

    return false;
  }

  private void doPost(HttpRequest iRequest, HttpResponse iResponse, String db, String command)
      throws IOException {

    final var connections =
        server.getClientConnectionManager().getConnections();
    for (var connection : connections) {
      if (connection.getProtocol() instanceof NetworkProtocolHttpAbstract http) {
        final var req = http.getRequest();

        if (req != null && req != iRequest && req.getSessionId().equals(iRequest.getSessionId())) {
          server.getClientConnectionManager().interrupt(connection.getId());
        }
      }
    }
    iResponse.send(
        HttpUtils.STATUS_OK_NOCONTENT_CODE,
        HttpUtils.STATUS_OK_NOCONTENT_DESCRIPTION,
        HttpUtils.CONTENT_TEXT_PLAIN,
        null,
        null);
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
