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

import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAuthenticatedDbAbstract;

public class ServerCommandGetStorageAllocation extends ServerCommandAuthenticatedDbAbstract {

  private static final String[] NAMES = {"GET|allocation/*"};

  @Override
  public boolean execute(final HttpRequest iRequest, final HttpResponse iResponse)
      throws Exception {
    var urlParts = checkSyntax(iRequest.getUrl(), 2, "Syntax error: allocation/<database>");

    iRequest.getData().commandInfo = "Storage allocation";
    iRequest.getData().commandDetail = urlParts[1];

    throw new IllegalArgumentException(
        "Cannot get allocation information for database '"
            + iRequest.getDatabaseName()
            + "' because it is not implemented yet.");
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
