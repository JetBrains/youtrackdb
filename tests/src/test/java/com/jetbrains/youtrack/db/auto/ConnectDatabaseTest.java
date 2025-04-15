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
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseDocumentTx;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import org.testng.Assert;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test(enabled = false)
public class ConnectDatabaseTest {

  private final String url;
  private final String databaseName;

  @Parameters(value = "url")
  public ConnectDatabaseTest(@Optional String iURL) {
    if (iURL == null) {
      url = "remote:xxx/GratefulDeadConcerts";
    } else {
      url = iURL;
    }

    if (url.contains("/")) {
      databaseName = url.substring(url.lastIndexOf('/') + 1);
    } else {
      databaseName = url.substring(url.lastIndexOf(':') + 1);
    }
  }

  public void connectWithDNS() throws IOException {
    if (!url.startsWith("remote:") || !isInternetAvailable()) {
      return;
    }

    GlobalConfiguration.NETWORK_BINARY_DNS_LOADBALANCING_ENABLED.setValue(true);
    try {
      final DatabaseSessionInternal database =
          new DatabaseDocumentTx("remote:orientechnologies.com/" + databaseName);
      database.open("admin", "admin");
      Assert.assertFalse(database.isClosed());
      database.close();
    } finally {
      GlobalConfiguration.NETWORK_BINARY_DNS_LOADBALANCING_ENABLED.setValue(false);
    }
  }

  protected boolean isInternetAvailable() {
    try {
      final var url = new URL("http://orientdb.com");
      final var urlConn = (HttpURLConnection) url.openConnection();
      urlConn.setConnectTimeout(1000 * 10); // mTimeout is in seconds
      urlConn.connect();
      if (urlConn.getResponseCode() == HttpURLConnection.HTTP_OK) {
        return true;
      }
    } catch (final MalformedURLException e1) {
    } catch (final IOException e) {
    }
    return false;
  }
}
