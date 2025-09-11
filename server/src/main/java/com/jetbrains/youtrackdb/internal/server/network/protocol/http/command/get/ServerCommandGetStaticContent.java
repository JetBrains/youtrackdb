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

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.io.YTDBIOUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.CallableFunction;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrackdb.internal.tools.config.ServerCommandConfiguration;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerCommandGetStaticContent extends ServerCommandConfigurableAbstract {

  private static final Logger logger = LoggerFactory.getLogger(ServerCommandGetStaticContent.class);

  private static final String[] DEF_PATTERN = {
      "GET|www",
      "GET|studio/",
      "GET|",
      "GET|*.htm",
      "GET|*.html",
      "GET|*.xml",
      "GET|*.jpeg",
      "GET|*.jpg",
      "GET|*.png",
      "GET|*.gif",
      "GET|*.js",
      "GET|*.otf",
      "GET|*.css",
      "GET|*.swf",
      "GET|favicon.ico",
      "GET|robots.txt"
  };

  private static final String CONFIG_HTTP_CACHE = "http.cache:";
  private static final String CONFIG_ROOT_PATH = "root.path";
  private static final String CONFIG_FILE_PATH = "file.path";

  private final ConcurrentHashMap<String, StaticContentCachedEntry> cacheContents =
      new ConcurrentHashMap<String, StaticContentCachedEntry>();
  private final Map<String, String> cacheHttp = new HashMap<String, String>();
  private String cacheHttpDefault = "Cache-Control: max-age=3000";
  private String rootPath;
  private String filePath;
  private final ConcurrentHashMap<String, CallableFunction<Object, String>> virtualFolders =
      new ConcurrentHashMap<String, CallableFunction<Object, String>>();

  public static class StaticContent {

    public InputStream is = null;
    public long contentSize = 0;
    public String type = null;
  }

  public ServerCommandGetStaticContent() {
    super(DEF_PATTERN);
  }

  public ServerCommandGetStaticContent(final ServerCommandConfiguration iConfiguration) {
    super(iConfiguration.pattern);

    // LOAD HTTP CACHE CONFIGURATION
    for (var par : iConfiguration.parameters) {
      if (par.name.startsWith(CONFIG_HTTP_CACHE)) {
        final var filter = par.name.substring(CONFIG_HTTP_CACHE.length());
        if (filter.equalsIgnoreCase("default")) {
          cacheHttpDefault = par.value;
        } else if (filter.length() > 0) {
          final var filters = filter.split(" ");
          for (var f : filters) {
            cacheHttp.put(f, par.value);
          }
        }
      } else if (par.name.startsWith(CONFIG_ROOT_PATH)) {
        rootPath = par.value;
      } else if (par.name.startsWith(CONFIG_FILE_PATH)) {
        filePath = par.value;
      }
    }
  }

  @Override
  public boolean beforeExecute(HttpRequest iRequest, HttpResponse iResponse) throws IOException {
    var header = cacheHttpDefault;

    if (cacheHttp.size() > 0) {
      final var resource = getResource(iRequest);

      // SEARCH IN CACHE IF ANY
      for (var entry : cacheHttp.entrySet()) {
        final var wildcardPos = entry.getKey().indexOf('*');
        final var partLeft = entry.getKey().substring(0, wildcardPos);
        final var partRight = entry.getKey().substring(wildcardPos + 1);

        if (resource.startsWith(partLeft) && resource.endsWith(partRight)) {
          // FOUND
          header = entry.getValue();
          break;
        }
      }
    }

    iResponse.setHeader(header);
    return true;
  }

  @Override
  public boolean execute(final HttpRequest iRequest, final HttpResponse iResponse)
      throws Exception {
    iRequest.getData().commandInfo = "Get static content";
    iRequest.getData().commandDetail = iRequest.getUrl();

    StaticContent staticContent = null;
    try {
      staticContent = getVirtualFolderContent(iRequest);

      if (staticContent == null) {
        staticContent = new StaticContent();
        loadStaticContent(iRequest, iResponse, staticContent);
      }

      if (staticContent.is != null && staticContent.contentSize < 0) {
        var bytesOutput = new ByteArrayOutputStream();
        var stream = new GZIPOutputStream(bytesOutput, 16384);
        try {
          YTDBIOUtils.copyStream(staticContent.is, stream);
          stream.finish();
          var compressedBytes = bytesOutput.toByteArray();
          iResponse.sendStream(
              HttpUtils.STATUS_OK_CODE,
              HttpUtils.STATUS_OK_DESCRIPTION,
              staticContent.type,
              new ByteArrayInputStream(compressedBytes),
              compressedBytes.length,
              null,
              new HashMap<String, String>() {
                {
                  put("Content-Encoding", "gzip");
                }
              });
        } finally {
          stream.close();
          bytesOutput.close();
        }
      } else if (staticContent.is != null) {
        iResponse.sendStream(
            HttpUtils.STATUS_OK_CODE,
            HttpUtils.STATUS_OK_DESCRIPTION,
            staticContent.type,
            staticContent.is,
            staticContent.contentSize);
      } else {
        iResponse.sendStream(404, "File not found", null, null, 0);
      }

    } catch (IOException e) {
      LogManager.instance().error(this, "Error on loading resource %s", e, iRequest.getUrl());

    } finally {
      if (staticContent != null && staticContent.is != null) {
        try {
          staticContent.is.close();
        } catch (IOException e) {
          LogManager.instance().warn(this, "Error on closing file", e);
        }
      }
    }
    return false;
  }

  public void registerVirtualFolder(final String iName,
      final CallableFunction<Object, String> iCallback) {
    virtualFolders.put(iName, iCallback);
  }

  public void unregisterVirtualFolder(final String iName) {
    virtualFolders.remove(iName);
  }

  protected String getResource(final HttpRequest iRequest) {
    final String url;
    if (HttpUtils.URL_SEPARATOR.equals(iRequest.getUrl())) {
      url = "/www/index.htm";
    } else {
      var pos = iRequest.getUrl().indexOf('?');
      if (pos > -1) {
        url = iRequest.getUrl().substring(0, pos);
      } else {
        url = iRequest.getUrl();
      }
    }
    return url;
  }

  @Nullable
  protected StaticContent getVirtualFolderContent(final HttpRequest iRequest) {
    if (iRequest.getUrl() != null) {
      final var beginPos = iRequest.getUrl().startsWith("/") ? 1 : 0;
      final var endPos = iRequest.getUrl().indexOf('/', beginPos);
      final var firstFolderName =
          endPos > -1
              ? iRequest.getUrl().substring(beginPos, endPos)
              : iRequest.getUrl().substring(beginPos);
      final var virtualFolderCallback = virtualFolders.get(
          firstFolderName);
      if (virtualFolderCallback != null) {
        // DELEGATE TO THE CALLBACK
        final var content =
            virtualFolderCallback.call(endPos > -1 ? iRequest.getUrl().substring(endPos + 1) : "");
        if (content == null) {
          return null;
        }

        if (content instanceof StaticContent) {
          return (StaticContent) content;
        } else if (content instanceof String contentString) {
          final var sc = new StaticContent();
          sc.is = new ByteArrayInputStream(contentString.getBytes());
          sc.contentSize = contentString.length();
          sc.type = "text/html";
        }
      }
    }
    return null;
  }

  private void loadStaticContent(
      final HttpRequest iRequest,
      final HttpResponse iResponse,
      final StaticContent staticContent)
      throws IOException {
    if (filePath == null && rootPath == null) {
      // GET GLOBAL CONFIG
      rootPath = iRequest.getConfiguration().getValueAsString("orientdb.www.path", "src/site");
      if (rootPath == null) {
        LogManager.instance()
            .warn(
                this,
                "No path configured. Specify the 'root.path', 'file.path' or the global"
                    + " 'orientdb.www.path' variable",
                rootPath);
        return;
      }
    }

    if (filePath == null) {
      // CHECK DIRECTORY
      final var wwwPathDirectory = new File(rootPath);
      if (!wwwPathDirectory.exists()) {
        LogManager.instance()
            .warn(this, "path variable points to '%s' but it doesn't exists", rootPath);
      }
      if (!wwwPathDirectory.isDirectory()) {
        LogManager.instance()
            .warn(this, "path variable points to '%s' but it isn't a directory", rootPath);
      }
    }

    String path;
    if (filePath != null)
    // SINGLE FILE
    {
      path = filePath;
    } else {
      // GET FROM A DIRECTORY
      final var url = getResource(iRequest);
      if (url.startsWith("/www")) {
        path = rootPath + url.substring("/www".length());
      } else {
        path = rootPath + url;
      }
    }

    path = URLDecoder.decode(path, StandardCharsets.UTF_8);

    if (path.contains("..")) {
      iResponse.sendStream(404, "File not found", null, null, 0);
      return;
    }

    if (server
        .getContextConfiguration()
        .getValueAsBoolean(GlobalConfiguration.SERVER_CACHE_FILE_STATIC)) {
      final var cachedEntry = cacheContents.get(path);
      if (cachedEntry != null) {
        staticContent.is = new ByteArrayInputStream(cachedEntry.content);
        staticContent.contentSize = cachedEntry.size;
        staticContent.type = cachedEntry.type;
      }
    }

    if (staticContent.is == null) {
      var inputFile = new File(path);
      if (!inputFile.exists()) {
        LogManager.instance().debug(this, "Static resource not found: %s", logger, path);

        iResponse.sendStream(404, "File not found", null, null, 0);
        return;
      }

      if (filePath == null && inputFile.isDirectory()) {
        inputFile = new File(path + "/index.htm");
        if (inputFile.exists()) {
          path = path + "/index.htm";
        } else {
          inputFile = new File(path + "/index.html");
          if (inputFile.exists()) {
            path = path + "/index.html";
          }
        }
      }

      staticContent.type = getContentType(path);

      staticContent.is = new BufferedInputStream(new FileInputStream(inputFile));
      staticContent.contentSize = inputFile.length();

      if (server
          .getContextConfiguration()
          .getValueAsBoolean(GlobalConfiguration.SERVER_CACHE_FILE_STATIC)) {
        // READ THE ENTIRE STREAM AND CACHE IT IN MEMORY
        final var buffer = new byte[(int) staticContent.contentSize];
        for (var i = 0; i < staticContent.contentSize; ++i) {
          buffer[i] = (byte) staticContent.is.read();
        }

        var cachedEntry = new StaticContentCachedEntry();
        cachedEntry.content = buffer;
        cachedEntry.size = staticContent.contentSize;
        cachedEntry.type = staticContent.type;

        cacheContents.put(path, cachedEntry);

        staticContent.is = new ByteArrayInputStream(cachedEntry.content);
      }
    }
  }

  public static String getContentType(final String path) {
    if (path.endsWith(".htm") || path.endsWith(".html")) {
      return "text/html";
    } else if (path.endsWith(".png")) {
      return "image/png";
    } else if (path.endsWith(".jpeg")) {
      return "image/jpeg";
    } else if (path.endsWith(".js")) {
      return "application/x-javascript";
    } else if (path.endsWith(".css")) {
      return "text/css";
    } else if (path.endsWith(".ico")) {
      return "image/x-icon";
    } else if (path.endsWith(".otf")) {
      return "font/opentype";
    }

    return "text/plain";
  }
}
