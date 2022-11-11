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

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * 抽象的 Statement 处理器 (负责处理设置 Statement 参数,生成主键,关闭 Statement等工作)
 *
 * 提取出公共的属性(其中最重要的是{@link ResultSetHandler}与{@link ParameterHandler}).
 * 并定义了抽象方法 {@link #instantiateStatement(Connection)}
 *
 * @author Clinton Begin
 */
public abstract class BaseStatementHandler implements StatementHandler {

  // Configuration 对象
  protected final Configuration configuration;
  // ObjectFactory 对象
  protected final ObjectFactory objectFactory;
  // TypeHandlerRegistry 对象
  protected final TypeHandlerRegistry typeHandlerRegistry;
  // ResultSetHandler 对象，负责将结果集映射成对象
  protected final ResultSetHandler resultSetHandler;
  // ParameterHandler 对象，负责为 SQL 语句设置参数
  protected final ParameterHandler parameterHandler;

  // Executor 对象，负责执行 sql
  protected final Executor executor;
  // MappedStatement 对象
  protected final MappedStatement mappedStatement;
  // RowBounds 对象，伪分页
  protected final RowBounds rowBounds;

  // BoundSql 对象，保存有 sql 语句信息
  protected BoundSql boundSql;

  protected BaseStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
    this.configuration = mappedStatement.getConfiguration();
    this.executor = executor;
    this.mappedStatement = mappedStatement;
    this.rowBounds = rowBounds;

    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.objectFactory = configuration.getObjectFactory();

    // 由于 select 语句需要 BoundSql 来生成缓存 key, 创建 StatementHandler 时会传入 BoundSql 对象, 无需再次获取
    // 而执行 update/insert/delete 语句，boundSql 等于 null
    if (boundSql == null) { // issue #435, get the key before calculating the statement
      // 生成主键（调用 KeyGenerator.processBefore 方法，执行插入前操作）
      generateKeys(parameterObject);
      // 获取 BoundSql 对象
      boundSql = mappedStatement.getBoundSql(parameterObject);
    }

    this.boundSql = boundSql;

    // 创建 ParameterHandler 与 ResultSetHandler 对象
    this.parameterHandler = configuration.newParameterHandler(mappedStatement, parameterObject, boundSql);
    this.resultSetHandler = configuration.newResultSetHandler(executor, mappedStatement, rowBounds, parameterHandler, resultHandler, boundSql);
  }

  @Override
  public BoundSql getBoundSql() {
    return boundSql;
  }

  @Override
  public ParameterHandler getParameterHandler() {
    return parameterHandler;
  }

  @Override
  public Statement prepare(Connection connection, Integer transactionTimeout) throws SQLException {
    ErrorContext.instance().sql(boundSql.getSql());
    Statement statement = null;
    try {
      // 初始化 Statement
      statement = instantiateStatement(connection);
      // 设置 Statement 超时时间
      setStatementTimeout(statement, transactionTimeout);
      // 设置 Statement fetchSize
      setFetchSize(statement);
      return statement;
    } catch (SQLException e) {
      closeStatement(statement);
      throw e;
    } catch (Exception e) {
      closeStatement(statement);
      throw new ExecutorException("Error preparing statement.  Cause: " + e, e);
    }
  }

  /**
   * 初始化（创建） Statement
   *
   * @param connection
   * @return
   * @throws SQLException
   */
  protected abstract Statement instantiateStatement(Connection connection) throws SQLException;

  /**
   * 设置 Statement 超时时间
   *
   * 优先使用 MappedStatement 中配置的超时时间，其次使用 Configuration 中配置的超时时间
   *
   * @param stmt
   * @param transactionTimeout
   * @throws SQLException
   */
  protected void setStatementTimeout(Statement stmt, Integer transactionTimeout) throws SQLException {
    Integer queryTimeout = null;
    // 如果 MappedStatement.timeout 没有配置则使用 Configuration.defaultStatementTimeout
    if (mappedStatement.getTimeout() != null) {
      queryTimeout = mappedStatement.getTimeout();
    } else if (configuration.getDefaultStatementTimeout() != null) {
      queryTimeout = configuration.getDefaultStatementTimeout();
    }
    // 不为空则设置超时时间
    if (queryTimeout != null) {
      stmt.setQueryTimeout(queryTimeout);
    }
    StatementUtil.applyTransactionTimeout(stmt, queryTimeout, transactionTimeout);
  }

  /**
   * 设置 Statement fetchSize
   *
   * 优先使用 MappedStatement 中的配置，其次使用 Configuration 中的配置
   *
   * @param stmt
   * @throws SQLException
   */
  protected void setFetchSize(Statement stmt) throws SQLException {
    // 使用 MappedStatement.fetchSize
    Integer fetchSize = mappedStatement.getFetchSize();
    if (fetchSize != null) {
      stmt.setFetchSize(fetchSize);
      return;
    }
    // 使用 Configuration.defaultFetchSize
    Integer defaultFetchSize = configuration.getDefaultFetchSize();
    if (defaultFetchSize != null) {
      stmt.setFetchSize(defaultFetchSize);
    }
  }

  /**
   * 关闭 Statement
   *
   * @param statement
   */
  protected void closeStatement(Statement statement) {
    try {
      if (statement != null) {
        statement.close();
      }
    } catch (SQLException e) {
      //ignore
    }
  }

  /**
   * 生成主键
   *
   * @param parameter
   */
  protected void generateKeys(Object parameter) {
    // 获取当前 MappedStatement 中的 KeyGenerator 对象
    KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
    ErrorContext.instance().store();
    // 调用 KeyGenerator.processBefore 方法生成主键 （该方法在插入前执行）
    keyGenerator.processBefore(executor, mappedStatement, null, parameter);
    ErrorContext.instance().recall();
  }

}
