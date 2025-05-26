package com.jetbrains.youtrack.db.auto.loader;

import java.net.URL;
import java.net.URLClassLoader;

public class ChildFirstClassLoader extends URLClassLoader {

  public ChildFirstClassLoader(URL[] jarUrls, ClassLoader parent) {
    super(jarUrls, parent);
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      // Check if class is already loaded
      var c = findLoadedClass(name);

      if (c == null && name.startsWith("com.jetbrains.youtrack.db")) {
        try {
          c = findClass(name); // Try to load from own JAR first
        } catch (ClassNotFoundException ignored) {
        }
      }

      if (c == null) {
        c = super.loadClass(name, resolve); // Delegate to parent
      }

      if (resolve) {
        resolveClass(c);
      }
      return c;
    }
  }
}
