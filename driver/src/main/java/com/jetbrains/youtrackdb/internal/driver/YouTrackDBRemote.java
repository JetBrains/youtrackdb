package com.jetbrains.youtrackdb.internal.driver;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.core.gremlin.io.YTDBIoRegistry;
import com.jetbrains.youtrackdb.internal.remote.RemoteProtocolConstants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.Result;
import org.apache.tinkerpop.gremlin.process.traversal.AnonymousTraversalSource;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerIoRegistryV3;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.ser.AbstractMessageSerializer;
import org.apache.tinkerpop.gremlin.util.ser.GraphBinaryMessageSerializerV1;
import org.jspecify.annotations.NonNull;

public class YouTrackDBRemote implements YouTrackDB {

  private static final String PORT_CONFIGURATION = "port";
  private static final String USERNAME_CONFIGURATION = "username";
  private static final String PASSWORD_CONFIGURATION = "password";
  private static final String HOSTS_CONFIGURATION = "hosts";

  private final Cluster cluster;
  private final BaseConfiguration configuration;

  public static YouTrackDBRemote instance(@Nonnull String serverAddress,
      @Nonnull String username, @Nonnull String password) {
    return instance(serverAddress, 8182, username, password);
  }

  public static YouTrackDBRemote instance(@Nonnull String serverAddress, int serverPort,
      @Nonnull String username, @Nonnull String password) {
    var config = new BaseConfiguration();
    config.setProperty(PORT_CONFIGURATION, serverPort);
    config.setProperty(HOSTS_CONFIGURATION, List.of(serverAddress));

    config.setProperty(USERNAME_CONFIGURATION, username);
    config.setProperty(PASSWORD_CONFIGURATION, password);

    return createRemoteYTDBInstance(config);
  }

  public static YouTrackDBRemote instance(@Nonnull String serverAddress, int serverPort) {
    var config = new BaseConfiguration();
    config.setProperty(PORT_CONFIGURATION, serverPort);
    config.setProperty(HOSTS_CONFIGURATION, List.of(serverAddress));

    return createRemoteYTDBInstance(config);
  }

  private static @NonNull YouTrackDBRemote createRemoteYTDBInstance(
      BaseConfiguration configuration) {
    var cluster = createCluster(configuration);
    return new YouTrackDBRemote(configuration, cluster);
  }

  private static @NonNull Cluster createCluster(BaseConfiguration configuration) {
    var builder = Cluster.build(configuration);
    builder.channelizer(YTDBDriverWebSocketChannelizer.class);

    var graphBinarySerializer = new GraphBinaryMessageSerializerV1();
    var config = new HashMap<String, Object>();

    var ytdbIoRegistry = YTDBIoRegistry.class.getName();
    var tinkerGraphIoRegistry = TinkerIoRegistryV3.class.getName();

    var registries = new ArrayList<String>();
    registries.add(ytdbIoRegistry);
    registries.add(tinkerGraphIoRegistry);

    config.put(AbstractMessageSerializer.TOKEN_IO_REGISTRIES, registries);
    graphBinarySerializer.configure(config, null);

    var cluster = builder.serializer(graphBinarySerializer).create();
    return cluster;
  }

  public YouTrackDBRemote(@Nonnull BaseConfiguration configuration,
      @Nonnull final Cluster cluster) {
    this.cluster = cluster;
    this.configuration = configuration;
  }

  @Override
  public void create(@Nonnull String databaseName, @Nonnull DatabaseType type,
      String... userCredentials) {
    executeServerRequestNoResult(RemoteProtocolConstants.SERVER_COMMAND_CREATE_DATABASE, Map.of(
            RemoteProtocolConstants.DATABASE_NAME_PARAMETER, databaseName,
            RemoteProtocolConstants.DATABASE_TYPE_PARAMETER, type.name(),
            RemoteProtocolConstants.USER_CREDENTIALS_PARAMETER, Arrays.asList(userCredentials)
        )
    );
  }

