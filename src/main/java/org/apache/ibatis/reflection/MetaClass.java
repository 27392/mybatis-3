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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

/**
 * 通过 {@link Reflector} 与 {@link PropertyTokenizer} 组合使用
 * 实现对复杂的属性表达式的解析, 并实现了获取指定属性描述信息的功能
 *
 * @author Clinton Begin
 */
public class MetaClass {

  /**
   * ReflectorFactory 对象, 用于生产 Reflector 对象
   */
  private final ReflectorFactory reflectorFactory;

  /**
   * Reflector对象,保存创建时指定类的元信息
   */
  private final Reflector reflector;

  /**
   * 私有构造器,只能通过{@link #forClass(Class, ReflectorFactory)}创建
   *
   * @param type
   * @param reflectorFactory
   */
  private MetaClass(Class<?> type, ReflectorFactory reflectorFactory) {
    // reflectorFactory 赋值
    this.reflectorFactory = reflectorFactory;

    // 使用 ReflectorFactory 创建 Reflector 对象
    this.reflector = reflectorFactory.findForClass(type);
  }

  /**
   * 使用静态方法创建 MetaClass 对象
   *
   * @param type
   * @param reflectorFactory
   * @return
   */
  public static MetaClass forClass(Class<?> type, ReflectorFactory reflectorFactory) {
    return new MetaClass(type, reflectorFactory);
  }

  /**
   * 通过属性名创建 MetaClass 对象
   *
   * @param name
   * @return
   */
  public MetaClass metaClassForProperty(String name) {
    Class<?> propType = reflector.getGetterType(name);
    return MetaClass.forClass(propType, reflectorFactory);
  }

  /**
   * 只检查 '.' 的表达式,并不检查带下标表达式
   *
   * @param name  表达式
   * @return 找得到的话返回值与`name`相等, 完全找不到的话返回null
   */
  public String findProperty(String name) {
    StringBuilder prop = buildProperty(name, new StringBuilder());
    return prop.length() > 0 ? prop.toString() : null;
  }

  public String findProperty(String name, boolean useCamelCaseMapping) {
    if (useCamelCaseMapping) {
      name = name.replace("_", "");
    }
    return findProperty(name);
  }

  public String[] getGetterNames() {
    return reflector.getGetablePropertyNames();
  }

  public String[] getSetterNames() {
    return reflector.getSetablePropertyNames();
  }

