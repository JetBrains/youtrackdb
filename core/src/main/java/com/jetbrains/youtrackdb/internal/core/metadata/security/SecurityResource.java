package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.internal.core.exception.SecurityException;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public abstract class SecurityResource {

  private static final Map<String, SecurityResource> cache = new ConcurrentHashMap<>();

  public static SecurityResource getInstance(String resource) {
    var result = cache.get(resource);
    if (result == null) {
      result = parseResource(resource);
      if (result != null) {
        cache.put(resource, result);
      }
    }
    return result;
  }

  protected static SecurityResource parseResource(String resource) {
    switch (resource) {
      case "*" -> {
        return SecurityResourceAll.INSTANCE;
      }
      case "database.schema" -> {
        return SecurityResourceSchema.INSTANCE;
      }
      case "database.class.*" -> {
        return SecurityResourceClass.ALL_CLASSES;
      }
      case "database.class.*.*" -> {
        return SecurityResourceProperty.ALL_PROPERTIES;
      }
      case "database.collection.*" -> {
        return SecurityResourceCollection.ALL_COLLECTIONS;
      }
      case "database.systemcollections" -> {
        return SecurityResourceCollection.SYSTEM_COLLECTIONS;
      }
      case "database.function.*" -> {
        return SecurityResourceFunction.ALL_FUNCTIONS;
      }
      case "database" -> {
        return SecurityResourceDatabaseOp.DB;
      }
      case "database.create" -> {
        return SecurityResourceDatabaseOp.CREATE;
      }
      case "database.copy" -> {
        return SecurityResourceDatabaseOp.COPY;
      }
      case "database.drop" -> {
        return SecurityResourceDatabaseOp.DROP;
      }
      case "database.exists" -> {
        return SecurityResourceDatabaseOp.EXISTS;
      }
      case "database.command" -> {
        return SecurityResourceDatabaseOp.COMMAND;
      }
      case "database.command.gremlin" -> {
        return SecurityResourceDatabaseOp.COMMAND_GREMLIN;
      }
      case "database.freeze" -> {
        return SecurityResourceDatabaseOp.FREEZE;
      }
      case "database.release" -> {
        return SecurityResourceDatabaseOp.RELEASE;
      }
      case "database.passthrough" -> {
        return SecurityResourceDatabaseOp.PASS_THROUGH;
      }
      case "database.bypassRestricted" -> {
        return SecurityResourceDatabaseOp.BYPASS_RESTRICTED;
      }
      case "database.hook.record" -> {
        return SecurityResourceDatabaseOp.HOOK_RECORD;
      }
      case "server" -> {
        return SecurityResourceServerOp.SERVER;
      }
      case "server.status" -> {
        return SecurityResourceServerOp.STATUS;
      }
      case "server.remove" -> {
        return SecurityResourceServerOp.REMOVE;
      }
      case "server.admin" -> {
        return SecurityResourceServerOp.ADMIN;
      }
    }
    try {
      var parsed = SQLEngine.parseSecurityResource(resource);

      if (resource.startsWith("database.class.")) {
        var classElement = parsed.getNext().getNext();
        String className;
        var allClasses = false;
        if (classElement.getIdentifier() != null) {
          className = classElement.getIdentifier().getStringValue();
        } else {
          className = null;
          allClasses = true;
        }
        var propertyModifier = classElement.getNext();
        if (propertyModifier != null) {
          if (propertyModifier.getNext() != null) {
            throw new SecurityException("Invalid resource: " + resource);
          }
          var propertyName = propertyModifier.getIdentifier().getStringValue();
          if (allClasses) {
            return new SecurityResourceProperty(resource, propertyName);
          } else {
            return new SecurityResourceProperty(resource, className, propertyName);
          }
        } else {
          return new SecurityResourceClass(resource, className);
        }
      } else if (resource.startsWith("database.collection.")) {
        var collectionElement = parsed.getNext().getNext();
        var collectionName = collectionElement.getIdentifier().getStringValue();
        if (collectionElement.getNext() != null) {
          throw new SecurityException("Invalid resource: " + resource);
        }
        return new SecurityResourceCollection(resource, collectionName);
      } else if (resource.startsWith("database.function.")) {
        var functionElement = parsed.getNext().getNext();
        var functionName = functionElement.getIdentifier().getStringValue();
        if (functionElement.getNext() != null) {
          throw new SecurityException("Invalid resource: " + resource);
        }
        return new SecurityResourceFunction(resource, functionName);
      } else if (resource.startsWith("database.systemcollections.")) {
        var collectionElement = parsed.getNext().getNext();
        var collectionName = collectionElement.getIdentifier().getStringValue();
        if (collectionElement.getNext() != null) {
          throw new SecurityException("Invalid resource: " + resource);
        }
        return new SecurityResourceCollection(resource, collectionName);
      }

      throw new SecurityException("Invalid resource: " + resource);
    } catch (Exception ex) {
      throw new SecurityException("Invalid resource: " + resource);
    }
  }

  protected final String resourceString;

  public SecurityResource(String resourceString) {
    this.resourceString = resourceString;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    var that = (SecurityResource) o;
    return Objects.equals(resourceString, that.resourceString);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(resourceString);
  }
}
