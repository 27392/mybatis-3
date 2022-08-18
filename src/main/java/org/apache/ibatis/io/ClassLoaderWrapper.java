/*
 *    Copyright 2009-2021 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.io;

import java.io.InputStream;
import java.net.URL;

/**
 * A class to wrap access to multiple class loaders making them work as one
 *
 * @author Clinton Begin
 */
public class ClassLoaderWrapper {

  // 应用指定的默认类加载器
  ClassLoader defaultClassLoader;

  // SystemClassLoader
  ClassLoader systemClassLoader;

  ClassLoaderWrapper() {
    try {
      // 初始化 systemClassLoader 字段
      systemClassLoader = ClassLoader.getSystemClassLoader();
    } catch (SecurityException ignored) {
      // AccessControlException on Google App Engine
    }
  }

  /**
   * 从 classpath 中获取资源,以 URL 形式返回
   *
   * Get a resource as a URL using the current class path
   *
   * @param resource - the resource to locate
   * @return the resource or null
   */
  public URL getResourceAsURL(String resource) {
    return getResourceAsURL(resource, getClassLoaders(null));
  }

  /**
   * 从 classpath 中获取资源,以 URL 形式返回
   *
   * Get a resource from the classpath, starting with a specific class loader
   *
   * @param resource    - the resource to find
   * @param classLoader - the first classloader to try
   * @return the stream or null
   */
  public URL getResourceAsURL(String resource, ClassLoader classLoader) {
    return getResourceAsURL(resource, getClassLoaders(classLoader));
  }

  /**
   * 从 classpath 中获取资源,以 InputStream 形式返回
   *
   * Get a resource from the classpath
   *
   * @param resource - the resource to find
   * @return the stream or null
   */
  public InputStream getResourceAsStream(String resource) {
    return getResourceAsStream(resource, getClassLoaders(null));
  }

  /**
   * 从 classpath 中获取资源,以 InputStream 形式返回
   *
   * Get a resource from the classpath, starting with a specific class loader
   *
   * @param resource    - the resource to find
   * @param classLoader - the first class loader to try
   * @return the stream or null
   */
  public InputStream getResourceAsStream(String resource, ClassLoader classLoader) {
    return getResourceAsStream(resource, getClassLoaders(classLoader));
  }

  /**
   * 从 classpath 中查找 class
   *
   * Find a class on the classpath (or die trying)
   *
   * @param name - the class to look for
   * @return - the class
   * @throws ClassNotFoundException Duh.
   */
  public Class<?> classForName(String name) throws ClassNotFoundException {
    return classForName(name, getClassLoaders(null));
  }

  /**
   * 从 classpath 中查找 class
   *
   * Find a class on the classpath, starting with a specific classloader (or die trying)
   *
   * @param name        - the class to look for
   * @param classLoader - the first classloader to try
   * @return - the class
   * @throws ClassNotFoundException Duh.
   */
  public Class<?> classForName(String name, ClassLoader classLoader) throws ClassNotFoundException {
    return classForName(name, getClassLoaders(classLoader));
  }

  /**
   * 在一组类加载器内获取资源,以 InputStream 形式返回
   *
   * Try to get a resource from a group of classloaders
   *
   * @param resource    - the resource to get
   * @param classLoader - the classloaders to examine
   * @return the resource or null
   */
  InputStream getResourceAsStream(String resource, ClassLoader[] classLoader) {

    // 遍历类加载器
    for (ClassLoader cl : classLoader) {
      if (null != cl) {

        // 调用 ClassLoader.getResourceAsStream 方法查找指定的资源
        // try to find the resource as passed
        InputStream returnValue = cl.getResourceAsStream(resource);

        // 如果未查找到资源, 尝试加上 "/" 再次查找
        // now, some class loaders want this leading "/", so we'll add it and try again if we didn't find the resource
        if (null == returnValue) {
          returnValue = cl.getResourceAsStream("/" + resource);
        }

        // 查询到指定的资源则直接返回
        if (null != returnValue) {
          return returnValue;
        }
      }
    }
    return null;
  }

  /**
   * 在一组类加载器内获取资源,以 URL 形式返回
   *
   * Get a resource as a URL using the current class path
   *
   * @param resource    - the resource to locate
   * @param classLoader - the class loaders to examine
   * @return the resource or null
   */
  URL getResourceAsURL(String resource, ClassLoader[] classLoader) {

    URL url;

    // 遍历类加载器
    for (ClassLoader cl : classLoader) {

      if (null != cl) {

        // 调用 ClassLoader.getResource 查找指定资源
        // look for the resource as passed in...
        url = cl.getResource(resource);

        // 如果未查找到资源, 尝试加上 "/" 再次查找
        // ...but some class loaders want this leading "/", so we'll add it
        // and try again if we didn't find the resource
        if (null == url) {
          url = cl.getResource("/" + resource);
        }

        // 查询到指定的资源则直接返回
        // "It's always in the last place I look for it!"
        // ... because only an idiot would keep looking for it after finding it, so stop looking already.
        if (null != url) {
          return url;
        }

      }

    }

    // didn't find it anywhere.
    return null;

  }

  /**
   * 在一组类加载器中查找 class
   *
   * Attempt to load a class from a group of classloaders
   *
   * @param name        - the class to load
   * @param classLoader - the group of classloaders to examine
   * @return the class
   * @throws ClassNotFoundException - Remember the wisdom of Judge Smails: Well, the world needs ditch diggers, too.
   */
  Class<?> classForName(String name, ClassLoader[] classLoader) throws ClassNotFoundException {

    // 遍历类加载器
    for (ClassLoader cl : classLoader) {

      if (null != cl) {

        try {

          // 调用 Class.forName 查询获取加载指定的类
          return Class.forName(name, true, cl);

        } catch (ClassNotFoundException e) {
          // we'll ignore this until all classloaders fail to locate the class
        }

      }

    }

    // 无法查询到则抛出异常
    throw new ClassNotFoundException("Cannot find class: " + name);

  }

  /**
   * 获取类加载器数组
   *
   * @param classLoader
   * @return
   */
  ClassLoader[] getClassLoaders(ClassLoader classLoader) {
    return new ClassLoader[]{
        classLoader,  // 参数指定的类加载器
        defaultClassLoader, // 应用指定的默认类加载器
        Thread.currentThread().getContextClassLoader(), // 当前线程绑定的类加载器
        getClass().getClassLoader(),  // 加载当前类所使用的类加载器
        systemClassLoader}; // SystemClassLoader
  }

}