  @Override
  public void create(@Nonnull String databaseName, @Nonnull DatabaseType type,
      @Nonnull Configuration youTrackDBConfig, String... userCredentials) {
    executeServerRequestNoResult(RemoteProtocolConstants.SERVER_COMMAND_CREATE_DATABASE,
        Map.of(
            RemoteProtocolConstants.DATABASE_NAME_PARAMETER, databaseName,
            RemoteProtocolConstants.DATABASE_TYPE_PARAMETER, type.name(),
            RemoteProtocolConstants.USER_CREDENTIALS_PARAMETER, Arrays.asList(userCredentials),
            RemoteProtocolConstants.CONFIGURATION_PARAMETER, convertConfigToMap(youTrackDBConfig)
        )
    );
  }

  @Override
  public void createIfNotExists(@Nonnull String databaseName, @Nonnull DatabaseType type,
      String... userCredentials) {
    executeServerRequestNoResult(
        RemoteProtocolConstants.SERVER_COMMAND_CREATE_DATABASE_IF_NOT_EXIST,
        Map.of(
            RemoteProtocolConstants.DATABASE_NAME_PARAMETER, databaseName,
            RemoteProtocolConstants.DATABASE_TYPE_PARAMETER, type.name(),
            RemoteProtocolConstants.USER_CREDENTIALS_PARAMETER, Arrays.asList(userCredentials)
        )
    );
  }

  @Override
  public void createIfNotExists(@Nonnull String databaseName, @Nonnull DatabaseType type,
      @Nonnull Configuration config, String... userCredentials) {
    executeServerRequestNoResult(
        RemoteProtocolConstants.SERVER_COMMAND_CREATE_DATABASE_IF_NOT_EXIST,
        Map.of(
            RemoteProtocolConstants.DATABASE_NAME_PARAMETER, databaseName,
            RemoteProtocolConstants.DATABASE_TYPE_PARAMETER, type.name(),
            RemoteProtocolConstants.USER_CREDENTIALS_PARAMETER, Arrays.asList(userCredentials),
            RemoteProtocolConstants.CONFIGURATION_PARAMETER, convertConfigToMap(config)
        )
    );
  }

  @Override
  public void restore(@Nonnull String databaseName,
      @Nonnull String path, @Nullable Configuration config) {
    executeServerRequestNoResult(RemoteProtocolConstants.SERVER_COMMAND_RESTORE, Map.of(
            RemoteProtocolConstants.DATABASE_NAME_PARAMETER, databaseName,
            RemoteProtocolConstants.BACKUP_PATH_PARAMETER, path,
            RemoteProtocolConstants.CONFIGURATION_PARAMETER, convertConfigToMap(config)
        )
    );
  }

  @Override
  public void createSystemUser(@Nonnull String username, @Nonnull String password,
      @Nonnull String... role) {
    executeServerRequestNoResult(RemoteProtocolConstants.SERVER_COMMAND_CREATE_SYSTEM_USER,
        Map.of(RemoteProtocolConstants.USER_NAME_PARAMETER, username,
            RemoteProtocolConstants.USER_PASSWORD_PARAMETER, password,
            RemoteProtocolConstants.USER_ROLES_PARAMETER, List.of(role)));
  }

  @Override
  public List<String> listSystemUsers() {
    return executeServerRequestWithListResult(
        RemoteProtocolConstants.SERVER_COMMAND_LIST_SYSTEM_USERS,
        Map.of(), Result::getString);
  }

  @Override
  public void dropSystemUser(@NonNull String username) {
    executeServerRequestNoResult(RemoteProtocolConstants.SERVER_COMMAND_DROP_SYSTEM_USER,
        Map.of(RemoteProtocolConstants.USER_NAME_PARAMETER, username));
  }

  @Override
  public void drop(@Nonnull String databaseName) {
    executeServerRequestNoResult(RemoteProtocolConstants.SERVER_COMMAND_DROP_DATABASE, Map.of(
        RemoteProtocolConstants.DATABASE_NAME_PARAMETER, databaseName));
  }

  @Override
  public boolean exists(@Nonnull String databaseName) {
    return executeServerRequestWithSingleResult(RemoteProtocolConstants.SERVER_COMMAND_EXISTS,
        Map.of(RemoteProtocolConstants.DATABASE_NAME_PARAMETER, databaseName)).getBoolean();
  }

