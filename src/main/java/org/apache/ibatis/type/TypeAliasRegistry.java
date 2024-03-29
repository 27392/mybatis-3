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
package org.apache.ibatis.type;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.io.Resources;

/**
 * @author Clinton Begin
 */
public class TypeAliasRegistry {

  /**
   * Class类型与别名的映射关系
   */
  private final Map<String, Class<?>> typeAliases = new HashMap<>();

  /**
   * 初始化基础类的别名
   */
  public TypeAliasRegistry() {
    registerAlias("string", String.class);

    registerAlias("byte", Byte.class);
    registerAlias("long", Long.class);
    registerAlias("short", Short.class);
    registerAlias("int", Integer.class);
    registerAlias("integer", Integer.class);
    registerAlias("double", Double.class);
    registerAlias("float", Float.class);
    registerAlias("boolean", Boolean.class);

    registerAlias("byte[]", Byte[].class);
    registerAlias("long[]", Long[].class);
    registerAlias("short[]", Short[].class);
    registerAlias("int[]", Integer[].class);
    registerAlias("integer[]", Integer[].class);
    registerAlias("double[]", Double[].class);
    registerAlias("float[]", Float[].class);
    registerAlias("boolean[]", Boolean[].class);

    registerAlias("_byte", byte.class);
    registerAlias("_long", long.class);
    registerAlias("_short", short.class);
    registerAlias("_int", int.class);
    registerAlias("_integer", int.class);
    registerAlias("_double", double.class);
    registerAlias("_float", float.class);
    registerAlias("_boolean", boolean.class);

    registerAlias("_byte[]", byte[].class);
    registerAlias("_long[]", long[].class);
    registerAlias("_short[]", short[].class);
    registerAlias("_int[]", int[].class);
    registerAlias("_integer[]", int[].class);
    registerAlias("_double[]", double[].class);
    registerAlias("_float[]", float[].class);
    registerAlias("_boolean[]", boolean[].class);

    registerAlias("date", Date.class);
    registerAlias("decimal", BigDecimal.class);
    registerAlias("bigdecimal", BigDecimal.class);
    registerAlias("biginteger", BigInteger.class);
    registerAlias("object", Object.class);

    registerAlias("date[]", Date[].class);
    registerAlias("decimal[]", BigDecimal[].class);
    registerAlias("bigdecimal[]", BigDecimal[].class);
    registerAlias("biginteger[]", BigInteger[].class);
    registerAlias("object[]", Object[].class);

    registerAlias("map", Map.class);
    registerAlias("hashmap", HashMap.class);
    registerAlias("list", List.class);
    registerAlias("arraylist", ArrayList.class);
    registerAlias("collection", Collection.class);
    registerAlias("iterator", Iterator.class);

    registerAlias("ResultSet", ResultSet.class);
  }

  /**
   * 解析别名
   *
   * @param string
   * @param <T>
   * @return
   */
  @SuppressWarnings("unchecked")
  // throws class cast exception as well if types cannot be assigned
  public <T> Class<T> resolveAlias(String string) {
    try {
      // 为空返回 null
      if (string == null) {
        return null;
      }
      // issue #748
      // 将别名转成小写, 在注册时就是使用的小写
      String key = string.toLowerCase(Locale.ENGLISH);
      Class<T> value;
      // 存在别名则直接方法,否则将其转class类
      if (typeAliases.containsKey(key)) {
        value = (Class<T>) typeAliases.get(key);
      } else {
        value = (Class<T>) Resources.classForName(string);
      }
      return value;
    } catch (ClassNotFoundException e) {
      throw new TypeException("Could not resolve type alias '" + string + "'.  Cause: " + e, e);
    }
  }

  /**
   * 注册指定包下的类别名
   *
   * @param packageName 包名
   */
  public void registerAliases(String packageName) {
    registerAliases(packageName, Object.class);
  }

  /**
   * 注册指定包下的类别名
   *
   * @param packageName 包名
   * @param superType   父类
   */
  public void registerAliases(String packageName, Class<?> superType) {
    ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
    // 获取指定包下指定父类的类
    resolverUtil.find(new ResolverUtil.IsA(superType), packageName);
    Set<Class<? extends Class<?>>> typeSet = resolverUtil.getClasses();
    // 遍历注册
    for (Class<?> type : typeSet) {
      // Ignore inner classes and interfaces (including package-info.java)
      // Skip also inner classes. See issue #6
      // 忽略内部类和接口(包括package-info.java)
      if (!type.isAnonymousClass() && !type.isInterface() && !type.isMemberClass()) {
        // 注册(使用class)
        registerAlias(type);
      }
    }
  }

  /**
   * 注册别名
   *
   * @param type class类型
   */
  public void registerAlias(Class<?> type) {
    // 调用 getSimpleName 方法获取类的简单名称(不包含包名的名称)
    String alias = type.getSimpleName();
    // 获取 @Alias 注解
    Alias aliasAnnotation = type.getAnnotation(Alias.class);
    // 如果 @Alias 注解存在则使用其指定的别名, 否则还是使用类的简单名称
    if (aliasAnnotation != null) {
      alias = aliasAnnotation.value();
    }
    // 调用重载方法注册
    registerAlias(alias, type);
  }

  /**
   * 注册别名
   *
   * @param alias 别名
   * @param value class类型
   */
  public void registerAlias(String alias, Class<?> value) {
    // 别名为空情况抛出异常
    if (alias == null) {
      throw new TypeException("The parameter alias cannot be null");
    }
    // issue #748
    // 将别名转为小写
    String key = alias.toLowerCase(Locale.ENGLISH);
    // 别名存在的情况下抛出异常
    if (typeAliases.containsKey(key) && typeAliases.get(key) != null && !typeAliases.get(key).equals(value)) {
      throw new TypeException("The alias '" + alias + "' is already mapped to the value '" + typeAliases.get(key).getName() + "'.");
    }
    // 保存关联关系
    typeAliases.put(key, value);
  }

  /**
   * 注册别名
   *
   * @param alias 别名
   * @param value class名称(含包名)
   */
  public void registerAlias(String alias, String value) {
    try {
      // 将 value 转换成 Class 类型, 调用重载方法进行注册
      registerAlias(alias, Resources.classForName(value));
    } catch (ClassNotFoundException e) {
      throw new TypeException("Error registering type alias " + alias + " for " + value + ". Cause: " + e, e);
    }
  }

  /**
   * Gets the type aliases.
   *
   * @return the type aliases
   * @since 3.2.2
   */
  public Map<String, Class<?>> getTypeAliases() {
    return Collections.unmodifiableMap(typeAliases);
  }

}
