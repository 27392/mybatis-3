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
package org.apache.ibatis.logging.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.reflection.ExceptionUtil;

/**
 * PreparedStatement proxy to add logging.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 *
 */
public final class PreparedStatementLogger extends BaseJdbcLogger implements InvocationHandler {

  private final PreparedStatement statement;

  private PreparedStatementLogger(PreparedStatement stmt, Log statementLog, int queryStack) {
    super(statementLog, queryStack);
    this.statement = stmt;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] params) throws Throwable {
    try {
      // 如果来自 Object 类的方法(toString等)直接调用
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, params);
      }
      // 调用执行SQL语句相关的方法
      if (EXECUTE_METHODS.contains(method.getName())) {
        if (isDebugEnabled()) {
          // 输出日志 (包含参数信息)
          debug("Parameters: " + getParameterValueString(), true);
        }
        // 清除字段信息
        clearColumnInfo();
        // 如果是 executeQuery(查询方法)
        if ("executeQuery".equals(method.getName())) {
          // 调用目标类 PreparedStatement 对应的方法, 获取到 ResultSet 对象
          ResultSet rs = (ResultSet) method.invoke(statement, params);
          // 如果 ResultSet 不为空则为其创建代理对象并返回
          return rs == null ? null : ResultSetLogger.newInstance(rs, statementLog, queryStack);
        } else {
          // 其余执行方法直接则直接调用
          return method.invoke(statement, params);
        }
      } else if (SET_METHODS.contains(method.getName())) {
        // 如果调用的设置参数方法,则调用 setColumn 填充属性
        if ("setNull".equals(method.getName())) {
          setColumn(params[0], null);
        } else {
          setColumn(params[0], params[1]);
        }
        // 调用目标类对应的方法
        return method.invoke(statement, params);
      } else if ("getResultSet".equals(method.getName())) {
        // 如果是 getResultSet 方法
        ResultSet rs = (ResultSet) method.invoke(statement, params);
        // 如果 ResultSet 不为空则为其创建代理对象并返回
        return rs == null ? null : ResultSetLogger.newInstance(rs, statementLog, queryStack);
      } else if ("getUpdateCount".equals(method.getName())) {
        // 如果调用的是 getUpdateCount (获取影响行数) 则直接调用
        int updateCount = (Integer) method.invoke(statement, params);
        // 数量不为 -1 的情况下
        if (updateCount != -1) {
          // 输出日志 (包含影响行数)
          debug("   Updates: " + updateCount, false);
        }
        // 返回影响行数
        return updateCount;
      } else {
        // 其余方法直接调用目标类,不做处理
        return method.invoke(statement, params);
      }
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
  }

  /**
   * 创建代理对象
   *
   * Creates a logging version of a PreparedStatement.
   *
   * @param stmt - the statement
   * @param statementLog - the statement log
   * @param queryStack - the query stack
   * @return - the proxy
   */
  public static PreparedStatement newInstance(PreparedStatement stmt, Log statementLog, int queryStack) {
    InvocationHandler handler = new PreparedStatementLogger(stmt, statementLog, queryStack);
    ClassLoader cl = PreparedStatement.class.getClassLoader();
    return (PreparedStatement) Proxy.newProxyInstance(cl, new Class[]{PreparedStatement.class, CallableStatement.class}, handler);
  }

  /**
   * 获取代理对象
   *
   * Return the wrapped prepared statement.
   *
   * @return the PreparedStatement
   */
  public PreparedStatement getPreparedStatement() {
    return statement;
  }

}
