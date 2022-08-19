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

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * <p>ResolverUtil is used to locate classes that are available in the/a class path and meet
 * arbitrary conditions. The two most common conditions are that a class implements/extends
 * another class, or that is it annotated with a specific annotation. However, through the use
 * of the {@link Test} class it is possible to search using arbitrary conditions.</p>
 *
 * <p>A ClassLoader is used to locate all locations (directories and jar files) in the class
 * path that contain classes within certain packages, and then to load those classes and
 * check them. By default the ClassLoader returned by
 * {@code Thread.currentThread().getContextClassLoader()} is used, but this can be overridden
 * by calling {@link #setClassLoader(ClassLoader)} prior to invoking any of the {@code find()}
 * methods.</p>
 *
 * <p>General searches are initiated by calling the {@link #find(Test, String)} and supplying
 * a package name and a Test instance. This will cause the named package <b>and all sub-packages</b>
 * to be scanned for classes that meet the test. There are also utility methods for the common
 * use cases of scanning multiple packages for extensions of particular classes, or classes
 * annotated with a specific annotation.</p>
 *
 * <p>The standard usage pattern for the ResolverUtil class is as follows:</p>
 *
 * <pre>
 * ResolverUtil&lt;ActionBean&gt; resolver = new ResolverUtil&lt;ActionBean&gt;();
 * resolver.findImplementation(ActionBean.class, pkg1, pkg2);
 * resolver.find(new CustomTest(), pkg1);
 * resolver.find(new CustomTest(), pkg2);
 * Collection&lt;ActionBean&gt; beans = resolver.getClasses();
 * </pre>
 *
 * @author Tim Fennell
 * @param <T>
 *          the generic type
 */
public class ResolverUtil<T> {

  /**
   * 日志对象
   * An instance of Log to use for logging in this class.
   */
  private static final Log log = LogFactory.getLog(ResolverUtil.class);

  /**
   * 测试接口
   *
   * A simple interface that specifies how to test classes to determine if they
   * are to be included in the results produced by the ResolverUtil.
   */
  public interface Test {

    /**
     * 指定类是否符合条件
     *
     * Will be called repeatedly with candidate classes. Must return True if a class
     * is to be included in the results, false otherwise.
     *
     * @param type
     *          the type
     * @return true, if successful
     */
    boolean matches(Class<?> type);
  }

  /**
   * 用于检测指定类是否是指定类型(parent)的子类
   *
   * A Test that checks to see if each class is assignable to the provided class. Note
   * that this test will match the parent type itself if it is presented for matching.
   */
  public static class IsA implements Test {

    // 父类
    /** The parent. */
    private Class<?> parent;

    /**
     * 构造方法中对 parent 属性初始化
     * Constructs an IsA test using the supplied Class as the parent class/interface.
     *
     * @param parentType
     *          the parent type
     */
    public IsA(Class<?> parentType) {
      this.parent = parentType;
    }

    /** Returns true if type is assignable to the parent type supplied in the constructor. */
    @Override
    public boolean matches(Class<?> type) {
      // 类不为空, 并且类是 parent 的子类
      return type != null && parent.isAssignableFrom(type);
    }

    @Override
    public String toString() {
      return "is assignable to " + parent.getSimpleName();
    }
  }

  /**
   * 检测指定类是否添加了指定注解
   *
   * A Test that checks to see if each class is annotated with a specific annotation. If it
   * is, then the test returns true, otherwise false.
   */
  public static class AnnotatedWith implements Test {

    // 注解信息
    /** The annotation. */
    private Class<? extends Annotation> annotation;

    /**
     * 构造方法中对 annotation 属性进行初始化
     * Constructs an AnnotatedWith test for the specified annotation type.
     *
     * @param annotation
     *          the annotation
     */
    public AnnotatedWith(Class<? extends Annotation> annotation) {
      this.annotation = annotation;
    }

    /** Returns true if the type is annotated with the class provided to the constructor. */
    @Override
    public boolean matches(Class<?> type) {
      // 类不为空并且类包含指定注解(annotation)
      return type != null && type.isAnnotationPresent(annotation);
    }

    @Override
    public String toString() {
      return "annotated with @" + annotation.getSimpleName();
    }
  }

  // 满足匹配条件的类集合
  /** The set of matches being accumulated. */
  private Set<Class<? extends T>> matches = new HashSet<>();

  /**
   * 类加载器
   *
   * The ClassLoader to use when looking for classes. If null then the ClassLoader returned
   * by Thread.currentThread().getContextClassLoader() will be used.
   */
  private ClassLoader classloader;

  /**
   * 获取满足匹配条件的类的集合
   *
   * Provides access to the classes discovered so far. If no calls have been made to
   * any of the {@code find()} methods, this set will be empty.
   *
   * @return the set of classes that have been discovered.
   */
  public Set<Class<? extends T>> getClasses() {
    return matches;
  }

  /**
   * 获取类加载器, 如果为空则使用当前线程的类加载器
   *
   * Returns the classloader that will be used for scanning for classes. If no explicit
   * ClassLoader has been set by the calling, the context class loader will be used.
   *
   * @return the ClassLoader that will be used to scan for classes
   */
  public ClassLoader getClassLoader() {
    return classloader == null ? Thread.currentThread().getContextClassLoader() : classloader;
  }

  /**
   * 设置类加载器
   * Sets an explicit ClassLoader that should be used when scanning for classes. If none
   * is set then the context classloader will be used.
   *
   * @param classloader a ClassLoader to use when scanning for classes
   */
  public void setClassLoader(ClassLoader classloader) {
    this.classloader = classloader;
  }

  /**
   * 根据是否是指定类型的子类查找指定包内的匹配的资源. 使用getClasses()方法获取匹配到的结果
   *
   * Attempts to discover classes that are assignable to the type provided. In the case
   * that an interface is provided this method will collect implementations. In the case
   * of a non-interface class, subclasses will be collected.  Accumulated classes can be
   * accessed by calling {@link #getClasses()}.
   *
   * @param parent
   *          the class of interface to find subclasses or implementations of
   * @param packageNames
   *          one or more package names to scan (including subpackages) for classes
   * @return the resolver util
   */
  public ResolverUtil<T> findImplementations(Class<?> parent, String... packageNames) {
    if (packageNames == null) {
      return this;
    }

    Test test = new IsA(parent);
    for (String pkg : packageNames) {
      find(test, pkg);
    }

    return this;
  }

  /**
   * 根据注解条件查找指定包内的匹配的资源. 使用getClasses()方法获取匹配到的结果
   *
   * Attempts to discover classes that are annotated with the annotation. Accumulated
   * classes can be accessed by calling {@link #getClasses()}.
   *
   * @param annotation
   *          the annotation that should be present on matching classes
   * @param packageNames
   *          one or more package names to scan (including subpackages) for classes
   * @return the resolver util
   */
  public ResolverUtil<T> findAnnotated(Class<? extends Annotation> annotation, String... packageNames) {
    if (packageNames == null) {
      return this;
    }

    Test test = new AnnotatedWith(annotation);
    for (String pkg : packageNames) {
      find(test, pkg);
    }

    return this;
  }

  /**
   * 根据条件查找指定包内的匹配的资源. 使用getClasses()方法获取匹配到的结果
   *
   * Scans for classes starting at the package provided and descending into subpackages.
   * Each class is offered up to the Test as it is discovered, and if the Test returns
   * true the class is retained.  Accumulated classes can be fetched by calling
   * {@link #getClasses()}.
   *
   * @param test
   *          an instance of {@link Test} that will be used to filter classes
   * @param packageName
   *          the name of the package from which to start scanning for classes, e.g. {@code net.sourceforge.stripes}
   * @return the resolver util
   */
  public ResolverUtil<T> find(Test test, String packageName) {
    // 获取转换后的包名
    String path = getPackagePath(packageName);

    try {
      // 通过VFS获取指定包下的所有资源
      List<String> children = VFS.getInstance().list(path);
      // 遍历资源
      for (String child : children) {
        // 是否是class文件
        if (child.endsWith(".class")) {
          // 将匹配的结果交给 test 处理
          addIfMatching(test, child);
        }
      }
    } catch (IOException ioe) {
      log.error("Could not read package: " + packageName, ioe);
    }

    return this;
  }

  /**
   * 转换包名,将 "." 替换成 "/"
   *
   * Converts a Java package name to a path that can be looked up with a call to
   * {@link ClassLoader#getResources(String)}.
   *
   * @param packageName
   *          The Java package name to convert to a path
   * @return the package path
   */
  protected String getPackagePath(String packageName) {
    return packageName == null ? null : packageName.replace('.', '/');
  }

  /**
   * 匹配资源并添加
   * 如果匹配成功则添加到 matches 集合中
   *
   * Add the class designated by the fully qualified class name provided to the set of
   * resolved classes if and only if it is approved by the Test supplied.
   *
   * @param test the test used to determine if the class matches
   * @param fqn the fully qualified name of a class
   */
  @SuppressWarnings("unchecked")
  protected void addIfMatching(Test test, String fqn) {
    try {
      // 将资源名称进行转换
      String externalName = fqn.substring(0, fqn.indexOf('.')).replace('/', '.');
      // 获取类加载器
      ClassLoader loader = getClassLoader();
      if (log.isDebugEnabled()) {
        log.debug("Checking to see if class " + externalName + " matches criteria [" + test + "]");
      }

      // 加载类
      Class<?> type = loader.loadClass(externalName);
      // 交由 Test 类进行匹配
      if (test.matches(type)) {
        // 匹配成功将结果添加到 matches 集合中
        matches.add((Class<T>) type);
      }
    } catch (Throwable t) {
      log.warn("Could not examine class '" + fqn + "'" + " due to a "
          + t.getClass().getName() + " with message: " + t.getMessage());
    }
  }
}
