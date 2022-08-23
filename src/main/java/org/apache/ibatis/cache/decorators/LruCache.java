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
package org.apache.ibatis.cache.decorators;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.ibatis.cache.Cache;

/**
 * Lru (最近最少使用) 缓存装饰器
 *
 * Lru (least recently used) cache decorator.
 *
 * @author Clinton Begin
 */
public class LruCache implements Cache {

  // 被装饰的类
  private final Cache delegate;
  // 缓存 key 记录, 使用 LinkedHashMap
  private Map<Object, Object> keyMap;
  // 最少被使用的缓存项的 key
  private Object eldestKey;

  public LruCache(Cache delegate) {
    this.delegate = delegate;
    // 设置默认大小
    setSize(1024);
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  public void setSize(final int size) {
    // accessOrder 参数为 true 表示会按照访问的元素来排序
    keyMap = new LinkedHashMap<Object, Object>(size, .75F, true) {
      private static final long serialVersionUID = 4267176411845948333L;

      // 在调用 put() 方法时会调用该方法
      @Override
      protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
        // 判断 map 的数量是否超过设置的数量
        boolean tooBig = size() > size;
        if (tooBig) {
          // 记录最少被使用的缓存项
          eldestKey = eldest.getKey();
        }
        return tooBig;
      }
    };
  }

  @Override
  public void putObject(Object key, Object value) {
    delegate.putObject(key, value);
    // 记录缓存key,并检查数量是超过上限
    cycleKeyList(key);
  }

  @Override
  public Object getObject(Object key) {
    // 获取属性,意义在于让 LinkedHashMap 知道被使用了
    keyMap.get(key); // touch
    return delegate.getObject(key);
  }

  @Override
  public Object removeObject(Object key) {
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    delegate.clear();
    keyMap.clear();
  }

  /**
   * 记录缓存key,并检查数量是超过上限
   *
   * @param key
   */
  private void cycleKeyList(Object key) {
    // 记录缓存 key
    keyMap.put(key, key);
    // eldestKey 属性不为空,表示达到了缓存上限
    if (eldestKey != null) {
      // 删除最少被使用的缓存项
      delegate.removeObject(eldestKey);
      // 将 eldestKey 属性置空
      eldestKey = null;
    }
  }

}
