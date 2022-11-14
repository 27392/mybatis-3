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
package org.apache.ibatis.executor;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * 可重用的执行器 (重用 Statement)
 * 重用 Statement 可以优化 SQL 预编译的开销与创建和销毁 Statement 对象的开销, 从而提高性能
 *
 * 原理是在执行SQL后并不会关闭 Statement 而是利用 HashMap 将 Statement 进行缓存
 * 当调用 {@link #close(boolean)},{@link #commit(boolean)},{@link #rollback(boolean)}等方法时会清空缓存 {@link #doFlushStatements(boolean)}
 *
 * @author Clinton Begin
 */
public class ReuseExecutor extends BaseExecutor {

  // key: sql 语句, value: Statement 对象
  private final Map<String, Statement> statementMap = new HashMap<>();

  public ReuseExecutor(Configuration configuration, Transaction transaction) {
    super(configuration, transaction);
  }

  @Override
  public int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
    Configuration configuration = ms.getConfiguration();
    StatementHandler handler = configuration.newStatementHandler(this, ms, parameter, RowBounds.DEFAULT, null, null);
    Statement stmt = prepareStatement(handler, ms.getStatementLog());
    return handler.update(stmt);
  }

  @Override
  public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
    // 获取配置对象
    Configuration configuration = ms.getConfiguration();
    // 创建 StatementHandler 对象
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler, boundSql);
    // 创建 Statement 并绑定SQL参数 (调用 StatementHandler.prepare() 与 StatementHandler.parameterize() 方法)
    Statement stmt = prepareStatement(handler, ms.getStatementLog());
    // 执行 SQL (调用 StatementHandler.update() 方法, 并通过 ResultSetHandler 完成结果集的映射)
    return handler.query(stmt, resultHandler);
  }

  @Override
  protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql) throws SQLException {
    Configuration configuration = ms.getConfiguration();
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
    Statement stmt = prepareStatement(handler, ms.getStatementLog());
    return handler.queryCursor(stmt);
  }

  @Override
  public List<BatchResult> doFlushStatements(boolean isRollback) {
    // 关闭所有缓存 Statement
    for (Statement stmt : statementMap.values()) {
      closeStatement(stmt);
    }
    // 清空缓存
    statementMap.clear();
    return Collections.emptyList();
  }

  /**
   * 创建 Statement 对象并绑定SQL参数
   *
   * 首先会从缓存中获取 Statement ,如果缓存中不存在则创建并放入缓存中
   *
   * @param handler
   * @param statementLog
   * @return
   * @throws SQLException
   */
  private Statement prepareStatement(StatementHandler handler, Log statementLog) throws SQLException {
    Statement stmt;
    BoundSql boundSql = handler.getBoundSql();
    String sql = boundSql.getSql();

    // 检测是否缓存了相同 SQL语句所对应的 Statement 对象
    if (hasStatementFor(sql)) {
      // 从缓存中获取 Statement 对象
      stmt = getStatement(sql);
      // 设置 Statement 事务超时时间
      applyTransactionTimeout(stmt);
    } else {
      // 获取连接
      Connection connection = getConnection(statementLog);
      // 创建 Statement 对象
      stmt = handler.prepare(connection, transaction.getTimeout());
      // 将 Statement 对象放入缓存
      putStatement(sql, stmt);
    }
    // 绑定SQL参数
    handler.parameterize(stmt);
    return stmt;
  }

  /**
   * 是否存在 Statement 对象 (从缓存中获取)
   *
   * @param sql sql 语句
   * @return
   */
  private boolean hasStatementFor(String sql) {
    try {
      Statement statement = statementMap.get(sql);
      // Statement 对象不等于 null ,并且连接没有关闭
      return statement != null && !statement.getConnection().isClosed();
    } catch (SQLException e) {
      return false;
    }
  }

  /**
   * 从缓存中获取 Statement
   *
   * @param s sql 语句
   * @return
   */
  private Statement getStatement(String s) {
    return statementMap.get(s);
  }

  /**
   * 想缓存中添加 Statement
   *
   * @param sql   sql 语句
   * @param stmt  Statement
   */
  private void putStatement(String sql, Statement stmt) {
    statementMap.put(sql, stmt);
  }

}
