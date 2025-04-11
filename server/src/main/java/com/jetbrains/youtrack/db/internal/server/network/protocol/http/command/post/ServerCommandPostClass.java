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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post;

import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAuthenticatedDbAbstract;

public class ServerCommandPostClass extends ServerCommandAuthenticatedDbAbstract {

  private static final String[] NAMES = {"POST|class/*"};

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    var urlParts =
        checkSyntax(iRequest.getUrl(), 3, "Syntax error: class/<database>/<class-name>");

    iRequest.getData().commandInfo = "Create class";
    iRequest.getData().commandDetail = urlParts[2];

    try (var db = getProfiledDatabaseSessionInstance(iRequest)) {

      if (db.getMetadata().getSchema().getClass(urlParts[2]) != null) {
        throw new IllegalArgumentException("Class '" + urlParts[2] + "' already exists");
      }

      db.getMetadata().getSchema().createClass(urlParts[2]);

      iResponse.send(
          HttpUtils.STATUS_CREATED_CODE,
          HttpUtils.STATUS_CREATED_DESCRIPTION,
          HttpUtils.CONTENT_TEXT_PLAIN,
          db.getMetadata().getSchema().getClasses().size(),
          null);

    }
    return false;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
