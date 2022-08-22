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
package org.apache.ibatis.binding;

import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.util.MapUtil;

/**
 * Mapper 接口代理
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class MapperProxy<T> implements InvocationHandler, Serializable {

  private static final long serialVersionUID = -4724728412955527868L;
  // Lock类查找的类型
  private static final int ALLOWED_MODES = MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PROTECTED
      | MethodHandles.Lookup.PACKAGE | MethodHandles.Lookup.PUBLIC;
  // JDK8 对应的 MethodHandles.Lookup 类的构造方法,主要用来查询方法句柄
  private static final Constructor<Lookup> lookupConstructor;
  // JDK9 对应的方法 MethodHandles.privateLookupIn,主要用来查询方法句柄 (当前环境为JDK9时使用该方法)
  private static final Method privateLookupInMethod;
  // sqlSession
  private final SqlSession sqlSession;
  // Mapper 接口对应的 Class
  private final Class<T> mapperInterface;
  // 方法缓存(key是: Mapper接口的方法对象,value是: MapperMethodInvoker 对象)
  private final Map<Method, MapperMethodInvoker> methodCache;

  public MapperProxy(SqlSession sqlSession, Class<T> mapperInterface, Map<Method, MapperMethodInvoker> methodCache) {
    this.sqlSession = sqlSession;
    this.mapperInterface = mapperInterface;
    this.methodCache = methodCache;
  }

  static {
    Method privateLookupIn;
    try {
      // 通过获取 MethodHandles 类的 privateLookupIn 方法,判断当前环境是 JDK9
      privateLookupIn = MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
    } catch (NoSuchMethodException e) {
      // 方法不存在则表示是 JDK8
      privateLookupIn = null;
    }
    // privateLookupInMethod 字段赋值
    privateLookupInMethod = privateLookupIn;

    // 获取JDK8的 Lookup 类的指定的构造方法
    Constructor<Lookup> lookup = null;
    if (privateLookupInMethod == null) {
      // JDK 1.8
      try {
        // 获取 Lookup(Class, int) 构造方法
        lookup = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
        lookup.setAccessible(true);
      } catch (NoSuchMethodException e) {
        throw new IllegalStateException(
            "There is neither 'privateLookupIn(Class, Lookup)' nor 'Lookup(Class, int)' method in java.lang.invoke.MethodHandles.",
            e);
      } catch (Exception e) {
        lookup = null;
      }
    }
    // lookupConstructor = lookup类构造方法
    lookupConstructor = lookup;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {
      // 如果是来着 Object 的方法直接调用
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, args);
      } else {
        // 缓存并获取 Mapper 方法执行器,然后执行 invoke 方法
        return cachedInvoker(method).invoke(proxy, method, args, sqlSession);
      }
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
  }

  /**
   * 获取 Mapper 方法执行器
   *
   * @param method
   * @return
   * @throws Throwable
   */
  private MapperMethodInvoker cachedInvoker(Method method) throws Throwable {
    try {
      // 如果 methodCache 字段中没有缓存则创建执行器并返回
      return MapUtil.computeIfAbsent(methodCache, method, m -> {
        // 判断是否是默认方法
        if (m.isDefault()) {
          // https://stackoverflow.com/questions/22614746/how-do-i-invoke-java-8-default-methods-reflectively
          try {
            // 判断jdk版本 (privateLookupInMethod 字段在JDK8时为 null )
            if (privateLookupInMethod == null) {
              // JDK8 创建默认方法执行器(持有方法句柄对象)
              return new DefaultMethodInvoker(getMethodHandleJava8(method));
            } else {
              // JDK9 创建默认方法执行器(持有方法句柄对象)
              return new DefaultMethodInvoker(getMethodHandleJava9(method));
            }
          } catch (IllegalAccessException | InstantiationException | InvocationTargetException
              | NoSuchMethodException e) {
            throw new RuntimeException(e);
          }
        } else {
          // 为非默认方法创建构造器(持有 MapperMethod 对象)
          return new PlainMethodInvoker(new MapperMethod(mapperInterface, method, sqlSession.getConfiguration()));
        }
      });
    } catch (RuntimeException re) {
      Throwable cause = re.getCause();
      throw cause == null ? re : cause;
    }
  }

  /**
   * 获取JDK9默认方法句柄
   * @param method
   * @return
   * @throws NoSuchMethodException
   * @throws IllegalAccessException
   * @throws InvocationTargetException
   */
  private MethodHandle getMethodHandleJava9(Method method)
      throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
    final Class<?> declaringClass = method.getDeclaringClass();
    return ((Lookup) privateLookupInMethod.invoke(null, declaringClass, MethodHandles.lookup())).findSpecial(
        declaringClass, method.getName(), MethodType.methodType(method.getReturnType(), method.getParameterTypes()),
        declaringClass);
  }

  /**
   * 获取JDK8默认方法句柄
   * @param method
   * @return
   * @throws IllegalAccessException
   * @throws InstantiationException
   * @throws InvocationTargetException
   */
  private MethodHandle getMethodHandleJava8(Method method)
      throws IllegalAccessException, InstantiationException, InvocationTargetException {
    // 获取定义的 class
    final Class<?> declaringClass = method.getDeclaringClass();
    // 构造LookUp对象,并为反射方法生成方法句柄
    return lookupConstructor.newInstance(declaringClass, ALLOWED_MODES).unreflectSpecial(method, declaringClass);
  }

  /**
   * Mapper 方法执行器
   */
  interface MapperMethodInvoker {

    /**
     * 调用具体的方法
     *
     * @param proxy       代理类
     * @param method      方法
     * @param args        参数
     * @param sqlSession  sqlSession
     * @return            方法返回值
     * @throws Throwable
     */
    Object invoke(Object proxy, Method method, Object[] args, SqlSession sqlSession) throws Throwable;
  }

  /**
   * 普通方法执行器
   */
  private static class PlainMethodInvoker implements MapperMethodInvoker {

    // MapperMethod 对象
    private final MapperMethod mapperMethod;

    public PlainMethodInvoker(MapperMethod mapperMethod) {
      super();
      this.mapperMethod = mapperMethod;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args, SqlSession sqlSession) throws Throwable {
      return mapperMethod.execute(sqlSession, args);
    }
  }

  /**
   * 默认方法执行器
   */
  private static class DefaultMethodInvoker implements MapperMethodInvoker {

    // 方法句柄
    private final MethodHandle methodHandle;

    public DefaultMethodInvoker(MethodHandle methodHandle) {
      super();
      this.methodHandle = methodHandle;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args, SqlSession sqlSession) throws Throwable {
      // 使用方法句柄调用
      return methodHandle.bindTo(proxy).invokeWithArguments(args);
    }
  }
}
