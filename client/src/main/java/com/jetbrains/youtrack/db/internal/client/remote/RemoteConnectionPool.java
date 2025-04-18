package com.jetbrains.youtrack.db.internal.client.remote;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.internal.client.binary.SocketChannelBinaryAsynchClient;
import com.jetbrains.youtrack.db.internal.common.concur.resource.ResourcePool;
import com.jetbrains.youtrack.db.internal.common.concur.resource.ResourcePoolListener;
import com.jetbrains.youtrack.db.internal.common.io.YTIOException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.enterprise.channel.binary.ChannelBinaryProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class RemoteConnectionPool
    implements ResourcePoolListener<String, SocketChannelBinaryAsynchClient> {

  private static final Logger logger = LoggerFactory.getLogger(RemoteConnectionPool.class);

  private final ResourcePool<String, SocketChannelBinaryAsynchClient> pool;

  public RemoteConnectionPool(int iMaxResources) {
    pool = new ResourcePool<>(iMaxResources, this);
  }

  protected SocketChannelBinaryAsynchClient createNetworkConnection(
      String serverURL, final ContextConfiguration clientConfiguration) throws YTIOException {
    if (serverURL == null) {
      throw new IllegalArgumentException("server url is null");
    }

    // TRY WITH CURRENT URL IF ANY
    try {
      LogManager.instance().debug(this, "Trying to connect to the remote host %s...", logger,
          serverURL);

      var sepPos = serverURL.indexOf(':');
      final var remoteHost = serverURL.substring(0, sepPos);
      final var remotePort = Integer.parseInt(serverURL.substring(sepPos + 1));

      final var ch =
          new SocketChannelBinaryAsynchClient(
              remoteHost,
              remotePort,
              clientConfiguration,
              ChannelBinaryProtocol.CURRENT_PROTOCOL_VERSION);

      return ch;

    } catch (YTIOException e) {
      // RE-THROW IT
      throw e;
    } catch (Exception e) {
      LogManager.instance().debug(this, "Error on connecting to %s", logger, e, serverURL);
      throw BaseException.wrapException(new YTIOException("Error on connecting to " + serverURL),
          e, (String) null);
    }
  }

  @Override
  public SocketChannelBinaryAsynchClient createNewResource(
      final String iKey, final Object... iAdditionalArgs) {
    return createNetworkConnection(iKey, (ContextConfiguration) iAdditionalArgs[0]);
  }

  @Override
  public boolean reuseResource(
      final String iKey, final Object[] iAdditionalArgs,
      final SocketChannelBinaryAsynchClient iValue) {
    final var canReuse = iValue.isConnected();
    if (!canReuse)
    // CANNOT REUSE: CLOSE IT PROPERLY
    {
      try {
        iValue.close();
      } catch (Exception e) {
        LogManager.instance().debug(this, "Error on closing socket connection", logger, e);
      }
    }
    iValue.markInUse();
    return canReuse;
  }

  public ResourcePool<String, SocketChannelBinaryAsynchClient> getPool() {
    return pool;
  }

  public SocketChannelBinaryAsynchClient acquire(
      final String iServerURL,
      final long timeout,
      final ContextConfiguration clientConfiguration) {
    return pool.getResource(iServerURL, timeout, clientConfiguration);
  }

  public void checkIdle(long timeout) {
    for (var resource : pool.getResources()) {
      if (!resource.isInUse() && resource.getLastUse() + timeout < System.currentTimeMillis()) {
        resource.close();
      }
    }
  }
}
