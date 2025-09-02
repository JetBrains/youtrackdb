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

import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.ServerCommandAuthenticatedServerAbstract;
import java.net.URL;

public class ServerCommandPostInstallDatabase extends ServerCommandAuthenticatedServerAbstract {

  private static final String[] NAMES = {"POST|installDatabase"};

  public ServerCommandPostInstallDatabase() {
    super("database.create");
  }

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    checkSyntax(iRequest.getUrl(), 1, "Syntax error: installDatabase");
    iRequest.getData().commandInfo = "Import database";
    try {
      final var url = iRequest.getContent();
      final var name = getDbName(url);
      if (name != null) {
        if (server.getContext().exists(name)) {
          throw new DatabaseException("Database named '" + name + "' already exists: ");
        } else {
          final var uri = new URL(url);
          final var conn = uri.openConnection();
          conn.setRequestProperty("User-Agent", "YouTrackDB-Studio");
          conn.setDefaultUseCaches(false);
          server
              .getDatabases()
              .networkRestore(
                  name,
                  conn.getInputStream(),
                  () -> {
                    //noinspection ReturnOfNull
                    return null;
                  });
          server.getDatabases().openNoAuthorization(name).close();

          iResponse.send(
              HttpUtils.STATUS_OK_CODE,
              HttpUtils.STATUS_OK_DESCRIPTION,
              HttpUtils.CONTENT_TEXT_PLAIN,
              null,
              null);
        }
      } else {
        throw new IllegalArgumentException("Could not find database name");
      }
    } catch (Exception e) {
      throw e;
    }
    return false;
  }

  protected String getDbName(final String url) {
    String name = null;
    if (url != null) {
      var idx = url.lastIndexOf('/');
      if (idx != -1) {
        name = url.substring(idx + 1).replace(".zip", "");
      }
    }
    return name;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
