/*
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

package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get;

import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.OHttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAuthenticatedServerAbstract;
import java.io.IOException;

/**
 * This command is called in order to know if the running instance of orientdb is EE or not.
 */
public class ServerCommandIsEnterprise extends ServerCommandAuthenticatedServerAbstract {

  private static final String[] NAMES = {"GET|isEE"};

  public ServerCommandIsEnterprise() {
    super("server.listDatabases");
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }

  @Override
  public boolean execute(OHttpRequest iRequest, HttpResponse iResponse) throws Exception {

    final String[] parts = checkSyntax(iRequest.getUrl(), 1, "Syntax error: isEE");

    if ("GET".equalsIgnoreCase(iRequest.getHttpMethod())) {
      doGet(iRequest, iResponse, parts);
    }

    return false;
  }

  private void doGet(OHttpRequest iRequest, HttpResponse iResponse, String[] parts)
      throws IOException {

    if ("isEE".equalsIgnoreCase(parts[0])) {

      iResponse.send(
          HttpUtils.STATUS_OK_CODE,
          "OK",
          HttpUtils.CONTENT_JSON,
          "{\"enterprise\": true}",
          null);

    } else {
      throw new IllegalArgumentException("");
    }
  }
}
