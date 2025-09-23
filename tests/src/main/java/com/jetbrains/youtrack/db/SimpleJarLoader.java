package com.jetbrains.youtrack.db;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.jar.JarFile;

public class SimpleJarLoader {

  public static void main(String[] args) throws Exception {
    String path = "/Users/logart/dev/youtrackdb/test.jar";
    File file = new File(path);
    JarFile jarFile = new JarFile(file);

    jarFile.stream().forEach(jar -> {
      System.out.println(jar.getName());
    });
    try (URLClassLoader loader = new URLClassLoader(new URL[]{file.toURI().toURL()})) {
      Class<?> cls = loader.loadClass("com.jetbrains.youtrack.db.TestClass");
      System.out.println("Loaded class: " + cls.getName());

      Object obj = cls.getDeclaredConstructor().newInstance();
      cls.getMethod("hello").invoke(obj);
    }
  }
}
