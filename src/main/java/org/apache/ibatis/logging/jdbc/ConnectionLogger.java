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

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.reflection.ExceptionUtil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * Connection proxy to add logging.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 *
 */
public final class ConnectionLogger extends BaseJdbcLogger implements InvocationHandler {

  private final Connection connection;

  private ConnectionLogger(Connection conn, Log statementLog, int queryStack) {
    super(statementLog, queryStack);
    this.connection = conn;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] params)
      throws Throwable {
    try {
      // 如果来自 Object 类的方法(toString等)直接调用
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, params);
      }
      // 调用 prepareStatement、prepareCall
      if ("prepareStatement".equals(method.getName()) || "prepareCall".equals(method.getName())) {
        if (isDebugEnabled()) {
          // 输出日志 (包含预处理 SQL 语句信息)
          debug(" Preparing: " + removeExtraWhitespace((String) params[0]), true);
        }
        // 调用目标类 Connection 对应的方法, 获取 PreparedStatement
        PreparedStatement stmt = (PreparedStatement) method.invoke(connection, params);
        // 为该 PreparedStatement 创建代理对象
        stmt = PreparedStatementLogger.newInstance(stmt, statementLog, queryStack);
        // 返回代理对象
        return stmt;
      } else if ("createStatement".equals(method.getName())) {
        // 如果是 createStatement 方法
        Statement stmt = (Statement) method.invoke(connection, params);
        // 通样为其创建代理对象
        stmt = StatementLogger.newInstance(stmt, statementLog, queryStack);
        // 返回代理对象
        return stmt;
      } else {
        // 其余方法直接调用目标类,不做处理
        return method.invoke(connection, params);
      }
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
  }

  /**
   * 创建 Connection 代理对象
   *
   * Creates a logging version of a connection.
   *
   * @param conn
   *          the original connection
   * @param statementLog
   *          the statement log
   * @param queryStack
   *          the query stack
   * @return the connection with logging
   */
  public static Connection newInstance(Connection conn, Log statementLog, int queryStack) {
    InvocationHandler handler = new ConnectionLogger(conn, statementLog, queryStack);
    ClassLoader cl = Connection.class.getClassLoader();
    return (Connection) Proxy.newProxyInstance(cl, new Class[]{Connection.class}, handler);
  }

  /**
   * 返回代理对象
   *
   * return the wrapped connection.
   *
   * @return the connection
   */
  public Connection getConnection() {
    return connection;
  }

}
