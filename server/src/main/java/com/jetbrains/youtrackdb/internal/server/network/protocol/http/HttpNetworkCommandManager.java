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

package com.jetbrains.youtrackdb.internal.server.network.protocol.http;

import com.jetbrains.youtrackdb.internal.common.util.PatternConst;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.ServerCommand;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

public class HttpNetworkCommandManager {

  private static final String URL_PART_PATTERN = "([a-zA-Z0-9%:\\\\+]*)";

  private final Map<String, ServerCommand> exactCommands =
      new ConcurrentHashMap<String, ServerCommand>();
  private final Map<String, ServerCommand> wildcardCommands =
      new ConcurrentHashMap<String, ServerCommand>();
  private final Map<String, ServerCommand> restCommands =
      new ConcurrentHashMap<String, ServerCommand>();
  private final HttpNetworkCommandManager parent;
  private final YouTrackDBServer server;

  public HttpNetworkCommandManager(
      final YouTrackDBServer iServer, final HttpNetworkCommandManager iParent) {
    server = iServer;
    parent = iParent;
  }

  public Object getCommand(final String iName) {
    var cmd = exactCommands.get(iName);

    if (cmd == null) {
      for (var entry : restCommands.entrySet()) {
        if (matches(entry.getKey(), iName)) {
          return entry.getValue();
        }
      }
    }
    if (cmd == null) {
      // TRY WITH WILDCARD COMMANDS
      // TODO: OPTIMIZE SEARCH!
      String partLeft;
      String partRight;
      for (var entry : wildcardCommands.entrySet()) {
        final var wildcardPos = entry.getKey().indexOf('*');
        partLeft = entry.getKey().substring(0, wildcardPos);
        partRight = entry.getKey().substring(wildcardPos + 1);

        if (iName.startsWith(partLeft) && iName.endsWith(partRight)) {
          cmd = entry.getValue();
          break;
        }
      }
    }

    if (cmd == null && parent != null) {
      cmd = (ServerCommand) parent.getCommand(iName);
    }

    return cmd;
  }

  /**
   * Register all the names for the same instance.
   *
   * @param iServerCommandInstance
   */
  public void registerCommand(final ServerCommand iServerCommandInstance) {
    for (var name : iServerCommandInstance.getNames()) {
      if (StringSerializerHelper.contains(name, '{')) {
        restCommands.put(name, iServerCommandInstance);
      } else if (StringSerializerHelper.contains(name, '*')) {
        wildcardCommands.put(name, iServerCommandInstance);
      } else {
        exactCommands.put(name, iServerCommandInstance);
      }
    }
    iServerCommandInstance.configure(server);
  }

  public Map<String, String> extractUrlTokens(String requestUrl) {
    Map<String, String> result = new HashMap<String, String>();
    var urlPattern = findUrlPattern(requestUrl);
    if (urlPattern == null) {
      return result;
    }
    var matcherUrl =
        PatternConst.PATTERN_REST_URL.matcher(urlPattern).replaceAll(URL_PART_PATTERN);

    matcherUrl = matcherUrl.substring(matcherUrl.indexOf('|') + 1);
    requestUrl = requestUrl.substring(requestUrl.indexOf('|') + 1);

    var pattern = Pattern.compile(matcherUrl);
    var matcher = pattern.matcher(requestUrl);
    if (matcher.find()) {
      var templateMatcher = PatternConst.PATTERN_REST_URL.matcher(urlPattern);
      var i = 1;
      String key;
      while (templateMatcher.find()) {
        key = templateMatcher.group();
        key = key.substring(1);
        key = key.substring(0, key.length() - 1);
        var value = matcher.group(i++);
        result.put(key, value);
      }
    }
    return result;
  }

  @Nullable
  protected String findUrlPattern(String requestUrl) {
    for (var entry : restCommands.entrySet()) {
      if (matches(entry.getKey(), requestUrl)) {
        return entry.getKey();
      }
    }
    if (parent == null) {
      return null;
    } else {
      return parent.findUrlPattern(requestUrl);
    }
  }

  private boolean matches(String urlPattern, String requestUrl) {
    var matcherUrl =
        PatternConst.PATTERN_REST_URL.matcher(urlPattern).replaceAll(URL_PART_PATTERN);

    if (!matcherUrl
        .substring(0, matcherUrl.indexOf('|') + 1)
        .equals(requestUrl.substring(0, requestUrl.indexOf('|') + 1))) {
      return false;
    }
    matcherUrl = matcherUrl.substring(matcherUrl.indexOf('|') + 1);
    requestUrl = requestUrl.substring(requestUrl.indexOf('|') + 1);
    return requestUrl.matches(matcherUrl);
  }
}
