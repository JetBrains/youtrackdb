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

import com.jetbrains.youtrack.db.internal.core.serialization.serializer.JSONWriter;
import com.jetbrains.youtrack.db.internal.server.ServerInfo;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAuthenticatedServerAbstract;
import java.io.StringWriter;

public class ServerCommandGetConnections extends ServerCommandAuthenticatedServerAbstract {

  private static final String[] NAMES = {"GET|connections/*"};

  public ServerCommandGetConnections() {
    super("server.connections");
  }

  public ServerCommandGetConnections(final String iName) {
    super(iName);
  }

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    final var args =
        checkSyntax(iRequest.getUrl(), 1, "Syntax error: connections[/<database>]");

    iRequest.getData().commandInfo = "Server status";

    final var jsonBuffer = new StringWriter();
    final var json = new JSONWriter(jsonBuffer);
    json.beginObject();

    final var databaseName = args.length > 1 && args[1].length() > 0 ? args[1] : null;

    ServerInfo.getConnections(server, json, databaseName);

    json.endObject();

    iResponse.send(
        HttpUtils.STATUS_OK_CODE,
        HttpUtils.STATUS_OK_DESCRIPTION,
        HttpUtils.CONTENT_JSON,
        jsonBuffer.toString(),
        null);

    return false;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
