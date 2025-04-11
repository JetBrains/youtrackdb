package com.jetbrains.youtrack.db.internal.server.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.Test;

public class HttpDisabledTokenTest extends BaseHttpDatabaseTest {

  protected String getServerCfg() {
    return "/com/jetbrains/youtrack/db/internal/server/network/youtrackdb-server-config-httponly-notoken.xml";
  }

  @Test
  public void testTokenRequest() throws IOException {
    var request = new HttpPost(getBaseURL() + "/token/" + getDatabaseName());
    request.setEntity(new StringEntity("grant_type=password&username=admin&password=admin"));
    final var httpClient = HttpClients.createDefault();
    var response = httpClient.execute(request);
    assertEquals(response.getReasonPhrase(), response.getCode(), 400);
    var entity = response.getEntity();
    var out = new ByteArrayOutputStream();
    entity.writeTo(out);
    assertTrue(out.toString().contains("unsupported_grant_type"));
  }

  @Override
  protected String getDatabaseName() {
    return "token_test";
  }
}
