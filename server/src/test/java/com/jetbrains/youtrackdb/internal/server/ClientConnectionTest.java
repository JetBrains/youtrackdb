package com.jetbrains.youtrackdb.internal.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.enterprise.channel.binary.TokenSecurityException;
import com.jetbrains.youtrackdb.internal.server.network.protocol.binary.NetworkProtocolBinary;
import com.jetbrains.youtrackdb.internal.server.token.TokenHandlerImpl;
import java.io.IOException;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 *
 */
public class ClientConnectionTest extends BaseMemoryInternalDatabase {

  @Mock
  private NetworkProtocolBinary protocol;

  @Mock
  private NetworkProtocolBinary protocol1;

  @Mock
  private ClientConnectionManager manager;

  @Mock
  private YouTrackDBServer server;

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    MockitoAnnotations.initMocks(this);
    Mockito.when(protocol.getServer()).thenReturn(server);
    Mockito.when(server.getClientConnectionManager()).thenReturn(manager);
    Mockito.when(server.getContextConfiguration()).thenReturn(new ContextConfiguration());
  }

  @Test
  public void testValidToken() throws IOException {
    var conn = new ClientConnection(1, protocol);
    TokenHandler handler = new TokenHandlerImpl(server.getContextConfiguration());
    var tokenBytes = handler.getSignedBinaryToken(session, session.getCurrentUser(),
        conn.getData());

    conn.validateSession(tokenBytes, handler, null);
    assertTrue(conn.getTokenBased());
    assertArrayEquals(tokenBytes, conn.getTokenBytes());
    assertNotNull(conn.getToken());
  }

  @Test(expected = TokenSecurityException.class)
  public void testExpiredToken() throws IOException, InterruptedException {
    var conn = new ClientConnection(1, protocol);
    var sessionTimeout = GlobalConfiguration.NETWORK_TOKEN_EXPIRE_TIMEOUT.getValueAsLong();
    GlobalConfiguration.NETWORK_TOKEN_EXPIRE_TIMEOUT.setValue(0);
    TokenHandler handler = new TokenHandlerImpl(server.getContextConfiguration());
    GlobalConfiguration.NETWORK_TOKEN_EXPIRE_TIMEOUT.setValue(sessionTimeout);
    var tokenBytes = handler.getSignedBinaryToken(session, session.getCurrentUser(),
        conn.getData());
    Thread.sleep(1);
    conn.validateSession(tokenBytes, handler, protocol);
  }

  @Test(expected = TokenSecurityException.class)
  public void testWrongToken() throws IOException {
    var conn = new ClientConnection(1, protocol);
    TokenHandler handler = new TokenHandlerImpl(server.getContextConfiguration());
    var tokenBytes = new byte[120];
    conn.validateSession(tokenBytes, handler, protocol);
  }

  @Test
  public void testAlreadyAuthenticatedOnConnection() throws IOException {
    var conn = new ClientConnection(1, protocol);
    TokenHandler handler = new TokenHandlerImpl(server.getContextConfiguration());
    var tokenBytes = handler.getSignedBinaryToken(session, session.getCurrentUser(),
        conn.getData());
    conn.validateSession(tokenBytes, handler, protocol);
    assertTrue(conn.getTokenBased());
    assertArrayEquals(tokenBytes, conn.getTokenBytes());
    assertNotNull(conn.getToken());
    // second validation don't need token
    conn.validateSession(null, handler, protocol);
    assertTrue(conn.getTokenBased());
    assertEquals(tokenBytes, conn.getTokenBytes());
    assertNotNull(conn.getToken());
  }

  @Test(expected = TokenSecurityException.class)
  public void testNotAlreadyAuthenticated() throws IOException {
    var conn = new ClientConnection(1, protocol);
    TokenHandler handler = new TokenHandlerImpl(server.getContextConfiguration());
    // second validation don't need token
    conn.validateSession(null, handler, protocol1);
  }

  @Test(expected = TokenSecurityException.class)
  public void testAlreadyAuthenticatedButNotOnSpecificConnection() throws IOException {
    var conn = new ClientConnection(1, protocol);
    TokenHandler handler = new TokenHandlerImpl(server.getContextConfiguration());
    var tokenBytes = handler.getSignedBinaryToken(session, session.getCurrentUser(),
        conn.getData());
    conn.validateSession(tokenBytes, handler, protocol);
    assertTrue(conn.getTokenBased());
    assertArrayEquals(tokenBytes, conn.getTokenBytes());
    assertNotNull(conn.getToken());
    // second validation don't need token
    var otherConn = Mockito.mock(NetworkProtocolBinary.class);
    conn.validateSession(null, handler, otherConn);
  }
}