  @Override
  public @Nonnull List<String> listDatabases() {
    var requestMessageBuilder = RequestMessage.build(
        RemoteProtocolConstants.SERVER_COMMAND_LIST_DATABASES);
    requestMessageBuilder.processor(RemoteProtocolConstants.PROCESSOR_NAME);

    try (var client = cluster.connect()) {
      return client.submitAsync(requestMessageBuilder.create()).get().stream().map(
          Result::getString).toList();
    } catch (InterruptedException e) {
      throw new RuntimeException("Server request processing was interrupted", e);
    } catch (ExecutionException e) {
      throw new RuntimeException("Server request processing failed", e);
    }
  }

  @Override
  public void close() {
    cluster.close();
  }

  @Override
  public boolean isOpen() {
    return !cluster.isClosed();
  }

  @Override
  public @NonNull YTDBGraphTraversalSource openTraversal(@NonNull String databaseName,
      @NonNull String userName, @NonNull String userPassword) {

    var newConfig = (BaseConfiguration) configuration.clone();

    newConfig.setProperty(USERNAME_CONFIGURATION, userName);
    newConfig.setProperty(PASSWORD_CONFIGURATION, userPassword);

    var cluster = createCluster(newConfig);
    var remoteConnection = new YTDBDriverRemoteConnection(cluster, true, databaseName);

    return AnonymousTraversalSource
        .traversal(YTDBGraphTraversalSource.class)
        .with(remoteConnection);
  }

  @Override
  public @NonNull YTDBGraphTraversalSource openTraversal(@NonNull String databaseName) {
    var remoteConnection = new YTDBDriverRemoteConnection(cluster, false, databaseName);

    return AnonymousTraversalSource
        .traversal(YTDBGraphTraversalSource.class)
        .with(remoteConnection);
  }

  private void executeServerRequestNoResult(String op, Map<String, Object> args) {
    var requestMessageBuilder = RequestMessage.build(op);
    requestMessageBuilder.processor(RemoteProtocolConstants.PROCESSOR_NAME);

    for (var entry : args.entrySet()) {
      requestMessageBuilder.addArg(entry.getKey(), entry.getValue());
    }
    try (var client = cluster.connect()) {
      var resultSet = client.submitAsync(requestMessageBuilder.create()).get();
      resultSet.all().get();
    } catch (InterruptedException e) {
      throw new RuntimeException("Server request processing was interrupted", e);
    } catch (ExecutionException e) {
      throw new RuntimeException("Server request processing failed", e);
    }
  }

  @SuppressWarnings("SameParameterValue")
  private Result executeServerRequestWithSingleResult(String op, Map<String, Object> args) {
    var requestMessageBuilder = RequestMessage.build(op);
    requestMessageBuilder.processor(RemoteProtocolConstants.PROCESSOR_NAME);
    for (var entry : args.entrySet()) {
      requestMessageBuilder.addArg(entry.getKey(), entry.getValue());
    }

    try (var client = cluster.connect()) {
      return client.submitAsync(requestMessageBuilder.create()).get().all().get().getFirst();
    } catch (InterruptedException e) {
      throw new RuntimeException("Server request processing was interrupted", e);
    } catch (ExecutionException e) {
      throw new RuntimeException("Server request processing failed", e);
    }
  }

  private <T> List<T> executeServerRequestWithListResult(String op, Map<String, Object> args,
      Function<Result, T> mapper) {
    var requestMessageBuilder = RequestMessage.build(op);
    requestMessageBuilder.processor(RemoteProtocolConstants.PROCESSOR_NAME);
    for (var entry : args.entrySet()) {
      requestMessageBuilder.addArg(entry.getKey(), entry.getValue());
    }

    try (var client = cluster.connect()) {
      return client.submitAsync(requestMessageBuilder.create()).get().all().get().stream()
          .map(mapper).toList();
    } catch (InterruptedException e) {
      throw new RuntimeException("Server request processing was interrupted", e);
    } catch (ExecutionException e) {
      throw new RuntimeException("Server request processing failed", e);
    }
  }


  private static Map<String, ?> convertConfigToMap(@Nonnull Configuration configuration) {
    var map = new HashMap<String, Object>();
    var keys = configuration.getKeys();

    while (keys.hasNext()) {
      map.put(keys.next(), configuration.getProperty(keys.next()));
    }

    return map;
  }
}
