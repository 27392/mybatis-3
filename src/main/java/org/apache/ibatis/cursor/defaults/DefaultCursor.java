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
package org.apache.ibatis.cursor.defaults;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.executor.resultset.ResultSetWrapper;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * 默认的游标实现 (非线程安全)
 *
 * This is the default implementation of a MyBatis Cursor.
 * This implementation is not thread safe.
 *
 * @author Guillaume Darmont / guillaume@dropinocean.com
 */
public class DefaultCursor<T> implements Cursor<T> {

  // ResultSetHandler stuff
  // DefaultResultSetHandler 对象
  private final DefaultResultSetHandler resultSetHandler;
  // ResultMap 对象
  private final ResultMap resultMap;
  // ResultSetWrapper 对象
  private final ResultSetWrapper rsw;
  // RowBounds 对象
  private final RowBounds rowBounds;

  // 结果集包装对象
  protected final ObjectWrapperResultHandler<T> objectWrapperResultHandler = new ObjectWrapperResultHandler<>();

  // 游标迭代器
  private final CursorIterator cursorIterator = new CursorIterator();
  // 迭代器是否进行过检索 (游标只能打开一次)
  private boolean iteratorRetrieved;

  // 游标状态; 默认状态 CREATED
  private CursorStatus status = CursorStatus.CREATED;
  //
  private int indexWithRowBound = -1;

  /**
   * 游标状态
   */
  private enum CursorStatus {

    /**
     * 新创建的游标、数据库结果集消费还没有开始
     * A freshly created cursor, database ResultSet consuming has not started.
     */
    CREATED,
    /**
     * 当前正在使用游标，数据库结果集消费已经开始。
     * A cursor currently in use, database ResultSet consuming has started.
     */
    OPEN,
    /**
     * 已关闭的游标，结果集并未完全消费
     * A closed cursor, not fully consumed.
     */
    CLOSED,
    /**
     * 已关闭的游标，结果集以完全消费
     * A fully consumed cursor, a consumed cursor is always closed.
     */
    CONSUMED
  }

  public DefaultCursor(DefaultResultSetHandler resultSetHandler, ResultMap resultMap, ResultSetWrapper rsw, RowBounds rowBounds) {
    this.resultSetHandler = resultSetHandler;
    this.resultMap = resultMap;
    this.rsw = rsw;
    this.rowBounds = rowBounds;
  }

  @Override
  public boolean isOpen() {
    return status == CursorStatus.OPEN;
  }

  @Override
  public boolean isConsumed() {
    return status == CursorStatus.CONSUMED;
  }

  @Override
  public int getCurrentIndex() {
    return rowBounds.getOffset() + cursorIterator.iteratorIndex;
  }

  @Override
  public Iterator<T> iterator() {
    if (iteratorRetrieved) {
      throw new IllegalStateException("Cannot open more than one iterator on a Cursor");
    }
    if (isClosed()) {
      throw new IllegalStateException("A Cursor is already closed.");
    }
    // 修改迭代器标识
    iteratorRetrieved = true;
    return cursorIterator;
  }

  @Override
  public void close() {
    if (isClosed()) {
      return;
    }

    ResultSet rs = rsw.getResultSet();
    try {
      if (rs != null) {
        rs.close();
      }
    } catch (SQLException e) {
      // ignore
    } finally {
      status = CursorStatus.CLOSED;
    }
  }

  /**
   * 获取下一行数据
   *
   * @return
   */
  protected T fetchNextUsingRowBound() {
    // 获取一行数据库记录(并且 indexWithRowBound 递增)
    T result = fetchNextObjectFromDatabase();

    // 跳到指定行
    while (objectWrapperResultHandler.fetched && indexWithRowBound < rowBounds.getOffset()) {
      result = fetchNextObjectFromDatabase();
    }
    return result;
  }

  /**
   * 从结果集中获取下一行数据
   * @return
   */
  protected T fetchNextObjectFromDatabase() {
    // 判断游标是否关闭
    if (isClosed()) {
      return null;
    }

    try {
      // 更新结果处理器中的标识与游标的状态
      objectWrapperResultHandler.fetched = false;
      status = CursorStatus.OPEN;

      // 判断结果集是否关闭
      if (!rsw.getResultSet().isClosed()) {
        // 获取一行记录 (结果对象保存在 objectWrapperResultHandler.result 中)
        resultSetHandler.handleRowValues(rsw, resultMap, objectWrapperResultHandler, RowBounds.DEFAULT, null);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }

    // 记录结果对象
    T next = objectWrapperResultHandler.result;
    // 如果获取到对象, indexWithRowBound 递增
    if (objectWrapperResultHandler.fetched) {
      indexWithRowBound++;
    }

    // No more object or limit reached
    // 如果没有更多的记录则关闭游标并修改状态
    if (!objectWrapperResultHandler.fetched || getReadItemsCount() == rowBounds.getOffset() + rowBounds.getLimit()) {
      // 关闭
      close();
      // 修改游标状态为 CONSUMED (全部消费完成)
      status = CursorStatus.CONSUMED;
    }
    // 清空 objectWrapperResultHandler.result
    objectWrapperResultHandler.result = null;
    return next;
  }

  private boolean isClosed() {
    return status == CursorStatus.CLOSED || status == CursorStatus.CONSUMED;
  }

  private int getReadItemsCount() {
    return indexWithRowBound + 1;
  }

  /**
   * 结果对象包装类
   *
   * @param <T>
   */
  protected static class ObjectWrapperResultHandler<T> implements ResultHandler<T> {

    // 结果对象
    protected T result;
    // 标识是否获取到对象
    protected boolean fetched;

    @Override
    public void handleResult(ResultContext<? extends T> context) {
      // 获取结果对象
      this.result = context.getResultObject();
      // 停止继续获取
      context.stop();
      // 修改 fetched 标识
      fetched = true;
    }
  }

  protected class CursorIterator implements Iterator<T> {

    /**
     * 保存下一个要返回的对象
     * Holder for the next object to be returned.
     */
    T object;

    /**
     * 索引
     * Index of objects returned using next(), and as such, visible to users.
     */
    int iteratorIndex = -1;

    @Override
    public boolean hasNext() {
      // 判断是否调用过 hasNext 方法
      if (!objectWrapperResultHandler.fetched) {
        object = fetchNextUsingRowBound();
      }
      return objectWrapperResultHandler.fetched;
    }

    @Override
    public T next() {
      // Fill next with object fetched from hasNext()
      T next = object;

      // 判断是否调用 hasNext() 方法, 没有则获取下一行元素
      if (!objectWrapperResultHandler.fetched) {
        next = fetchNextUsingRowBound();
      }

      // 如果能获取到结果,则清除标识等信息
      if (objectWrapperResultHandler.fetched) {
        objectWrapperResultHandler.fetched = false;
        // 下一个对象为空
        object = null;
        // 索引递增
        iteratorIndex++;
        // 返回结果
        return next;
      }
      throw new NoSuchElementException();
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException("Cannot remove element from Cursor");
    }
  }
}
