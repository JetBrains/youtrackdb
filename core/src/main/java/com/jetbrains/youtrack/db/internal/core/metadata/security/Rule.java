package com.jetbrains.youtrack.db.internal.core.metadata.security;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * @since 08/11/14
 */
public class Rule implements Serializable {

  public abstract static class ResourceGeneric implements Serializable {

    private static final TreeMap<String, ResourceGeneric> nameToGenericMap =
        new TreeMap<String, ResourceGeneric>();
    private static final TreeMap<String, ResourceGeneric> legacyToGenericMap =
        new TreeMap<String, ResourceGeneric>();
    private static final Map<ResourceGeneric, String> genericToLegacyMap =
        new HashMap<ResourceGeneric, String>();

    public static final ResourceGeneric ALL =
        new ResourceGeneric("ALL", DatabaseSecurityResources.ALL) {
        };

    public static final ResourceGeneric FUNCTION =
        new ResourceGeneric("FUNCTION", DatabaseSecurityResources.FUNCTION) {
        };
    public static final ResourceGeneric CLASS =
        new ResourceGeneric("CLASS", DatabaseSecurityResources.CLASS) {
        };
    public static final ResourceGeneric COLLECTION =
        new ResourceGeneric("COLLECTION", DatabaseSecurityResources.COLLECTION) {
        };
    public static final ResourceGeneric BYPASS_RESTRICTED =
        new ResourceGeneric("BYPASS_RESTRICTED", DatabaseSecurityResources.BYPASS_RESTRICTED) {
        };
    public static final ResourceGeneric DATABASE =
        new ResourceGeneric("DATABASE", DatabaseSecurityResources.DATABASE) {
        };
    public static final ResourceGeneric SCHEMA =
        new ResourceGeneric("SCHEMA", DatabaseSecurityResources.SCHEMA) {
        };
    public static final ResourceGeneric COMMAND =
        new ResourceGeneric("COMMAND", DatabaseSecurityResources.COMMAND) {
        };

    public static final ResourceGeneric COMMAND_GREMLIN =
        new ResourceGeneric("COMMAND_GREMLIN", DatabaseSecurityResources.COMMAND_GREMLIN) {
        };

    public static final ResourceGeneric RECORD_HOOK =
        new ResourceGeneric("RECORD_HOOK", DatabaseSecurityResources.RECORD_HOOK) {
        };

    public static final ResourceGeneric SYSTEM_COLLECTIONS =
        new ResourceGeneric("SYSTEM_COLLECTION", DatabaseSecurityResources.SYSTEMCOLLECTIONS) {
        };

    public static final ResourceGeneric SERVER =
        new ResourceGeneric("SERVER", "server") {
        };

    public static final ResourceGeneric DATABASE_COPY =
        new ResourceGeneric("DATABASE_COPY", "database.copy") {
        };

    public static final ResourceGeneric DATABASE_CREATE =
        new ResourceGeneric("DATABASE_CREATE", "database.create") {
        };

    public static final ResourceGeneric DATABASE_DROP =
        new ResourceGeneric("DATABASE_DROP", "database.drop") {
        };

    public static final ResourceGeneric DATABASE_EXISTS =
        new ResourceGeneric("DATABASE_EXISTS", "database.exists") {
        };

    public static final ResourceGeneric DATABASE_FREEZE =
        new ResourceGeneric("DATABASE_FREEZE", "database.freeze") {
        };

    public static final ResourceGeneric DATABASE_RELEASE =
        new ResourceGeneric("DATABASE_RELEASE", "database.release") {
        };

    public static final ResourceGeneric DATABASE_PASSTHROUGH =
        new ResourceGeneric("DATABASE_PASSTHROUGH", "database.passthrough") {
        };

    private final String name;
    private final String legacyName;

    protected ResourceGeneric(String name, String legacyName) {
      this.name = name;
      this.legacyName = legacyName != null ? legacyName : name;
      register(this);
    }

    public String getName() {
      return name;
    }

    public String getLegacyName() {
      return legacyName;
    }

    private static void register(ResourceGeneric resource) {
      var legacyNameLowCase = resource.legacyName.toLowerCase(Locale.ENGLISH);
      if (nameToGenericMap.containsKey(resource.name)
          || legacyToGenericMap.containsKey(resource.legacyName.toLowerCase(Locale.ENGLISH))
          || genericToLegacyMap.containsKey(resource)) {
        throw new IllegalArgumentException(resource + " already registered");
      }
      nameToGenericMap.put(resource.name, resource);
      legacyToGenericMap.put(legacyNameLowCase, resource);
      genericToLegacyMap.put(resource, resource.legacyName);
    }

