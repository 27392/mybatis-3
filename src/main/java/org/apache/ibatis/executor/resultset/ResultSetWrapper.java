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
package org.apache.ibatis.executor.resultset;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.apache.ibatis.type.UnknownTypeHandler;

/**
 * ResultSetWrapper
 *
 * 记录了 ResultSet 中的一些元数据(包括每列的列名、java类型、jdbc类型)
 * 并提供了一系列操作 ResultSet 的辅助方法
 *
 * @author Iwao AVE!
 */
public class ResultSetWrapper {

  // ResultSet 对象
  private final ResultSet resultSet;
  // TypeHandlerRegistry
  private final TypeHandlerRegistry typeHandlerRegistry;

  // 记录了 ResultSet 中每列的列名
  private final List<String> columnNames = new ArrayList<>();
  // 记录了 ResultSet 中每列对应的 Java 类型
  private final List<String> classNames = new ArrayList<>();
  // 记录了 ResultSet 中每列对应的 JdbcType 类型
  private final List<JdbcType> jdbcTypes = new ArrayList<>();

  // key 是列名, value
  private final Map<String, Map<Class<?>, TypeHandler<?>>> typeHandlerMap = new HashMap<>();
  // 记录了以映射的列名，其中 key 是 ResultMap 的 id + 列前缀, value 是列名集合
  private final Map<String, List<String>> mappedColumnNamesMap = new HashMap<>();
  // 记录了未映射的列名，其中 key 是 ResultMap 的 id + 列前缀, value 是列名集合
  private final Map<String, List<String>> unMappedColumnNamesMap = new HashMap<>();

  public ResultSetWrapper(ResultSet rs, Configuration configuration) throws SQLException {
    super();
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.resultSet = rs;

    // 获取 ResultSet 的元信息
    final ResultSetMetaData metaData = rs.getMetaData();
    // 获取 ResultSet 的列数
    final int columnCount = metaData.getColumnCount();
    for (int i = 1; i <= columnCount; i++) {
      // 获取列名或是通过`as`关键字指定的别名
      columnNames.add(configuration.isUseColumnLabel() ? metaData.getColumnLabel(i) : metaData.getColumnName(i));
      // 该列的 JdbcType 类型
      jdbcTypes.add(JdbcType.forCode(metaData.getColumnType(i)));
      // 该列的对应的 Java 类型
      classNames.add(metaData.getColumnClassName(i));
    }
  }

  /**
   * 获取 ResultSet
   *
   * @return
   */
  public ResultSet getResultSet() {
    return resultSet;
  }

  /**
   * 获取 ResultSet 中每列的名称
   *
   * @return
   */
  public List<String> getColumnNames() {
    return this.columnNames;
  }

  /**
   * 获取 ResultSet 中每列的 Java 类型
   *
   * @return
   */
  public List<String> getClassNames() {
    return Collections.unmodifiableList(classNames);
  }

  /**
   * 获取 ResultSet 中每列的 JdbcType 类型
   *
   * @return
   */
  public List<JdbcType> getJdbcTypes() {
    return jdbcTypes;
  }

  /**
   * 根据指定列名获取Jdbc类型
   *
   * @param columnName  列名
   * @return
   */
  public JdbcType getJdbcType(String columnName) {
    for (int i = 0; i < columnNames.size(); i++) {
      if (columnNames.get(i).equalsIgnoreCase(columnName)) {
        return jdbcTypes.get(i);
      }
    }
    return null;
  }

  /**
   * Todo
   * Gets the type handler to use when reading the result set.
   * Tries to get from the TypeHandlerRegistry by searching for the property type.
   * If not found it gets the column JDBC type and tries to get a handler for it.
   *
   * @param propertyType
   *          the property type
   * @param columnName
   *          the column name
   * @return the type handler
   */
  public TypeHandler<?> getTypeHandler(Class<?> propertyType, String columnName) {
    TypeHandler<?> handler = null;
    // 从 typeHandlerMap 中获取或初始化
    Map<Class<?>, TypeHandler<?>> columnHandlers = typeHandlerMap.get(columnName);
    if (columnHandlers == null) {
      // 为空的情况下初始化
      columnHandlers = new HashMap<>();
      typeHandlerMap.put(columnName, columnHandlers);
    } else {
      // 不为空的情况下获取 TypeHandler
      handler = columnHandlers.get(propertyType);
    }

    // TypeHandler 为空的情况
    if (handler == null) {
      // 获取列名对应的 JdbcType
      JdbcType jdbcType = getJdbcType(columnName);

      // 获取属性类型与 JdbcType 对应的 TypeHandler
      handler = typeHandlerRegistry.getTypeHandler(propertyType, jdbcType);
      // Replicate logic of UnknownTypeHandler#resolveTypeHandler
      // See issue #59 comment 10
      if (handler == null || handler instanceof UnknownTypeHandler) {
        final int index = columnNames.indexOf(columnName);
        // 根据列名解析出对应的 Class 对象
        final Class<?> javaType = resolveClass(classNames.get(index));
        if (javaType != null && jdbcType != null) {
          handler = typeHandlerRegistry.getTypeHandler(javaType, jdbcType);
        } else if (javaType != null) {
          handler = typeHandlerRegistry.getTypeHandler(javaType);
        } else if (jdbcType != null) {
          handler = typeHandlerRegistry.getTypeHandler(jdbcType);
        }
      }
      if (handler == null || handler instanceof UnknownTypeHandler) {
        handler = new ObjectTypeHandler();
      }
      columnHandlers.put(propertyType, handler);
    }
    return handler;
  }

