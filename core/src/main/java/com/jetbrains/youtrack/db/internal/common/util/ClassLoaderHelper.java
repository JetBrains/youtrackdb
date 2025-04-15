/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrack.db.internal.common.util;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import java.util.Iterator;
import java.util.ServiceLoader;

public class ClassLoaderHelper {

  /**
   * Switch to the YouTrackDB classloader before lookups on ServiceRegistry for implementation of
   * the given Class. Useful under OSGI and generally under applications where jars are loaded by
   * another class loader
   *
   * @param clazz the class to lookup foor
   * @return an Iterator on the class implementation
   */
  public static synchronized <T extends Object> Iterator<T> lookupProviderWithYouTrackDBClassLoader(
      Class<T> clazz) {

    return lookupProviderWithYouTrackDBClassLoader(clazz,
        ClassLoaderHelper.class.getClassLoader());
  }

  public static synchronized <T extends Object> Iterator<T> lookupProviderWithYouTrackDBClassLoader(
      Class<T> clazz, ClassLoader youTrackDBClassLoader) {

    final var origClassLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(youTrackDBClassLoader);
    try {
      return ServiceLoader.load(clazz).iterator();
    } catch (Exception e) {
      LogManager.instance().warn(ClassLoaderHelper.class, "Cannot lookup in service registry", e);
      throw BaseException.wrapException(
          new ConfigurationException("Cannot lookup in service registry"), e, (String) null);
    } finally {
      Thread.currentThread().setContextClassLoader(origClassLoader);
    }
  }
}
