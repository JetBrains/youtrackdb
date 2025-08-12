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
package com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.get;

import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.ServerCommandAbstract;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.ServerCommandAuthenticatedDbAbstract;
import java.io.IOException;

public class ServerCommandGetDisconnect extends ServerCommandAbstract {

  private static final String[] NAMES = {"GET|disconnect"};

  @Override
  public boolean beforeExecute(HttpRequest iRequest, HttpResponse iResponse) throws IOException {
    super.beforeExecute(iRequest, iResponse);
    return true;
  }

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    checkSyntax(iRequest.getUrl(), 1, "Syntax error: disconnect");

    iRequest.getData().commandInfo = "Disconnect";
    iRequest.getData().commandDetail = null;

    if (iRequest.getSessionId() != null) {
      server.getHttpSessionManager().removeSession(iRequest.getSessionId());
      iRequest.setSessionId(ServerCommandAuthenticatedDbAbstract.SESSIONID_UNAUTHORIZED);
      iResponse.setSessionId(iRequest.getSessionId());
    }

    iResponse.setKeepAlive(false);

    if (isJsonResponse(iResponse)) {
      sendJsonError(
          iResponse,
          HttpUtils.STATUS_AUTH_CODE,
          HttpUtils.STATUS_AUTH_DESCRIPTION,
          "Logged out",
          null);
    } else {
      iResponse.send(
          HttpUtils.STATUS_AUTH_CODE,
          HttpUtils.STATUS_AUTH_DESCRIPTION,
          HttpUtils.CONTENT_TEXT_PLAIN,
          "Logged out",
          null);
    }

    return false;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
