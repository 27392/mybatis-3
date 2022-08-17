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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.reflection.ExceptionUtil;

/**
 * ResultSet proxy to add logging.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 *
 */
public final class ResultSetLogger extends BaseJdbcLogger implements InvocationHandler {

  // 超大长度类型
  private static final Set<Integer> BLOB_TYPES = new HashSet<>();

  // 是否是第一行
  private boolean first = true;

  // 统计数量
  private int rows;

  // 目标对象
  private final ResultSet rs;

  // 超大长度类型下标
  private final Set<Integer> blobColumns = new HashSet<>();

  // 初始化超大长度类型
  static {
    BLOB_TYPES.add(Types.BINARY);
    BLOB_TYPES.add(Types.BLOB);
    BLOB_TYPES.add(Types.CLOB);
    BLOB_TYPES.add(Types.LONGNVARCHAR);
    BLOB_TYPES.add(Types.LONGVARBINARY);
    BLOB_TYPES.add(Types.LONGVARCHAR);
    BLOB_TYPES.add(Types.NCLOB);
    BLOB_TYPES.add(Types.VARBINARY);
  }

  private ResultSetLogger(ResultSet rs, Log statementLog, int queryStack) {
    super(statementLog, queryStack);
    this.rs = rs;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] params) throws Throwable {
    try {
      // 如果来自 Object 类的方法(toString等)直接调用
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, params);
      }
      // 调用目标类 ResultSet 对应的方法, 获取对象
      Object o = method.invoke(rs, params);
      // 如果是 next 方法(是否存在下一行数据)
      if ("next".equals(method.getName())) {
        // 如果存在下一行数据
        if ((Boolean) o) {
          // 统计数量 +1
          rows++;
          // 如果开启了 trace 级别日志
          if (isTraceEnabled()) {
            // 获取原数据类型
            ResultSetMetaData rsmd = rs.getMetaData();
            // 获取列数量
            final int columnCount = rsmd.getColumnCount();
            // 如果是第一行数据则输出表头
            if (first) {
              first = false;
              printColumnHeaders(rsmd, columnCount);
            }
            // 打印行数据
            printColumnValues(columnCount);
          }
        } else {
          // 输出日志 (包含获取数量)
          debug("     Total: " + rows, false);
        }
      }
      // 清除字段信息
      clearColumnInfo();
      return o;
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
  }

  private void printColumnHeaders(ResultSetMetaData rsmd, int columnCount) throws SQLException {
    StringJoiner row = new StringJoiner(", ", "   Columns: ", "");
    for (int i = 1; i <= columnCount; i++) {
      // 如果是超大长度类型则将其下标记录在 blobColumns 属性中
      if (BLOB_TYPES.contains(rsmd.getColumnType(i))) {
        blobColumns.add(i);
      }
      // 获取类名称
      row.add(rsmd.getColumnLabel(i));
    }
    // 打印表名, trace 级别
    trace(row.toString(), false);
  }

  private void printColumnValues(int columnCount) {
    StringJoiner row = new StringJoiner(", ", "       Row: ", "");
    for (int i = 1; i <= columnCount; i++) {
      try {
        // 判断是否是超大长度的值, 如果是则输出占位符,否则输出真正的属性值
        if (blobColumns.contains(i)) {
          row.add("<<BLOB>>");
        } else {
          row.add(rs.getString(i));
        }
      } catch (SQLException e) {
        // generally can't call getString() on a BLOB column
        row.add("<<Cannot Display>>");
      }
    }
    // 输出内容, trace 级别
    trace(row.toString(), false);
  }

  /**
   * 创建代理对象
   *
   * Creates a logging version of a ResultSet.
   *
   * @param rs
   *          the ResultSet to proxy
   * @param statementLog
   *          the statement log
   * @param queryStack
   *          the query stack
   * @return the ResultSet with logging
   */
  public static ResultSet newInstance(ResultSet rs, Log statementLog, int queryStack) {
    InvocationHandler handler = new ResultSetLogger(rs, statementLog, queryStack);
    ClassLoader cl = ResultSet.class.getClassLoader();
    return (ResultSet) Proxy.newProxyInstance(cl, new Class[]{ResultSet.class}, handler);
  }

  /**
   * 获取代理对象
   *
   * Get the wrapped result set.
   *
   * @return the resultSet
   */
  public ResultSet getRs() {
    return rs;
  }

}
