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
import java.sql.ResultSet;
import java.sql.Statement;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.reflection.ExceptionUtil;

/**
 * Statement proxy to add logging.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 *
 */
public final class StatementLogger extends BaseJdbcLogger implements InvocationHandler {

  private final Statement statement;

  private StatementLogger(Statement stmt, Log statementLog, int queryStack) {
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
          // 输出日志 (包含 SQL 语句信息)
          debug(" Executing: " + removeExtraWhitespace((String) params[0]), true);
        }
        // 如果是 executeQuery (查询方法)
        if ("executeQuery".equals(method.getName())) {
          // 调用目标类 Statement 对应的方法, 获取到 ResultSet 对象
          ResultSet rs = (ResultSet) method.invoke(statement, params);
          // 如果 ResultSet 不为空则为其创建代理对象并返回
          return rs == null ? null : ResultSetLogger.newInstance(rs, statementLog, queryStack);
        } else {
          // 其余执行方法直接则直接调用
          return method.invoke(statement, params);
        }
      } else if ("getResultSet".equals(method.getName())) {
        // 如果是 getResultSet 方法
        ResultSet rs = (ResultSet) method.invoke(statement, params);
        // 如果 ResultSet 不为空则为其创建代理对象并返回
        return rs == null ? null : ResultSetLogger.newInstance(rs, statementLog, queryStack);
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
   * Creates a logging version of a Statement.
   *
   * @param stmt
   *          the statement
   * @param statementLog
   *          the statement log
   * @param queryStack
   *          the query stack
   * @return the proxy
   */
  public static Statement newInstance(Statement stmt, Log statementLog, int queryStack) {
    InvocationHandler handler = new StatementLogger(stmt, statementLog, queryStack);
    ClassLoader cl = Statement.class.getClassLoader();
    return (Statement) Proxy.newProxyInstance(cl, new Class[]{Statement.class}, handler);
  }

  /**
   * 获取代理对象
   *
   * return the wrapped statement.
   *
   * @return the statement
   */
  public Statement getStatement() {
    return statement;
  }

}
