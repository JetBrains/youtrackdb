package com.jetbrains.youtrack.db.internal.server.http;

import com.jetbrains.youtrack.db.internal.common.io.IOUtils;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.NetworkProtocolHttpAbstract;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetStaticContent;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.get.ServerCommandGetStaticContent.StaticContent;
import java.io.BufferedInputStream;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests HTTP "static content" command.
 */
public class HttpGetStaticContentTest extends BaseHttpTest {

  @Before
  public void setupFolder() {
    registerFakeVirtualFolder();
  }

  public void registerFakeVirtualFolder() {
    CallableFunction callableFunction =
        new CallableFunction<Object, String>() {
          @Override
          public Object call(final String iArgument) {
            var classLoader = Thread.currentThread().getContextClassLoader();
            final var url = classLoader.getResource(iArgument);

            if (url != null) {
              final var content =
                  new StaticContent();
              content.is = new BufferedInputStream(classLoader.getResourceAsStream(iArgument));
              content.contentSize = -1;
              content.type = ServerCommandGetStaticContent.getContentType(url.getFile());
              return content;
            }
            return null;
          }
        };
    final var httpListener =
        getServer().getListenerByProtocol(NetworkProtocolHttpAbstract.class);
    final var command =
        (ServerCommandGetStaticContent)
            httpListener.getCommand(ServerCommandGetStaticContent.class);
    command.registerVirtualFolder("fake", callableFunction);
  }

  @Test
  public void testIndexHTML() throws Exception {
    var response = get("fake/index.htm").getResponse();
    Assert.assertEquals(200, response.getCode());

    var expected =
        IOUtils.readStreamAsString(
            this.getClass().getClassLoader().getResourceAsStream("index.htm"));
    var actual = IOUtils.readStreamAsString(response.getEntity().getContent());
    Assert.assertEquals(expected, actual);
  }

  @Override
  public String getDatabaseName() {
    return "httpdb";
  }

  @Before
  public void startServer() throws Exception {
    super.startServer();
  }

  @After
  public void stopServer() throws Exception {
    super.stopServer();
  }
}
