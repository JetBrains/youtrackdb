package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import org.junit.Test;

public class RemoteConnectWrongUrlTest {

  @Test(expected = DatabaseException.class)
  public void testConnectWrongUrl() {
    try (var remote = YourTracks.remote("remote:wrong:2424", "root", "root")) {
      try (var session = remote.open("test", "admin", "admin")) {
        // do nothing
      }
    }
  }
}
