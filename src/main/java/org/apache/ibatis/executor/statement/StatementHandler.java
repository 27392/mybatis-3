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
package org.apache.ibatis.executor.statement;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.ResultHandler;

/**
 * Statement 处理器 (负责创建 Statement, 为 Statement 绑定参数, 执行增删改查等操作)
 *
 * @see RoutingStatementHandler
 * @see BaseStatementHandler
 *    @see SimpleStatementHandler    负责处理 Statement 类型
 *    @see PreparedStatementHandler  负责处理 PreparedStatement 类型
 *    @see CallableStatementHandler  负责处理 CallableStatement 类型
 *
 * @author Clinton Begin
 */
public interface StatementHandler {

  /**
   * 从连接中获取一个 Statement
   *
   * @param connection
   * @param transactionTimeout
   * @return
   * @throws SQLException
   */
  Statement prepare(Connection connection, Integer transactionTimeout)
      throws SQLException;

  /**
   * 绑定 Statement 执行所需要的参数
   *
   * @param statement
   * @throws SQLException
   */
  void parameterize(Statement statement)
      throws SQLException;

  /**
   * 批量执行 SQL 语句
   *
   * @param statement
   * @throws SQLException
   */
  void batch(Statement statement)
      throws SQLException;

  /**
   * 执行 update/insert/delete 语句
   * @param statement
   * @return
   * @throws SQLException
   */
  int update(Statement statement)
      throws SQLException;

  /**
   * 执行 select 语句
   *
   * @param statement
   * @param resultHandler
   * @param <E>
   * @return
   * @throws SQLException
   */
  <E> List<E> query(Statement statement, ResultHandler resultHandler)
      throws SQLException;

  /**
   * 执行游标查询
   *
   * @param statement
   * @param <E>
   * @return
   * @throws SQLException
   */
  <E> Cursor<E> queryCursor(Statement statement)
      throws SQLException;

  /**
   * 获取 BoundSql 对象
   *
   * @return
   */
  BoundSql getBoundSql();

  /**
   * 获取参数处理器
   *
   * @return
   */
  ParameterHandler getParameterHandler();

}
