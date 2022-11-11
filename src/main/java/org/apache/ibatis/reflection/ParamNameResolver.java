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
package org.apache.ibatis.reflection;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * 参数名称解析器
 */
public class ParamNameResolver {

  // 通用名称前缀
  public static final String GENERIC_NAME_PREFIX = "param";

  // 是否使用实际参数名称
  private final boolean useActualParamName;

  /**
   * <p>
   * The key is the index and the value is the name of the parameter.<br />
   * The name is obtained from {@link Param} if specified. When {@link Param} is not specified,
   * the parameter index is used. Note that this index could be different from the actual index
   * when the method has special parameters (i.e. {@link RowBounds} or {@link ResultHandler}).
   * </p>
   * <ul>
   * <li>aMethod(@Param("M") int a, @Param("N") int b) -&gt; {{0, "M"}, {1, "N"}}</li>
   * <li>aMethod(int a, int b) -&gt; {{0, "0"}, {1, "1"}}</li>
   * <li>aMethod(int a, RowBounds rb, int b) -&gt; {{0, "0"}, {2, "1"}}</li>
   * </ul>
   */
  private final SortedMap<Integer, String> names;

  /**
   * 是否存在{@link Param}注释
   */
  private boolean hasParamAnnotation;

  /**
   * 构造函数
   *
   * @param config
   * @param method
   */
  public ParamNameResolver(Configuration config, Method method) {
    this.useActualParamName = config.isUseActualParamName();
    // 获取方法所有参数的类型
    final Class<?>[] paramTypes = method.getParameterTypes();
    // 获取方法参数的注解
    final Annotation[][] paramAnnotations = method.getParameterAnnotations();
    // 用于记录参数索引与参数名称的关系
    final SortedMap<Integer, String> map = new TreeMap<>();
    // 方法参数注解的数量
    int paramCount = paramAnnotations.length;
    // get names from @Param annotations
    for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
      // 跳过 RowBounds ResultHandler 类型参数,
      if (isSpecialParameter(paramTypes[paramIndex])) {
        // skip special parameters
        continue;
      }
      String name = null;
      // 遍历参数注解,查询 @Param 注解
      for (Annotation annotation : paramAnnotations[paramIndex]) {
        if (annotation instanceof Param) {
          // 标记为包含注解
          hasParamAnnotation = true;
          // 获取注解中的值
          name = ((Param) annotation).value();
          break;
        }
      }
      // 如果名称为空
      if (name == null) {
        // 是否使用实际参数名称 (默认是使用)
        // @Param was not specified.
        if (useActualParamName) {
          // 获取实际参数名称
          name = getActualParamName(method, paramIndex);
        }
        // 无法获取实际参数名称或者未开启,则使用 map.size() 做为名称
        if (name == null) {
          // use the parameter index as the name ("0", "1", ...)
          // gcode issue #71
          name = String.valueOf(map.size());
        }
      }
      // 最后将参数下标以及名称放入map中
      map.put(paramIndex, name);
    }
    names = Collections.unmodifiableSortedMap(map);
  }

  /**
   * 获取实际参数名称
   *
   * @param method
   * @param paramIndex
   * @return
   */
  private String getActualParamName(Method method, int paramIndex) {
    return ParamNameUtil.getParamNames(method).get(paramIndex);
  }

  /**
   * 类型是否是特殊类型
   *
   * @param clazz
   * @return
   */
  private static boolean isSpecialParameter(Class<?> clazz) {
    return RowBounds.class.isAssignableFrom(clazz) || ResultHandler.class.isAssignableFrom(clazz);
  }

  /**
   * 获取所有的参数名称
   *
   * Returns parameter names referenced by SQL providers.
   *
   * @return the names
   */
  public String[] getNames() {
    return names.values().toArray(new String[0]);
  }

  /**
   * <p>
   * A single non-special parameter is returned without a name.
   * Multiple parameters are named using the naming rule.
   * In addition to the default names, this method also adds the generic names (param1, param2,
   * ...).
   * </p>
   *
   * @param args
   *          the args
   * @return the named params
   */
  public Object getNamedParams(Object[] args) {
    // 当参数数量为空直接返回 null
    final int paramCount = names.size();
    if (args == null || paramCount == 0) {
      return null;
    } else if (!hasParamAnnotation && paramCount == 1) {
      // 未使用 @Param 且只有一个参数
      Object value = args[names.firstKey()];
      // 包装参数
      return wrapToMapIfCollection(value, useActualParamName ? names.get(0) : null);
    } else {
      // 有多个参数的情况
      final Map<String, Object> param = new ParamMap<>();
      int i = 0;
      for (Map.Entry<Integer, String> entry : names.entrySet()) {
        // 将 names 中的所有属性添加到 param 中
        param.put(entry.getValue(), args[entry.getKey()]);
        // 额外在生成一个通用名称添加到 param 中
        // add generic param names (param1, param2, ...)
        final String genericParamName = GENERIC_NAME_PREFIX + (i + 1);
        // ensure not to overwrite parameter named with @Param
        if (!names.containsValue(genericParamName)) {
          param.put(genericParamName, args[entry.getKey()]);
        }
        i++;
      }
      return param;
    }
  }

  /**
   * 包装参数
   *
   * 如果是 Collection 或数组的话,会使用对象 ParamMap 进行包装
   *
   * Wrap to a {@link ParamMap} if object is {@link Collection} or array.
   *
   * @param object a parameter object
   * @param actualParamName an actual parameter name
   *                        (If specify a name, set an object to {@link ParamMap} with specified name)
   * @return a {@link ParamMap}
   * @since 3.5.5
   */
  public static Object wrapToMapIfCollection(Object object, String actualParamName) {
    if (object instanceof Collection) {
      ParamMap<Object> map = new ParamMap<>();
      map.put("collection", object);
      if (object instanceof List) {
        map.put("list", object);
      }
      // 如果指定了实际的参数名称,会额外添加
      Optional.ofNullable(actualParamName).ifPresent(name -> map.put(name, object));
      return map;
    } else if (object != null && object.getClass().isArray()) {
      ParamMap<Object> map = new ParamMap<>();
      map.put("array", object);
      Optional.ofNullable(actualParamName).ifPresent(name -> map.put(name, object));
      return map;
    }
    return object;
  }

}
