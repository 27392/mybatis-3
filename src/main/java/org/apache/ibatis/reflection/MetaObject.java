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

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.reflection.wrapper.BeanWrapper;
import org.apache.ibatis.reflection.wrapper.CollectionWrapper;
import org.apache.ibatis.reflection.wrapper.MapWrapper;
import org.apache.ibatis.reflection.wrapper.ObjectWrapper;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;

/**
 * @author Clinton Begin
 */
public class MetaObject {

  /**
   * 原始的javaBean对象
   */
  private final Object originalObject;

  /**
   * 对象包装
   */
  private final ObjectWrapper objectWrapper;

  /**
   * 对象工厂
   */
  private final ObjectFactory objectFactory;

  /**
   * ObjectWrapperFactory 负责生产 ObjectWrapper 对象
   */
  private final ObjectWrapperFactory objectWrapperFactory;

  /**
   * ReflectorFactory 负责生产 Reflector 对象
   */
  private final ReflectorFactory reflectorFactory;

  /**
   * 私有构造
   *
   * @param object
   * @param objectFactory
   * @param objectWrapperFactory
   * @param reflectorFactory
   */
  private MetaObject(Object object, ObjectFactory objectFactory, ObjectWrapperFactory objectWrapperFactory, ReflectorFactory reflectorFactory) {
    this.originalObject = object;
    this.objectFactory = objectFactory;
    this.objectWrapperFactory = objectWrapperFactory;
    this.reflectorFactory = reflectorFactory;

    // 若对象已经是 ObjectWrapper 类型则直接使用
    if (object instanceof ObjectWrapper) {
      this.objectWrapper = (ObjectWrapper) object;
    } else if (objectWrapperFactory.hasWrapperFor(object)) {
      // 若 ObjectWrapperFactory 能包装对象,则优先使用 ObjectWrapperFactory 创建
      // 而 hasWrapperFor 方法始终返回 false, 所以在默认情况下是不会走这里.
      // 开发者可以实现 ObjectWrapperFactory 来拓展
      this.objectWrapper = objectWrapperFactory.getWrapperFor(this, object);
    } else if (object instanceof Map) {
      // 如果是 Map 类型,则创建 MapWrapper 对象
      this.objectWrapper = new MapWrapper(this, (Map) object);
    } else if (object instanceof Collection) {
      // 如果是 Collection 类型,则创建 CollectionWrapper 对象
      this.objectWrapper = new CollectionWrapper(this, (Collection) object);
    } else {
      // 否则创建 BeanWrapper 对象
      this.objectWrapper = new BeanWrapper(this, object);
    }
  }

  /**
   * 静态方法创建对象
   *
   * 可以使用该方法便捷的创建 {@link SystemMetaObject#forObject(Object)}
   *
   * @param object
   * @param objectFactory
   * @param objectWrapperFactory
   * @param reflectorFactory
   * @return
   */
  public static MetaObject forObject(Object object, ObjectFactory objectFactory, ObjectWrapperFactory objectWrapperFactory, ReflectorFactory reflectorFactory) {
    if (object == null) {
      return SystemMetaObject.NULL_META_OBJECT;
    } else {
      return new MetaObject(object, objectFactory, objectWrapperFactory, reflectorFactory);
    }
  }

  public ObjectFactory getObjectFactory() {
    return objectFactory;
  }

  public ObjectWrapperFactory getObjectWrapperFactory() {
    return objectWrapperFactory;
  }

  public ReflectorFactory getReflectorFactory() {
    return reflectorFactory;
  }

  public Object getOriginalObject() {
    return originalObject;
  }

  public String findProperty(String propName, boolean useCamelCaseMapping) {
    return objectWrapper.findProperty(propName, useCamelCaseMapping);
  }

  public String[] getGetterNames() {
    return objectWrapper.getGetterNames();
  }

  public String[] getSetterNames() {
    return objectWrapper.getSetterNames();
  }

  public Class<?> getSetterType(String name) {
    return objectWrapper.getSetterType(name);
  }

  public Class<?> getGetterType(String name) {
    return objectWrapper.getGetterType(name);
  }

  public boolean hasSetter(String name) {
    return objectWrapper.hasSetter(name);
  }

  public boolean hasGetter(String name) {
    return objectWrapper.hasGetter(name);
  }

  /**
   * 获取属性
   * @param name
   * @return
   */
  public Object getValue(String name) {
    // 创建分词器对象
    PropertyTokenizer prop = new PropertyTokenizer(name);
    // 判断是否存在子表达式
    if (prop.hasNext()) {
      // 利用索引名称创建新的MetaObject对象。为什么不用name呢？ 因为可能存在包含下标
      MetaObject metaValue = metaObjectForProperty(prop.getIndexedName());
      if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
        return null;
      } else {
        // 使用创建好的MetaObject对象继续解析子表达式（递归）
        return metaValue.getValue(prop.getChildren());
      }
    } else {
      // 如果不存在子表达式，则使用 objectWrapper 直接获取属性值
      return objectWrapper.get(prop);
    }
  }

  /**
   * 设置属性
   *
   * @param name
   * @param value
   */
  public void setValue(String name, Object value) {
    // 创建分词器对象
    PropertyTokenizer prop = new PropertyTokenizer(name);
    // 判断是否存在子表达式
    if (prop.hasNext()) {
      // 利用索引名称创建新的MetaObject对象。为什么不用name呢？ 因为可能存在包含下标
      MetaObject metaValue = metaObjectForProperty(prop.getIndexedName());
      // 如果返回的null
      if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
        if (value == null) {
          // don't instantiate child path if value is null
          return;
        } else {
          // 例如 roles[0]
          // 根据name创建属性对象，并将创建的属性对象设置到当前对象中。然后再返回使用子属性创建的 MetaObject
          metaValue = objectWrapper.instantiatePropertyValue(name, prop, objectFactory);
        }
      }
      // 设置属性（递归）
      metaValue.setValue(prop.getChildren(), value);
    } else {
      // 如果不存在子表达式，则使用 objectWrapper 直接设置属性值
      objectWrapper.set(prop, value);
    }
  }

  public MetaObject metaObjectForProperty(String name) {
    // 获取属性值
    Object value = getValue(name);
    // 根据属性值创建新的MetaObject
    return MetaObject.forObject(value, objectFactory, objectWrapperFactory, reflectorFactory);
  }

  public ObjectWrapper getObjectWrapper() {
    return objectWrapper;
  }

  public boolean isCollection() {
    return objectWrapper.isCollection();
  }

  public void add(Object element) {
    objectWrapper.add(element);
  }

  public <E> void addAll(List<E> list) {
    objectWrapper.addAll(list);
  }

}
