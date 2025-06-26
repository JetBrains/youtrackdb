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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.options;

import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAbstract;

public class ServerCommandOptions extends ServerCommandAbstract {

  private static final String[] NAMES = {"OPTIONS|*"};

  public ServerCommandOptions() {
  }

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    iRequest.getData().commandInfo = "HTTP Options";
    iRequest.getData().commandDetail = iRequest.getUrl();

    iResponse.send(
        HttpUtils.STATUS_OK_CODE,
        HttpUtils.STATUS_OK_DESCRIPTION,
        HttpUtils.CONTENT_TEXT_PLAIN,
        null,
        "Access-Control-Allow-Methods: POST, GET, PUT, DELETE, OPTIONS\r\n"
            + "Access-Control-Max-Age: 1728000\r\n"
            + "Access-Control-Allow-Headers: if-modified-since, content-type, authorization,"
            + " x-requested-with");
    return false;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