  /**
   * 获取 Setter 方法类型, 根据属性表达式
   *
   * @param name
   * @return
   */
  public Class<?> getSetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      MetaClass metaProp = metaClassForProperty(prop.getName());
      return metaProp.getSetterType(prop.getChildren());
    } else {
      return reflector.getSetterType(prop.getName());
    }
  }

  /**
   * 获取 Getter 方法类型, 根据属性表达式
   *
   * @param name
   * @return
   */
  public Class<?> getGetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    // 判断是否存在子表达式(递归出口)
    if (prop.hasNext()) {
      // 获取第一个属性的类型, 并以该类型创建一个新的MateClass对象
      MetaClass metaProp = metaClassForProperty(prop);
      // 使用新的MateClass对象解析子表达式(递归)
      return metaProp.getGetterType(prop.getChildren());
    }
    // issue #506. Resolve the type inside a Collection Object
    // 不含子表达式, 调用getGetterType解析返回类型
    return getGetterType(prop);
  }

  private MetaClass metaClassForProperty(PropertyTokenizer prop) {
    // 获取表达式属性的类型
    Class<?> propType = getGetterType(prop);
    return MetaClass.forClass(propType, reflectorFactory);
  }

  /**
   * 获取getter方法实际的返回类型
   *
   * 不支持参数化类型多层嵌套,只支持一层
   *
   * @param prop
   * @return
   */
  private Class<?> getGetterType(PropertyTokenizer prop) {
    // 获取Getter方法class类型
    Class<?> type = reflector.getGetterType(prop.getName());
    // 如果是带索引的并且是集合
    if (prop.getIndex() != null && Collection.class.isAssignableFrom(type)) {
      // 获取集合泛型
      Type returnType = getGenericGetterType(prop.getName());
      // 如果是参数化类型
      if (returnType instanceof ParameterizedType) {
        // 获取实际的类型参数
        Type[] actualTypeArguments = ((ParameterizedType) returnType).getActualTypeArguments();
        // 判断是否设置泛型,Collection的子类一般只有一个泛型. 所以判断长度为 1
        if (actualTypeArguments != null && actualTypeArguments.length == 1) {
          // 获取泛型
          returnType = actualTypeArguments[0];
          // 判断是class则直接方法
          if (returnType instanceof Class) {
            type = (Class<?>) returnType;
            // 如果是参数化类型则取原始类型
          } else if (returnType instanceof ParameterizedType) {
            type = (Class<?>) ((ParameterizedType) returnType).getRawType();
          }
        }
      }
    }
    return type;
  }

  /**
   * 获取getter方法的泛型
   *
   * @param propertyName
   * @return
   */
  private Type getGenericGetterType(String propertyName) {
    try {
      // 获取调用器
      Invoker invoker = reflector.getGetInvoker(propertyName);
      if (invoker instanceof MethodInvoker) {
        Field declaredMethod = MethodInvoker.class.getDeclaredField("method");
        declaredMethod.setAccessible(true);
        Method method = (Method) declaredMethod.get(invoker);
        return TypeParameterResolver.resolveReturnType(method, reflector.getType());
      } else if (invoker instanceof GetFieldInvoker) {
        Field declaredField = GetFieldInvoker.class.getDeclaredField("field");
        declaredField.setAccessible(true);
        Field field = (Field) declaredField.get(invoker);
        return TypeParameterResolver.resolveFieldType(field, reflector.getType());
      }
    } catch (NoSuchFieldException | IllegalAccessException e) {
      // Ignored
    }
    return null;
  }

  /**
   * 是否存在 Setter 方法, 根据属性表达式判断
   *
   * @param name
   * @return
   */
  public boolean hasSetter(String name) {
    // 使用 PropertyTokenizer 解析属性表达式
    PropertyTokenizer prop = new PropertyTokenizer(name);
    // 是否存在子表达式(递归出口)
    if (prop.hasNext()) {
      // 第一个属性是否存在setter方法
      if (reflector.hasSetter(prop.getName())) {
        // 获取第一个属性的类型, 并以该类型创建一个新的MateClass对象
        MetaClass metaProp = metaClassForProperty(prop.getName());
        // 使用新的MateClass对象解析子表达式(递归)
        return metaProp.hasSetter(prop.getChildren());
      } else {
        return false;
      }
    } else {
      // 不含子表达式,调用Reflector对象的hasSetter来判断
      return reflector.hasSetter(prop.getName());
    }
  }

  /**
   * 是否存在 Getter 方法, 根据属性表达式判断
   * @param name
   * @return
   */
  public boolean hasGetter(String name) {
    // 使用 PropertyTokenizer 解析属性表达式
    PropertyTokenizer prop = new PropertyTokenizer(name);
    // 是否存在子表达式(递归出口)
    if (prop.hasNext()) {
      // 第一个属性是否存在getter方法
      if (reflector.hasGetter(prop.getName())) {
        // 获取第一个属性的类型, 并以该类型创建一个新的MateClass对象
        MetaClass metaProp = metaClassForProperty(prop);
        // 使用新的MateClass对象解析子表达式(递归)
        return metaProp.hasGetter(prop.getChildren());
      } else {
        return false;
      }
    } else {
      // 不含子表达式,调用Reflector对象的hasGetter来判断
      return reflector.hasGetter(prop.getName());
    }
  }

  /**
   * 获取 getter Invoker对象, 通过名称(不支持表达式)
   *
   * @param name
   * @return
   */
  public Invoker getGetInvoker(String name) {
    return reflector.getGetInvoker(name);
  }

  /**
   * 获取 setter Invoker对象, 通过名称(不支持表达式)
   *
   * @param name
   * @return
   */
  public Invoker getSetInvoker(String name) {
    return reflector.getSetInvoker(name);
  }

  private StringBuilder buildProperty(String name, StringBuilder builder) {
    // 使用 PropertyTokenizer 解析属性表达式
    PropertyTokenizer prop = new PropertyTokenizer(name);
    // 是否存在子表达式
    if (prop.hasNext()) {
      // 根据解析的名称获取类属性
      String propertyName = reflector.findPropertyName(prop.getName());
      if (propertyName != null) {
        // 追加属性名
        builder.append(propertyName);
        builder.append(".");
        // 为该属性创建对呀的MetaClass对象
        MetaClass metaProp = metaClassForProperty(propertyName);
        // 递归解析子表达式
        metaProp.buildProperty(prop.getChildren(), builder);
      }
    } else {
      String propertyName = reflector.findPropertyName(name);
      if (propertyName != null) {
        builder.append(propertyName);
      }
    }
    return builder;
  }

  /**
   * 是否存在默认构造器
   *
   * @return
   */
  public boolean hasDefaultConstructor() {
    return reflector.hasDefaultConstructor();
  }

}
