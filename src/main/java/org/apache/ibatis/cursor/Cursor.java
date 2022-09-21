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
package org.apache.ibatis.cursor;

import java.io.Closeable;

/**
 * 游标接口
 *
 * 游标使用迭代器惰性地处理获取项。游标是一个完美的适合处理数百万项查询，通常不适合在内存中
 *
 * Cursor contract to handle fetching items lazily using an Iterator.
 * Cursors are a perfect fit to handle millions of items queries that would not normally fits in memory.
 * If you use collections in resultMaps then cursor SQL queries must be ordered (resultOrdered="true")
 * using the id columns of the resultMap.
 *
 * @see org.apache.ibatis.cursor.defaults.DefaultCursor
 * @author Guillaume Darmont / guillaume@dropinocean.com
 */
public interface Cursor<T> extends Closeable, Iterable<T> {

  /**
   * 游标是否开启
   *
   * @return true if the cursor has started to fetch items from database.
   */
  boolean isOpen();

  /**
   * 游标是否消费完成
   *
   * @return true if the cursor is fully consumed and has returned all elements matching the query.
   */
  boolean isConsumed();

  /**
   * 获取当前元素索引.第一项的索引为0
   *
   * Get the current item index. The first item has the index 0.
   *
   * @return -1 if the first cursor item has not been retrieved. The index of the current item retrieved.
   */
  int getCurrentIndex();
}
