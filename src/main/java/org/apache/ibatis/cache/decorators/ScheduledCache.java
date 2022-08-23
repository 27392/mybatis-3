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

import java.util.concurrent.TimeUnit;

import org.apache.ibatis.cache.Cache;

/**
 * 周期性缓存清理装饰器
 *
 * 在固定的时间间隔会将缓存全部清空(调用缓存操作时判断)
 *
 * @author Clinton Begin
 */
public class ScheduledCache implements Cache {

  // 被装饰的类
  private final Cache delegate;
  // 清理时间间隔(默认一个小时)
  protected long clearInterval;
  // 最后清理时间
  protected long lastClear;

  public ScheduledCache(Cache delegate) {
    this.delegate = delegate;
    this.clearInterval = TimeUnit.HOURS.toMillis(1);
    this.lastClear = System.currentTimeMillis();
  }

  public void setClearInterval(long clearInterval) {
    this.clearInterval = clearInterval;
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    // 判断是否到时间,需要清空缓存
    clearWhenStale();
    return delegate.getSize();
  }

  @Override
  public void putObject(Object key, Object object) {
    // 判断是否到时间,需要清空缓存
    clearWhenStale();
    delegate.putObject(key, object);
  }

  @Override
  public Object getObject(Object key) {
    // 判断是否到时间,需要清空缓存
    return clearWhenStale() ? null : delegate.getObject(key);
  }

  @Override
  public Object removeObject(Object key) {
    // 判断是否到时间,需要清空缓存
    clearWhenStale();
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    // 清空缓存前记录最后清理时间
    lastClear = System.currentTimeMillis();
    // 清空缓存
    delegate.clear();
  }

  @Override
  public int hashCode() {
    return delegate.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return delegate.equals(obj);
  }

  /**
   * 判断是否到时间,需要清空缓存
   *
   * @return
   */
  private boolean clearWhenStale() {
    // (当前时间 - 最后一次清楚时间 > 清理时间间隔) 清空所有的缓存
    if (System.currentTimeMillis() - lastClear > clearInterval) {
      // 清空所有的缓存
      clear();
      return true;
    }
    return false;
  }

}
