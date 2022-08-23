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

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.Deque;
import java.util.LinkedList;

import org.apache.ibatis.cache.Cache;

/**
 * 软引用缓存装饰器
 *
 * 当虚拟机内存不足时,GC会回收被软引用指向的对象
 *
 * Soft Reference cache decorator
 * Thanks to Dr. Heinz Kabutz for his guidance here.
 *
 * @author Clinton Begin
 */
public class SoftCache implements Cache {
  // 强引用集合,最近使用的一部分缓存不会被GC回收
  private final Deque<Object> hardLinksToAvoidGarbageCollection;
  // 引用队列,用于记录已经被GC回收的缓存项对应的 SoftEntry 对象
  private final ReferenceQueue<Object> queueOfGarbageCollectedEntries;
  // 被装饰的类
  private final Cache delegate;
  // 强连接个数(默认256)
  private int numberOfHardLinks;

  public SoftCache(Cache delegate) {
    this.delegate = delegate;
    this.numberOfHardLinks = 256;
    this.hardLinksToAvoidGarbageCollection = new LinkedList<>();
    this.queueOfGarbageCollectedEntries = new ReferenceQueue<>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    // 清除已经被GC的 SoftEntry
    removeGarbageCollectedItems();
    return delegate.getSize();
  }

  public void setSize(int size) {
    this.numberOfHardLinks = size;
  }

  @Override
  public void putObject(Object key, Object value) {
    // 清除已经被GC的 SoftEntry
    removeGarbageCollectedItems();
    // 缓存的 value 是 SoftEntry 对象它是 SoftReference 的子类
    delegate.putObject(key, new SoftEntry(key, value, queueOfGarbageCollectedEntries));
  }

  @Override
  public Object getObject(Object key) {
    Object result = null;
    // 获取缓存值
    @SuppressWarnings("unchecked") // assumed delegate cache is totally managed by this cache
    SoftReference<Object> softReference = (SoftReference<Object>) delegate.getObject(key);
    // 检查是否存在
    if (softReference != null) {
      // 从软引用中获取
      result = softReference.get();
      // 被GC回收的情况下删除缓存
      if (result == null) {
        delegate.removeObject(key);
      } else {
        // 未被GC回收的情况下
        // See #586 (and #335) modifications need more than a read lock
        synchronized (hardLinksToAvoidGarbageCollection) {
          // 将缓存项添加到(头部)强连接集合(以免垃圾回收)
          hardLinksToAvoidGarbageCollection.addFirst(result);
          // 强连接数量是否超过数量
          if (hardLinksToAvoidGarbageCollection.size() > numberOfHardLinks) {
            // 从尾部超过删除元素(最早的元素)
            hardLinksToAvoidGarbageCollection.removeLast();
          }
        }
      }
    }
    return result;
  }

  @Override
  public Object removeObject(Object key) {
    // 清除已经被GC的 SoftEntry
    removeGarbageCollectedItems();
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    // 清除强引用集合
    synchronized (hardLinksToAvoidGarbageCollection) {
      hardLinksToAvoidGarbageCollection.clear();
    }
    // 清除已经被GC的 SoftEntry
    removeGarbageCollectedItems();
    delegate.clear();
  }

  /**
   * 删除所有被GC的 SoftEntry
   */
  private void removeGarbageCollectedItems() {
    SoftEntry sv;
    while ((sv = (SoftEntry) queueOfGarbageCollectedEntries.poll()) != null) {
      delegate.removeObject(sv.key);
    }
  }

  private static class SoftEntry extends SoftReference<Object> {
    // key
    private final Object key;

    SoftEntry(Object key, Object value, ReferenceQueue<Object> garbageCollectionQueue) {
      // value 的引用是软引用,
      super(value, garbageCollectionQueue);
      // key 是强引用
      this.key = key;
    }
  }

}
