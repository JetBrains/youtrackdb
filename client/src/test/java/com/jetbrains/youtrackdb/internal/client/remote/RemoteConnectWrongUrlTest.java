package com.jetbrains.youtrackdb.internal.client.remote;

import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBRemoteImpl;
import org.junit.Test;

public class RemoteConnectWrongUrlTest {

  @Test(expected = DatabaseException.class)
  public void testConnectWrongUrl() {
    try (var remote = (YouTrackDBRemoteImpl) YouTrackDBRemoteImpl.remote("remote:wrong:2424",
        "root",
        "root")) {
      try (var session = remote.open("test", "admin", "admin")) {
        // do nothing
      }
    }
  }
}
