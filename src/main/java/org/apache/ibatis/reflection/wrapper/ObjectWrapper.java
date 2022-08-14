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
package org.apache.ibatis.reflection.wrapper;

import java.util.List;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

/**
 * @author Clinton Begin
 */
public interface ObjectWrapper {

  /**
   * 获取属性值
   *
   * @param prop
   * @return
   */
  Object get(PropertyTokenizer prop);

  /**
   * 设置属性值
   *
   * @param prop
   * @param value
   */
  void set(PropertyTokenizer prop, Object value);

  /**
   * 查询属性
   *
   * @param name
   * @param useCamelCaseMapping
   * @return
   */
  String findProperty(String name, boolean useCamelCaseMapping);

  /**
   * 获取所有可读属性的名称
   *
   * @return
   */
  String[] getGetterNames();

  /**
   * 获取所有可写属性的名称
   *
   * @return
   */
  String[] getSetterNames();

  /**
   * 获取setter类型,支持表达式
   *
   * @return
   */
  Class<?> getSetterType(String name);

  /**
   * 获取getter类型,支持表达式
   *
   * @param name
   * @return
   */
  Class<?> getGetterType(String name);

  /**
   * 判断是否存在setter,支持表达式
   *
   * @param name
   * @return
   */
  boolean hasSetter(String name);

  /**
   * 判断是否存在getter,支持表达式
   *
   * @param name
   * @return
   */
  boolean hasGetter(String name);

  /**
   * 实例化属性并赋值,同时返回以属性创建的 MetaObject 对象
   *
   * @param name
   * @param prop
   * @param objectFactory
   * @return
   */
  MetaObject instantiatePropertyValue(String name, PropertyTokenizer prop, ObjectFactory objectFactory);

  /**
   * 是否是集合
   *
   * @return
   */
  boolean isCollection();

  /**
   * 添加元素
   *
   * @param element
   */
  void add(Object element);

  /**
   * 添加列表
   *
   * @param element
   * @param <E>
   */
  <E> void addAll(List<E> element);

}
