package com.jetbrains.youtrack.db.internal.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.jetbrains.youtrack.db.internal.core.metadata.security.Token;
import com.jetbrains.youtrack.db.internal.core.security.ParsedToken;
import com.jetbrains.youtrack.db.internal.server.network.protocol.binary.NetworkProtocolBinary;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class ClientConnectionManagerTest {

  @Mock
  private NetworkProtocolBinary protocol;

  @Mock
  private Token token;

  @Mock
  private TokenHandler handler;

  @Mock
  private YouTrackDBServer server;

  @Before
  public void before() throws NoSuchAlgorithmException, InvalidKeyException, IOException {
    MockitoAnnotations.initMocks(this);
    Mockito.when(handler.parseBinaryToken(Mockito.any(byte[].class))).thenReturn(token);
    Mockito.when(handler.validateBinaryToken(Mockito.any(Token.class))).thenReturn(true);
    Mockito.when(handler.validateBinaryToken(Mockito.any(ParsedToken.class))).thenReturn(true);
    Mockito.when(protocol.getServer()).thenReturn(server);
    Mockito.when(server.getTokenHandler()).thenReturn(handler);
  }

  @Test
  public void testSimpleConnectDisconnect() throws IOException {
    var manager = new ClientConnectionManager(server);
    var ret = manager.connect(protocol);
    assertNotNull(ret);
    var ret1 = manager.getConnection(ret.getId(), protocol);
    assertSame(ret, ret1);
    manager.disconnect(ret);

    var ret2 = manager.getConnection(ret.getId(), protocol);
    assertNull(ret2);
  }

  @Test
  @Ignore
  public void testTokenConnectDisconnect() throws IOException {
    var atoken = new byte[]{};

    var manager = new ClientConnectionManager(server);
    var ret = manager.connect(protocol);
    manager.connect(protocol, ret, atoken);
    assertNotNull(ret);
    var sess = manager.getSession(ret);
    assertNotNull(sess);
    assertEquals(sess.getConnections().size(), 1);
    var ret1 = manager.getConnection(ret.getId(), protocol);
    assertSame(ret, ret1);
    var ret2 = manager.reConnect(protocol, atoken);
    assertNotSame(ret1, ret2);
    assertEquals(sess.getConnections().size(), 2);
    manager.disconnect(ret);

    assertEquals(sess.getConnections().size(), 1);
    var ret3 = manager.getConnection(ret.getId(), protocol);
    assertNull(ret3);

    manager.disconnect(ret2);
    assertEquals(sess.getConnections().size(), 0);
    var ret4 = manager.getConnection(ret2.getId(), protocol);
    assertNull(ret4);
  }
}
