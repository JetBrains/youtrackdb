package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import org.apache.tinkerpop.gremlin.structure.service.ServiceRegistry;

public final class YTDBServices {

  private YTDBServices() {
  }

  /// Registry for YouTrackDB TinkerPop services. It is static in the sense that it is not possible
  /// to register new TinkerPop services at runtime.
  public static final ServiceRegistry REGISTRY = new ServiceRegistry() {
    private final boolean frozen;
    {
      registerService(new YTDBRemovePropertyService.Factory<>());
      registerService(new YTDBCommandService.Factory());
      registerService(new YTDBCommandService.SqlCommandFactory());
      registerService(new YTDBFullBackupService.Factory());
      registerService(new YTDBIncrementalBackupService.Factory());
      registerService(new YTDBGraphUuidService.Factory());
      frozen = true;
    }

    @Override
    public ServiceFactory<?, ?> registerService(ServiceFactory serviceFactory) {
      if (frozen) {
        throw new UnsupportedOperationException("Cannot register a service");
      }
      return super.registerService(serviceFactory);
    }
  };
}
