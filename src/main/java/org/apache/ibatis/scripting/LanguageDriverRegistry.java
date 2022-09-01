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
package org.apache.ibatis.scripting;

import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.util.MapUtil;

/**
 * 语言驱动注册器
 *
 * 缓存了语言驱动类
 *
 * @author Frank D. Martinez [mnesarco]
 */
public class LanguageDriverRegistry {

  // 缓存
  private final Map<Class<? extends LanguageDriver>, LanguageDriver> LANGUAGE_DRIVER_MAP = new HashMap<>();

  // 默认驱动类型
  private Class<? extends LanguageDriver> defaultDriverClass;

  /**
   * 注册驱动
   *
   * @param cls
   */
  public void register(Class<? extends LanguageDriver> cls) {
    if (cls == null) {
      throw new IllegalArgumentException("null is not a valid Language Driver");
    }
    MapUtil.computeIfAbsent(LANGUAGE_DRIVER_MAP, cls, k -> {
      try {
        return k.getDeclaredConstructor().newInstance();
      } catch (Exception ex) {
        throw new ScriptingException("Failed to load language driver for " + cls.getName(), ex);
      }
    });
  }

  /**
   * 注册驱动
   *
   * @param instance
   */
  public void register(LanguageDriver instance) {
    if (instance == null) {
      throw new IllegalArgumentException("null is not a valid Language Driver");
    }
    Class<? extends LanguageDriver> cls = instance.getClass();
    if (!LANGUAGE_DRIVER_MAP.containsKey(cls)) {
      LANGUAGE_DRIVER_MAP.put(cls, instance);
    }
  }

  /**
   * 获取指定类型驱动
   *
   * @param cls
   * @return
   */
  public LanguageDriver getDriver(Class<? extends LanguageDriver> cls) {
    return LANGUAGE_DRIVER_MAP.get(cls);
  }

  /**
   * 获取默认驱动
   *
   * @return
   */
  public LanguageDriver getDefaultDriver() {
    return getDriver(getDefaultDriverClass());
  }

  /**
   * 获取默认的驱动类型
   *
   * @return
   */
  public Class<? extends LanguageDriver> getDefaultDriverClass() {
    return defaultDriverClass;
  }

  /**
   * 设置默认驱动类型.同时注册驱动
   *
   * @param defaultDriverClass
   */
  public void setDefaultDriverClass(Class<? extends LanguageDriver> defaultDriverClass) {
    register(defaultDriverClass);
    this.defaultDriverClass = defaultDriverClass;
  }

}
