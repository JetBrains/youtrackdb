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

import com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.ServerCommandAbstract;

public abstract class ServerCommandConfigurableAbstract extends ServerCommandAbstract {

  private final String[] pattern;

  public ServerCommandConfigurableAbstract(final String iPattern) {
    pattern = iPattern.split(" ");
  }

  public ServerCommandConfigurableAbstract(final String[] iPattern) {
    pattern = iPattern;
  }

  @Override
  public String[] getNames() {
    return pattern;
  }
}
