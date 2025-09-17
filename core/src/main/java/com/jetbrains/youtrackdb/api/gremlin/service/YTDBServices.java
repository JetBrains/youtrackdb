package com.jetbrains.youtrackdb.api.gremlin.service;

import org.apache.tinkerpop.gremlin.structure.service.ServiceRegistry;

public class YTDBServices {

  public static final ServiceRegistry REGISTRY = new ServiceRegistry() {
    private final boolean frozen;

    {
      registerService(new YTDBRemovePropertyService.Factory<>());
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