    public static ResourceGeneric valueOf(String name) {
      return nameToGenericMap.get(name);
    }

    public static ResourceGeneric[] values() {
      return genericToLegacyMap.keySet().toArray(new ResourceGeneric[genericToLegacyMap.size()]);
    }

    @Override
    public String toString() {
      return ResourceGeneric.class.getSimpleName()
          + " [name="
          + name
          + ", legacyName="
          + legacyName
          + "]";
    }
  }

  private final ResourceGeneric resourceGeneric;
  private final Map<String, Byte> specificResources = new HashMap<String, Byte>();

  private Byte access = null;

  public Rule(
      final ResourceGeneric resourceGeneric,
      final Map<String, Byte> specificResources,
      final Byte access) {
    this.resourceGeneric = resourceGeneric;
    if (specificResources != null) {
      this.specificResources.putAll(specificResources);
    }
    this.access = access;
  }

  @Nullable
  public static ResourceGeneric mapLegacyResourceToGenericResource(final String resource) {
    final var found =
        ResourceGeneric.legacyToGenericMap.floorEntry(resource.toLowerCase(Locale.ENGLISH));
    if (found == null) {
      return null;
    }

    if (resource.length() < found.getKey().length()) {
      return null;
    }

    if (resource.substring(0, found.getKey().length()).equalsIgnoreCase(found.getKey())) {
      return found.getValue();
    }

    return null;
  }

  public static String mapResourceGenericToLegacyResource(final ResourceGeneric resourceGeneric) {
    return ResourceGeneric.genericToLegacyMap.get(resourceGeneric);
  }

  @Nullable
  public static String mapLegacyResourceToSpecificResource(final String resource) {
    var found =
        ResourceGeneric.legacyToGenericMap.floorEntry(resource.toLowerCase(Locale.ENGLISH));

    if (found == null) {
      return resource;
    }

    if (resource.length() < found.getKey().length()) {
      return resource;
    }

    if (resource.length() == found.getKey().length()) {
      return null;
    }

    if (resource.substring(0, found.getKey().length()).equalsIgnoreCase(found.getKey())) {
      return resource.substring(found.getKey().length() + 1);
    }

    return resource;
  }

  public Byte getAccess() {
    return access;
  }

  public ResourceGeneric getResourceGeneric() {
    return resourceGeneric;
  }

  public Map<String, Byte> getSpecificResources() {
    return specificResources;
  }

  public void grantAccess(String resource, final int operation) {
    if (resource == null) {
      access = grant((byte) operation, access);
    } else {
      resource = resource.toLowerCase(Locale.ENGLISH);
      var ac = specificResources.get(resource);
      specificResources.put(resource, grant((byte) operation, ac));
    }
  }

  private byte grant(final byte operation, final Byte ac) {
    if (operation == Role.PERMISSION_NONE)
    // IT'S A REVOKE
    {
      return 0;
    }

    byte currentValue = ac == null ? Role.PERMISSION_NONE : ac;

    currentValue |= operation;
    return currentValue;
  }

  public void revokeAccess(String resource, final int operation) {
    if (operation == Role.PERMISSION_NONE) {
      return;
    }

    if (resource == null) {
      access = revoke((byte) operation, access);
    } else {
      resource = resource.toLowerCase(Locale.ENGLISH);
      final var ac = specificResources.get(resource);
      specificResources.put(resource, revoke((byte) operation, ac));
    }
  }

  private byte revoke(final byte operation, final Byte ac) {
    byte currentValue;
    if (ac == null) {
      currentValue = Role.PERMISSION_NONE;
    } else {
      currentValue = ac.byteValue();
      currentValue &= ~(byte) operation;
    }
    return currentValue;
  }

  public Boolean isAllowed(final String name, final int operation) {
    if (name == null) {
      return allowed((byte) operation, access);
    }

    if (specificResources.isEmpty()) {
      return isAllowed(null, operation);
    }

    final var ac = specificResources.get(name.toLowerCase(Locale.ENGLISH));
    final var allowed = allowed((byte) operation, ac);
    if (allowed == null) {
      return isAllowed(null, operation);
    }

    return allowed;
  }

  @Nullable
  private Boolean allowed(final byte operation, final Byte ac) {
    if (ac == null) {
      return null;
    }

    final var mask = operation;

    return (ac.byteValue() & mask) == mask;
  }

  public boolean containsSpecificResource(final String resource) {
    if (specificResources.isEmpty()) {
      return false;
    }

    return specificResources.containsKey(resource.toLowerCase(Locale.ENGLISH));
  }
}
