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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;

/**
 * 阻塞缓存装饰器
 *
 * EhCache 的 BlockingCache 装饰器的简单而低效的版本。当在缓存中找不到元素时，它为缓存键设置锁。
 * 这样，其他线程将等待该元素被填充，而不是到达数据库。就其本质而言，如果使用不当，该实现可能会导致死锁
 *
 * <p>Simple blocking decorator
 *
 * <p>Simple and inefficient version of EhCache's BlockingCache decorator.
 * It sets a lock over a cache key when the element is not found in cache.
 * This way, other threads will wait until this element is filled instead of hitting the database.
 *
 * <p>By its nature, this implementation can cause deadlock when used incorrectly.
 *
 * @author Eduardo Macarron
 *
 */
public class BlockingCache implements Cache {

  // 阻塞超时时长(毫秒)
  private long timeout;
  // 被装饰的类
  private final Cache delegate;
  // 保存了缓存 key 对应的 CountDownLatch
  private final ConcurrentHashMap<Object, CountDownLatch> locks;

  public BlockingCache(Cache delegate) {
    this.delegate = delegate;
    this.locks = new ConcurrentHashMap<>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  @Override
  public void putObject(Object key, Object value) {
    try {
      // 缓存数据
      delegate.putObject(key, value);
    } finally {
      // 释放锁 (缓存等待线程)
      releaseLock(key);
    }
  }

  /**
   * 获取缓存项
   *
   * 不为空的情况下释放锁,
   * 为空不释放锁,一般是需要将结果放入缓存中(这两个操作是一起的)
   * 还有的情况是事务回滚了,也是释放锁
   *
   * 这也解释了这两个方法只需要释放锁
   * @see #putObject(Object, Object)
   * @see #removeObject(Object)
   *
   * @param key
   *          The key
   * @return
   */
  @Override
  public Object getObject(Object key) {
    // 获取锁
    acquireLock(key);
    // 获取对应的缓存项
    Object value = delegate.getObject(key);
    // 当获取的缓存项不为空时,将锁释放;否则不释放锁
    if (value != null) {
      // 释放锁(唤醒等待线程)
      releaseLock(key);
    }
    return value;
  }

  /**
   * 事务回滚时调用
   *
   * @param key
   *          The key
   * @return
   */
  @Override
  public Object removeObject(Object key) {
    // despite of its name, this method is called only to release locks
    // 释放锁
    releaseLock(key);
    return null;
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  /**
   * 获取锁
   *
   * @param key
   */
  private void acquireLock(Object key) {
    // 创建门闩
    CountDownLatch newLatch = new CountDownLatch(1);
    while (true) {
      // 判断门闩是否存在
      CountDownLatch latch = locks.putIfAbsent(key, newLatch);
      // 不存在而表示没有人持有锁
      if (latch == null) {
        break;
      }
      // 门闩存在表示有人持有锁
      try {
        // 等待
        if (timeout > 0) {
          boolean acquired = latch.await(timeout, TimeUnit.MILLISECONDS);
          // 超过等待时间, 抛出异常
          if (!acquired) {
            throw new CacheException(
                "Couldn't get a lock in " + timeout + " for the key " + key + " at the cache " + delegate.getId());
          }
        } else {
          // 无限制等待
          latch.await();
        }
      } catch (InterruptedException e) {
        throw new CacheException("Got interrupted while trying to acquire lock for key " + key, e);
      }
    }
  }

  /**
   * 释放锁
   * @param key
   */
  private void releaseLock(Object key) {
    // 删除门闩
    CountDownLatch latch = locks.remove(key);
    if (latch == null) {
      throw new IllegalStateException("Detected an attempt at releasing unacquired lock. This should never happen.");
    }
    // 门闩减一(等价与释放锁)
    latch.countDown();
  }

  public long getTimeout() {
    return timeout;
  }

  public void setTimeout(long timeout) {
    this.timeout = timeout;
  }
}
