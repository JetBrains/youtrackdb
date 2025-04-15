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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.delete;

import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAuthenticatedServerAbstract;

public class ServerCommandDeleteDatabase extends ServerCommandAuthenticatedServerAbstract {

  private static final String[] NAMES = {"DELETE|database/*"};

  public ServerCommandDeleteDatabase() {
    super("database.drop");
  }

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    var urlParts = checkSyntax(iRequest.getUrl(), 2, "Syntax error: database/<database>");

    iRequest.getData().commandInfo = "Drop database";
    iRequest.getData().commandDetail = urlParts[1];

    server.dropDatabase(urlParts[1]);

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