  /**
   * 将 className 解析成 Class 对象
   *
   * @param className
   * @return
   */
  private Class<?> resolveClass(String className) {
    try {
      // #699 className could be null
      if (className != null) {
        return Resources.classForName(className);
      }
    } catch (ClassNotFoundException e) {
      // ignore
    }
    return null;
  }

  /**
   * 加载指定 ResultMap 映射与未映射列名
   *
   * @param resultMap
   * @param columnPrefix
   * @throws SQLException
   */
  private void loadMappedAndUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    // 记录以映射的列名
    List<String> mappedColumnNames = new ArrayList<>();
    // 记录未映射的列名
    List<String> unmappedColumnNames = new ArrayList<>();

    // 列名前缀(大写)
    final String upperColumnPrefix = columnPrefix == null ? null : columnPrefix.toUpperCase(Locale.ENGLISH);
    // ResultMap 中定义的列名加上前缀
    final Set<String> mappedColumns = prependPrefixes(resultMap.getMappedColumns(), upperColumnPrefix);

    // 遍历 ResultSet 中的列名
    for (String columnName : columnNames) {
      // 列名转为大写(ResultMap中定义的列名也是转换成大写后存储的)
      final String upperColumnName = columnName.toUpperCase(Locale.ENGLISH);
      // 与 ResultMap 中定义的列名进行匹配. 存在则表示以映射否则为未映射
      if (mappedColumns.contains(upperColumnName)) {
        mappedColumnNames.add(upperColumnName);
      } else {
        unmappedColumnNames.add(columnName);
      }
    }
    // 将 ResultMap 以映射的列名集合保存到 mappedColumnNamesMap 中 (key 由 ResultMap 的 id 与列前缀组成)
    mappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), mappedColumnNames);
    // 将 ResultMap 未映射的列名集合保存到 unMappedColumnNamesMap 中 (key 由 ResultMap 的 id 与列前缀组成)
    unMappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), unmappedColumnNames);
  }

  /**
   * 获取指定 ResultMap 对象中明确映射的列名集合
   *
   * @param resultMap
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  public List<String> getMappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    // 从 mappedColumnNamesMap 集合中查找被映射的列名 (key 由 ResultMap 的 id 与列前缀组成)
    List<String> mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));

    // 如果未查找到指定 ResultMap 映射的列名, 则会调用 loadMappedAndUnmappedColumnNames 方法解析映射关系
    if (mappedColumnNames == null) {
      // 加载 ResultMap 与当前字段的映射关系, 并将结果存入 mappedColumnNamesMap 与 unMappedColumnNamesMap 集合中
      loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
      // 从加载后的 mappedColumnNamesMap 中再次获取
      mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    }
    return mappedColumnNames;
  }

  /**
   * 获取指定 ResultMap 对象中未映射的列名集合
   *
   * @param resultMap
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  public List<String> getUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    // 从 unMappedColumnNamesMap 集合中查找未被映射的列名 (key 由 ResultMap 的 id 与列前缀组成)
    List<String> unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));

    // 如果未查找到指定 ResultMap 未映射的列名, 则会调用 loadMappedAndUnmappedColumnNames 方法解析映射关系
    if (unMappedColumnNames == null) {
      // 加载 ResultMap 与当前字段的映射关系, 并将结果存入 mappedColumnNamesMap 与 unMappedColumnNamesMap 集合中
      loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
      // 从加载后的 unMappedColumnNamesMap 中再次获取
      unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    }
    return unMappedColumnNames;
  }

  /**
   * 获取 ResultMap 在 {@link #mappedColumnNamesMap} 与 {@link #unMappedColumnNamesMap} 中的 key
   *
   * @param resultMap
   * @param columnPrefix
   * @return
   */
  private String getMapKey(ResultMap resultMap, String columnPrefix) {
    return resultMap.getId() + ":" + columnPrefix;
  }

  /**
   * 添加列前缀
   *
   * @param columnNames
   * @param prefix
   * @return
   */
  private Set<String> prependPrefixes(Set<String> columnNames, String prefix) {
    if (columnNames == null || columnNames.isEmpty() || prefix == null || prefix.length() == 0) {
      return columnNames;
    }
    final Set<String> prefixed = new HashSet<>();
    for (String columnName : columnNames) {
      prefixed.add(prefix + columnName);
    }
    return prefixed;
  }

}
