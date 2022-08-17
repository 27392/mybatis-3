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

import java.lang.reflect.Method;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.reflection.ArrayUtil;

/**
 * Base class for proxies to do logging.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public abstract class BaseJdbcLogger {

  // 保存了 PreparedStatement 类的所有方法名以 set 开头并且参数数量大于 1 的方法名
  protected static final Set<String> SET_METHODS;

  // 保存了 Statement 接口执行SQL语句相关的方法
  protected static final Set<String> EXECUTE_METHODS = new HashSet<>();

  // 保存了 PreparedStatement 类 set 开头方法的两个参数的值
  private final Map<Object, Object> columnMap = new HashMap<>();

  // 保存了 PreparedStatement 类 set 开头方法的第一个参数的值
  private final List<Object> columnNames = new ArrayList<>();

  // 保存了 PreparedStatement 类 set 开头方法的第两个参数的值
  private final List<Object> columnValues = new ArrayList<>();

  // 当前所执行的 Mapper log 对象
  protected final Log statementLog;

  // 记录的SQL的层数,用来格式化输出SQL
  protected final int queryStack;

  /*
   * Default constructor
   */
  public BaseJdbcLogger(Log log, int queryStack) {
    this.statementLog = log;
    if (queryStack == 0) {
      this.queryStack = 1;
    } else {
      this.queryStack = queryStack;
    }
  }

  static {
    // 保存 PreparedStatement 类所有以 set 开头的方法并且方法参数大于 1 的方法名
    SET_METHODS = Arrays.stream(PreparedStatement.class.getDeclaredMethods())
            .filter(method -> method.getName().startsWith("set"))
            .filter(method -> method.getParameterCount() > 1)
            .map(Method::getName)
            .collect(Collectors.toSet());

    // 保存 Statement 接口所有与SQL执行相关的方法
    EXECUTE_METHODS.add("execute");
    EXECUTE_METHODS.add("executeUpdate");
    EXECUTE_METHODS.add("executeQuery");
    EXECUTE_METHODS.add("addBatch");
  }

  /**
   * 设置字段值
   *
   * @param key
   * @param value
   */
  protected void setColumn(Object key, Object value) {
    columnMap.put(key, value);
    columnNames.add(key);
    columnValues.add(value);
  }

  /**
   * 获取字段值
   *
   * @param key
   * @return
   */
  protected Object getColumn(Object key) {
    return columnMap.get(key);
  }

  /**
   * 获取参数值并格式化
   *
   * @return
   */
  protected String getParameterValueString() {
    List<Object> typeList = new ArrayList<>(columnValues.size());
    for (Object value : columnValues) {
      if (value == null) {
        typeList.add("null");
      } else {
        typeList.add(objectValueString(value) + "(" + value.getClass().getSimpleName() + ")");
      }
    }
    final String parameters = typeList.toString();
    return parameters.substring(1, parameters.length() - 1);
  }

  /**
   * 对象格式化
   *
   * @param value
   * @return
   */
  protected String objectValueString(Object value) {
    if (value instanceof Array) {
      try {
        return ArrayUtil.toString(((Array) value).getArray());
      } catch (SQLException e) {
        return value.toString();
      }
    }
    return value.toString();
  }

  /**
   * 获取格式化的 columnNames
   *
   * @return
   */
  protected String getColumnString() {
    return columnNames.toString();
  }

  /**
   * 清除字段数据
   */
  protected void clearColumnInfo() {
    columnMap.clear();
    columnNames.clear();
    columnValues.clear();
  }

  /**
   * 去除多余空格
   *
   * @param original
   * @return
   */
  protected String removeExtraWhitespace(String original) {
    return SqlSourceBuilder.removeExtraWhitespaces(original);
  }

  /**
   * isDebugEnabled 的简单封装
   *
   * @return
   */
  protected boolean isDebugEnabled() {
    return statementLog.isDebugEnabled();
  }

  /**
   * isTraceEnabled 的简单封装
   *
   * @return
   */
  protected boolean isTraceEnabled() {
    return statementLog.isTraceEnabled();
  }

  /**
   * 输出 debug 级别日志, 同时格式化日志内容
   *
   * @param text  文本
   * @param input 是否是输入
   */
  protected void debug(String text, boolean input) {
    if (statementLog.isDebugEnabled()) {
      statementLog.debug(prefix(input) + text);
    }
  }

  /**
   * 输出 trace 级别日志, 同时格式化日志内容
   *
   * @param text  文本
   * @param input 是否是输入
   */
  protected void trace(String text, boolean input) {
    if (statementLog.isTraceEnabled()) {
      statementLog.trace(prefix(input) + text);
    }
  }

  /**
   * 日志前缀 '==> '或 '<== '
   *
   * @param isInput 是否是输入
   * @return
   */
  private String prefix(boolean isInput) {
    // 根据SQL层级初始化
    char[] buffer = new char[queryStack * 2 + 2];
    // 使用 '=' 填充 buffer 数组
    Arrays.fill(buffer, '=');
    // 将倒数第一个赋值为空值 ' '
    buffer[queryStack * 2 + 1] = ' ';
    // 如果是输入将倒数第二个赋值为 '>', 否则将第一个赋值为 '<'
    if (isInput) {
      buffer[queryStack * 2] = '>';
    } else {
      buffer[0] = '<';
    }
    // 将数组转换成S tring 并返回
    return new String(buffer);
  }

}
