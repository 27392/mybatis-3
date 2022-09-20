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

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.apache.ibatis.util.MapUtil;

/**
 *
 * @see ResultSetHandler 的默认实现
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 * @author Kazuki Shimizu
 */
public class DefaultResultSetHandler implements ResultSetHandler {

  private static final Object DEFERRED = new Object();

  // Executor 对象
  private final Executor executor;
  // Configuration 对象
  private final Configuration configuration;
  // MappedStatement 对象
  private final MappedStatement mappedStatement;
  // RowBounds 对象
  private final RowBounds rowBounds;
  // ParameterHandler 对象
  private final ParameterHandler parameterHandler;
  // ResultHandler 对象
  private final ResultHandler<?> resultHandler;
  // BoundSql 对象
  private final BoundSql boundSql;
  // TypeHandlerRegistry 对象
  private final TypeHandlerRegistry typeHandlerRegistry;
  // ObjectFactory 对象
  private final ObjectFactory objectFactory;
  // ReflectorFactory 对象
  private final ReflectorFactory reflectorFactory;

  // nested resultmaps
  private final Map<CacheKey, Object> nestedResultObjects = new HashMap<>();
  private final Map<String, Object> ancestorObjects = new HashMap<>();
  private Object previousRowValue;

  // multiple resultsets
  private final Map<String, ResultMapping> nextResultMaps = new HashMap<>();
  private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<>();

  // Cached Automappings
  // 自动映射缓存 (保存了 ResultSet 中存在但 ResultSet 未映射的列信息)
  private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<>();

  // temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
  // 标识结果对象是否是否由构造函数创建
  private boolean useConstructorMappings;

  private static class PendingRelation {
    public MetaObject metaObject;
    public ResultMapping propertyMapping;
  }

  /**
   * 自动映射类(保存了未映射列的信息)
   */
  private static class UnMappedColumnAutoMapping {
    // 列名
    private final String column;
    // 属性名
    private final String property;
    // 属性与当前列 JdbcTye 对应的 TypeHandler 对象
    private final TypeHandler<?> typeHandler;
    // 是否是基本类型
    private final boolean primitive;

    public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler, boolean primitive) {
      this.column = column;
      this.property = property;
      this.typeHandler = typeHandler;
      this.primitive = primitive;
    }
  }

  public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement, ParameterHandler parameterHandler, ResultHandler<?> resultHandler, BoundSql boundSql,
                                 RowBounds rowBounds) {
    this.executor = executor;
    this.configuration = mappedStatement.getConfiguration();
    this.mappedStatement = mappedStatement;
    this.rowBounds = rowBounds;
    this.parameterHandler = parameterHandler;
    this.boundSql = boundSql;
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.objectFactory = configuration.getObjectFactory();
    this.reflectorFactory = configuration.getReflectorFactory();
    this.resultHandler = resultHandler;
  }

  //
  // HANDLE OUTPUT PARAMETER
  //

  @Override
  public void handleOutputParameters(CallableStatement cs) throws SQLException {
    final Object parameterObject = parameterHandler.getParameterObject();
    final MetaObject metaParam = configuration.newMetaObject(parameterObject);
    final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    for (int i = 0; i < parameterMappings.size(); i++) {
      final ParameterMapping parameterMapping = parameterMappings.get(i);
      if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
        if (ResultSet.class.equals(parameterMapping.getJavaType())) {
          handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
        } else {
          final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
          metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
        }
      }
    }
  }

  private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam) throws SQLException {
    if (rs == null) {
      return;
    }
    try {
      final String resultMapId = parameterMapping.getResultMapId();
      final ResultMap resultMap = configuration.getResultMap(resultMapId);
      final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
      if (this.resultHandler == null) {
        final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
        metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
      } else {
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
      }
    } finally {
      // issue #228 (close resultsets)
      closeResultSet(rs);
    }
  }

  //
  // HANDLE RESULT SETS
  // 处理结果集
  //
  @Override
  public List<Object> handleResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling results").object(mappedStatement.getId());

    // 结果对象 (用于保存映射结果集得到的对象)
    final List<Object> multipleResults = new ArrayList<>();

    // result数量
    int resultSetCount = 0;
    // 获取第一个 ResultSet 对象(可能存在多个 ResultSet 的情况)
    ResultSetWrapper rsw = getFirstResultSet(stmt);

    // 获取 ResultMap 集合 (如果存在 ResultSet 的情况可以在配置 <resultMap id="result1,result2">)
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();
    // ResultMap 的数量
    int resultMapCount = resultMaps.size();
    // 校验 ResultMap 集合是否为空；为空会抛出异常
    validateResultMapsCount(rsw, resultMapCount);

    // 遍历 ResultMap 集合
    while (rsw != null && resultMapCount > resultSetCount) {
      // 获取结果集对应的 ResultMap 对象
      ResultMap resultMap = resultMaps.get(resultSetCount);
      // 处理结果集 (根据ResultMap中定义的映射规则对ResultSet进行映射，并将映射的结果对象添加到 multipleResults 集合中)
      handleResultSet(rsw, resultMap, multipleResults, null);
      // 获取下一个结果集
      rsw = getNextResultSet(stmt);
      // 清空 nestedResultObjects 集合
      cleanUpAfterHandlingResultSet();
      // resultSetCount 递增
      resultSetCount++;
    }

    String[] resultSets = mappedStatement.getResultSets();
    if (resultSets != null) {
      while (rsw != null && resultSetCount < resultSets.length) {
        ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
        if (parentMapping != null) {
          String nestedResultMapId = parentMapping.getNestedResultMapId();
          ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
          handleResultSet(rsw, resultMap, null, parentMapping);
        }
        rsw = getNextResultSet(stmt);
        cleanUpAfterHandlingResultSet();
        resultSetCount++;
      }
    }

    return collapseSingleResultList(multipleResults);
  }

  @Override
  public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());

    ResultSetWrapper rsw = getFirstResultSet(stmt);

    List<ResultMap> resultMaps = mappedStatement.getResultMaps();

    int resultMapCount = resultMaps.size();
    validateResultMapsCount(rsw, resultMapCount);
    if (resultMapCount != 1) {
      throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
    }

    ResultMap resultMap = resultMaps.get(0);
    return new DefaultCursor<>(this, resultMap, rsw, rowBounds);
  }

  /**
   * 获取第一个 ResultSet 对象
   *
   * @param stmt
   * @return
   * @throws SQLException
   */
  private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
    // 获取 ResultSet 对象
    ResultSet rs = stmt.getResultSet();
    while (rs == null) {
      // move forward to get the first resultset in case the driver
      // doesn't return the resultset as the first result (HSQLDB 2.1)
      // 判断是否还有待处理的 ResultSet
      if (stmt.getMoreResults()) {
        rs = stmt.getResultSet();
      } else {
        // 没有处理的 ResultSet
        if (stmt.getUpdateCount() == -1) {
          // no more results. Must be no resultset
          break;
        }
      }
    }
    // 将 ResultSet 封装成 ResultSetWrapper 对象
    return rs != null ? new ResultSetWrapper(rs, configuration) : null;
  }

  /**
   * 获取下一个结果集
   *
   * @param stmt
   * @return
   */
  private ResultSetWrapper getNextResultSet(Statement stmt) {
    // Making this method tolerant of bad JDBC drivers
    try {
      // 检测JDBC是否支持多结果集
      if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
        // Crazy Standard JDBC way of determining if there are more results
        // 检测是否还有待处理的结果集，如果存在则封装成 ResultSetWrapper 返回
        if (!(!stmt.getMoreResults() && stmt.getUpdateCount() == -1)) {
          ResultSet rs = stmt.getResultSet();
          if (rs == null) {
            return getNextResultSet(stmt);
          } else {
            return new ResultSetWrapper(rs, configuration);
          }
        }
      }
    } catch (Exception e) {
      // Intentionally ignored.
    }
    return null;
  }

  /**
   * 关闭 ResultSet 对象
   *
   * @param rs
   */
  private void closeResultSet(ResultSet rs) {
    try {
      if (rs != null) {
        rs.close();
      }
    } catch (SQLException e) {
      // ignore
    }
  }

  /**
   * 清空 nestedResultObjects 集合
   */
  private void cleanUpAfterHandlingResultSet() {
    nestedResultObjects.clear();
  }

  /**
   * 校验 ResultMap 集合是否存在
   *
   * @param rsw
   * @param resultMapCount
   */
  private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
    if (rsw != null && resultMapCount < 1) {
      throw new ExecutorException("A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
          + "'.  It's likely that neither a Result Type nor a Result Map was specified.");
    }
  }

  /**
   * 处理单个结果集
   *
   * @param rsw
   * @param resultMap
   * @param multipleResults
   * @param parentMapping
   * @throws SQLException
   */
  private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping) throws SQLException {
    try {
      if (parentMapping != null) {
        handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
      } else {
        if (resultHandler == null) {
          // 如果用户未指定 ResultHandler 对象, 则使用 DefaultResultHandler 对象
          DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
          // 对 ResultSet 进行映射,并将映射得到的结果对象添加到 DefaultResultHandler 中暂存(DefaultResultHandler 中持有一个 List)
          handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
          // 将 DefaultResultHandler 中保存的结果添加到 multipleResults 集合中
          multipleResults.add(defaultResultHandler.getResultList());
        } else {
          // 使用用户自指定的 resultHandler 对象处理结果集
          handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
        }
      }
    } finally {
      // 关闭 ResultSet 对象
      // issue #228 (close resultsets)
      closeResultSet(rsw.getResultSet());
    }
  }

  @SuppressWarnings("unchecked")
  private List<Object> collapseSingleResultList(List<Object> multipleResults) {
    return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults;
  }

  //
  // HANDLE ROWS FOR SIMPLE RESULTMAP
  //

  /**
   * 处理简单结果集
   *
   * @param rsw
   * @param resultMap
   * @param resultHandler
   * @param rowBounds
   * @param parentMapping
   * @throws SQLException
   */
  public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    // 是否存在嵌套 ResultMap (association/collection中有存在 resultMap 属性)
    if (resultMap.hasNestedResultMaps()) {
      // 检查是否允许在嵌套语句中使用 RowBounds
      ensureNoRowBounds();
      // 是否允许在嵌套语句中使用 ResultHandler
      checkResultHandler();
      // 处理嵌套查询结果集
      handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    } else {
      // 处理普通结果集(不包含嵌套映射的简单映射)
      handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    }
  }

  /**
   * 检查是否允许使用了 RowBound 对象
   */
  private void ensureNoRowBounds() {
    if (configuration.isSafeRowBoundsEnabled() && rowBounds != null && (rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
      throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
          + "Use safeRowBoundsEnabled=false setting to bypass this check.");
    }
  }

  /**
   * 检查是否允许使用 ResultHandler 对象
   */
  protected void checkResultHandler() {
    if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
      throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
          + "Use safeResultHandlerEnabled=false setting to bypass this check "
          + "or ensure your statement returns ordered data and set resultOrdered=true on it.");
    }
  }

  /**
   * 处理简单的结果集映射
   *
   * @param rsw
   * @param resultMap
   * @param resultHandler
   * @param rowBounds
   * @param parentMapping
   * @throws SQLException
   */
  private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
      throws SQLException {

    // 结果上下文
    DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    // 获取结果集
    ResultSet resultSet = rsw.getResultSet();
    // 1. 定位到指定行数 (有 RowBounds 的时候才会起作用)
    skipRows(resultSet, rowBounds);

    // 2. 检测已处理的行数是否超过 BowBounds.limit 以及 ResultSet 中是否还有可处理的参数
    while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      // 3. 根据该行记录以及 ResultMap.Discriminator, 决定映射使用的 ResultMap
      ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
      // 4. 根据最终确定的 ResultMap 对 ResultSet 中该行记录进行映射, 得到映射后的结果对象
      Object rowValue = getRowValue(rsw, discriminatedResultMap, null);
      // 5. 将映射创建的结果对象添加到 ResultHandler 中保存
      storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
    }
  }

  /**
   * 保存映射创建的结果
   *
   * @param resultHandler
   * @param resultContext
   * @param rowValue
   * @param parentMapping
   * @param rs
   * @throws SQLException
   */
  private void storeObject(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue, ResultMapping parentMapping, ResultSet rs) throws SQLException {
    if (parentMapping != null) {
      linkToParents(rs, parentMapping, rowValue);
    } else {
      // 普通映射, 将结果对象保存到 ResultHandler 中
      callResultHandler(resultHandler, resultContext, rowValue);
    }
  }

  /**
   * 保存结果对象
   *
   *  1. 递增 {@link DefaultResultContext#resultCount} 字段, 该值用于检查处理记录的行数是否达到上限
   *     检查逻辑在 {@link #shouldProcessMoreRows} 方法中, 上限为 RowBounds.limit
   *     之后将结果对象暂存到 {@link DefaultResultContext#resultObject} 字段中
   *  2. 将结果对象添加到 {@link DefaultResultHandler#list} 字段中
   *
   * @see #shouldProcessMoreRows
   * @param resultHandler
   * @param resultContext
   * @param rowValue
   */
  @SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object>*/)
  private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue) {
    // 结果对象暂存到 DefaultResultContext 中, 并记录暂存数量
    resultContext.nextResultObject(rowValue);
    // 将结果对象保存到 ResultHandler 中
    ((ResultHandler<Object>) resultHandler).handleResult(resultContext);
  }

  /**
   * 是否可以继续处理
   *
   * @param context
   * @param rowBounds
   * @return
   */
  private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) {
    return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
  }

  /**
   * 根据 RowBounds 对象定位到指定行
   *
   * @param rs
   * @param rowBounds
   * @throws SQLException
   */
  private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
    if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
      if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
        rs.absolute(rowBounds.getOffset());
      }
    } else {
      for (int i = 0; i < rowBounds.getOffset(); i++) {
        if (!rs.next()) {
          break;
        }
      }
    }
  }

  //
  // GET VALUE FROM ROW FOR SIMPLE RESULT MAP
  //

  /**
   * 将行记录映射成对象
   *
   * @param rsw
   * @param resultMap
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {

    // 嵌套查询对象
    final ResultLoaderMap lazyLoader = new ResultLoaderMap();
    // 1. 创建行记录映射会后得到的结果对象(结果对象的类型由<resultMap>节点的 type 属性指定)
    Object rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
    if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {

      // 创建结果对象对应的 MetaObject 对象
      final MetaObject metaObject = configuration.newMetaObject(rowValue);

      // 是否使用构造函数创建
      boolean foundValues = this.useConstructorMappings;

      // 检测是否开启自动映射
      if (shouldApplyAutomaticMappings(resultMap, false)) {
        // 2. 自动映射 ResultMap 中未明确指定列 (如果没有获取到属性值则返回 false)
        foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
      }
      // 3. 映射 ResultMap 中明确指定需要映射的列 (如果没有获取到属性值则返回 false)
      foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
      foundValues = lazyLoader.size() > 0 || foundValues;
      // 4. 如果没有映射任何的属性, 则根据 returnInstanceForEmptyRow 配置决定返回空对象还是 null
      rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
    }
    return rowValue;
  }

  //
  // GET VALUE FROM ROW FOR NESTED RESULT MAP
  //

  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix, Object partialObject) throws SQLException {
    final String resultMapId = resultMap.getId();
    Object rowValue = partialObject;
    if (rowValue != null) {
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      putAncestor(rowValue, resultMapId);
      applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
      ancestorObjects.remove(resultMapId);
    } else {
      final ResultLoaderMap lazyLoader = new ResultLoaderMap();
      rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
      if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
        final MetaObject metaObject = configuration.newMetaObject(rowValue);
        boolean foundValues = this.useConstructorMappings;
        if (shouldApplyAutomaticMappings(resultMap, true)) {
          foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
        }
        foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
        putAncestor(rowValue, resultMapId);
        foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
        ancestorObjects.remove(resultMapId);
        foundValues = lazyLoader.size() > 0 || foundValues;
        rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
      }
      if (combinedKey != CacheKey.NULL_CACHE_KEY) {
        nestedResultObjects.put(combinedKey, rowValue);
      }
    }
    return rowValue;
  }

  private void putAncestor(Object resultObject, String resultMapId) {
    ancestorObjects.put(resultMapId, resultObject);
  }

  /**
   * 是否开启自动映射
   *
   * @param resultMap
   * @param isNested
   * @return
   */
  private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
    // 获取 ResultMap 中的 autoMapping 属性值
    if (resultMap.getAutoMapping() != null) {
      return resultMap.getAutoMapping();
    } else {
      // todo
      if (isNested) {
        return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
      } else {
        return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
      }
    }
  }

  //
  // PROPERTY MAPPINGS
  //

  /**
   * 映射 ResultMap 中明确指定需要映射的列
   *
   * @see #applyAutomaticMappings(ResultSetWrapper, ResultMap, MetaObject, String)
   * @param rsw
   * @param resultMap
   * @param metaObject
   * @param lazyLoader
   * @param columnPrefix
   * @return  如果没有获取到数据值则返回 false
   * @throws SQLException
   */
  private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    // 从 ResultSetWrapper 中获取以映射的列名集合
    final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
    // 标识是否获取到属性值
    boolean foundValues = false;
    // 从 ResultMap 中获取非 <constructor> 节点下的映射关系
    final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();

    // 遍历映射关系
    for (ResultMapping propertyMapping : propertyMappings) {
      // 处理列前缀
      String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      // todo
      if (propertyMapping.getNestedResultMapId() != null) {
        // the user added a column attribute to a nested result map, ignore it
        column = null;
      }

      // 以下逻辑有三个场景
      // 1. propertyMapping.isCompositeResult();          嵌套查询传递多个参数的方式
      // 2. (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH)); 普通属性的映射
      // 3. propertyMapping.getResultSet() != null;       多结果集
      if (propertyMapping.isCompositeResult()
          || (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH)))
          || propertyMapping.getResultSet() != null) {

        // 获取属性的值
        Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader, columnPrefix);
        // issue #541 make property optional
        final String property = propertyMapping.getProperty();
        if (property == null) {
          continue;
        } else if (value == DEFERRED) {
          foundValues = true;
          continue;
        }
        // 修改 foundValues 标识
        if (value != null) {
          foundValues = true;
        }
        if (value != null || (configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive())) {
          // gcode issue #377, call setter on nulls (value is not 'found')
          // 将获取到的值设置到结果对象中
          metaObject.setValue(property, value);
        }
      }
    }
    return foundValues;
  }

  /**
   * 获取属性映射值
   *
   * @param rs
   * @param metaResultObject
   * @param propertyMapping
   * @param lazyLoader
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    // 判断是否存在嵌套查询
    if (propertyMapping.getNestedQueryId() != null) {
      // 获取嵌套查询的属性值
      return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
    } else if (propertyMapping.getResultSet() != null) {
      addPendingChildRelation(rs, metaResultObject, propertyMapping);   // TODO is that OK?
      return DEFERRED;
    } else {
      // 获取 ResultMapping 中记录的 TypeHandler
      final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
      // 处理列前缀
      final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      // 使用 TypeHandler 获取属性值
      return typeHandler.getResult(rs, column);
    }
  }

  /**
   * 获取 ResultSet 中存在, 但 ResultMap 中没有明确映射的列所对应的 UnMappedColumnAutoMapping 集合
   *
   * @param rsw
   * @param resultMap
   * @param metaObject
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
    // autoMappingsCache 缓存的 key
    final String mapKey = resultMap.getId() + ":" + columnPrefix;
    // 从自动映射缓存中获取
    List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);

    // 缓存数据为空
    if (autoMapping == null) {
      autoMapping = new ArrayList<>();
      // 从 ResultSetWrapper 中获取未映射的列名集合
      final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
      // 遍历未映射列名集合
      for (String columnName : unmappedColumnNames) {
        String propertyName = columnName;
        // 处理列前缀
        if (columnPrefix != null && !columnPrefix.isEmpty()) {
          // When columnPrefix is specified,
          // ignore columns without the prefix.
          if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
            propertyName = columnName.substring(columnPrefix.length());
          } else {
            continue;
          }
        }
        // 在结果集对象中查询指定的属性名
        final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
        // 判断属性是否存在并且存在 setter 方法
        if (property != null && metaObject.hasSetter(property)) {
          if (resultMap.getMappedProperties().contains(property)) {
            continue;
          }
          // 获取属性 Class 类型
          final Class<?> propertyType = metaObject.getSetterType(property);
          // 检测属性与当前列 JdbcTye 是否存在 TypeHandler
          if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
            // 获取 TypeHandler
            final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
            // 创建 UnMappedColumnAutoMapping 对象并添加到 autoMapping 集合中
            autoMapping.add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));
          } else {
            // 抛出异常或记录日志
            configuration.getAutoMappingUnknownColumnBehavior()
                .doAction(mappedStatement, columnName, property, propertyType);
          }
        } else {
          // 抛出异常或记录日志
          configuration.getAutoMappingUnknownColumnBehavior()
              .doAction(mappedStatement, columnName, (property != null) ? property : propertyName, null);
        }
      }
      // 将 autoMapping 添加到 autoMappingsCache 缓存中 (key 由 resultMap.getId() + ":" + columnPrefix 组成)
      autoMappingsCache.put(mapKey, autoMapping);
    }
    return autoMapping;
  }

  /**
   * 自动映射 ResultMap 中未明确指定列
   *
   * @param rsw
   * @param resultMap
   * @param metaObject
   * @param columnPrefix
   * @return  如果没有获取到数据值则返回 false
   * @throws SQLException
   */
  private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
    // 获取 ResultSet 中存在, 但 ResultMap 中没有明确映射的列所对应的 UnMappedColumnAutoMapping 集合
    List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
    // 标识是否获取到属性值
    boolean foundValues = false;

    // 存在未映射的列信息
    if (!autoMapping.isEmpty()) {
      // 遍历
      for (UnMappedColumnAutoMapping mapping : autoMapping) {
        // 通过 TypeHandler 获取列对应的值
        final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
        // 修改 foundValues 标识
        if (value != null) {
          foundValues = true;
        }
        // 将获取到的值设置到结果对象中
        if (value != null || (configuration.isCallSettersOnNulls() && !mapping.primitive)) {
          // gcode issue #377, call setter on nulls (value is not 'found')
          metaObject.setValue(mapping.property, value);
        }
      }
    }
    return foundValues;
  }

  // MULTIPLE RESULT SETS

  private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
    CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
    List<PendingRelation> parents = pendingRelations.get(parentKey);
    if (parents != null) {
      for (PendingRelation parent : parents) {
        if (parent != null && rowValue != null) {
          linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
        }
      }
    }
  }

  private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping) throws SQLException {
    CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
    PendingRelation deferLoad = new PendingRelation();
    deferLoad.metaObject = metaResultObject;
    deferLoad.propertyMapping = parentMapping;
    List<PendingRelation> relations = MapUtil.computeIfAbsent(pendingRelations, cacheKey, k -> new ArrayList<>());
    // issue #255
    relations.add(deferLoad);
    ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
    if (previous == null) {
      nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
    } else {
      if (!previous.equals(parentMapping)) {
        throw new ExecutorException("Two different properties are mapped to the same resultSet");
      }
    }
  }

  private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns) throws SQLException {
    CacheKey cacheKey = new CacheKey();
    cacheKey.update(resultMapping);
    if (columns != null && names != null) {
      String[] columnsArray = columns.split(",");
      String[] namesArray = names.split(",");
      for (int i = 0; i < columnsArray.length; i++) {
        Object value = rs.getString(columnsArray[i]);
        if (value != null) {
          cacheKey.update(namesArray[i]);
          cacheKey.update(value);
        }
      }
    }
    return cacheKey;
  }

  //
  // INSTANTIATION & CONSTRUCTOR MAPPING
  //

  /**
   * 创建结果对象
   *
   * @param rsw
   * @param resultMap
   * @param lazyLoader
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
    // 标识结果对象是否是否由构造函数创建
    this.useConstructorMappings = false; // reset previous mapping result
    // 记录构造函数的参数类型
    final List<Class<?>> constructorArgTypes = new ArrayList<>();
    // 记录构造函数的参数
    final List<Object> constructorArgs = new ArrayList<>();

    // 创建当前行对应的结果对象 (如果由构造方法创建则会将构造参数类型与属性值记录到 constructorArgTypes 与 constructorArgs 中)
    Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);

    // todo
    if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      // 获取结果集映射集合
      final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
      // 遍历
      for (ResultMapping propertyMapping : propertyMappings) {
        // issue gcode #109 && issue #149
        // [延迟加载] 如果存在嵌套查询,并且是延迟加载.那么创建代理对象并返回
        if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {

          // [延迟加载] 创建代理对象
          resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
          break;
        }
      }
    }
    // 如果结果对象不为空,并且构造参数值不为空. 表示该对象是由构造函数创建
    this.useConstructorMappings = resultObject != null && !constructorArgTypes.isEmpty(); // set current mapping result
    // 返回创建好结果对象
    return resultObject;
  }

  /**
   * 创建结果对象
   *
   * @param rsw
   * @param resultMap
   * @param constructorArgTypes
   * @param constructorArgs
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix)
      throws SQLException {
    // 获取 ResultMap 中的 type 属性(该属性表示最终映射的结果类型)
    final Class<?> resultType = resultMap.getType();
    // 创建该类型对应的 MetaClass
    final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
    // 获取 ResultMap 中记录的 <constructor> 节点信息
    final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();

    // 创建结果对象有以下4种情况
    if (hasTypeHandlerForResultObject(rsw, resultType)) {
      // 场景1. 结果集只有一列, 并且结果对象存在对应的 TypeHandler
      return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
    } else if (!constructorMappings.isEmpty()) {
      // 场景2. 使用 ResultMap 中记录的 <constructor> 节点信息, 创建对象 (该方法会将解析出来的构造参数类型与属性值记录到 constructorArgTypes 与 constructorArgs 集合中)
      return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
    } else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
      // 场景3. 使用无参构造方法创建对象
      return objectFactory.create(resultType);
    } else if (shouldApplyAutomaticMappings(resultMap, false)) {
      // 场景4. 通过自动映射的方式查找合适的构造方法并创建对象 (该方法会将解析出来的构造参数类型与属性值记录到 constructorArgTypes 与 constructorArgs 集合中)
      return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs);
    }
    // 创建失败,抛出异常
    throw new ExecutorException("Do not know how to create an instance of " + resultType);
  }

  /**
   * 使用 ResultMap 中记录的 <constructor> 节点信息, 创建对象
   *
   * @param rsw
   * @param resultType
   * @param constructorMappings
   * @param constructorArgTypes
   * @param constructorArgs
   * @param columnPrefix
   * @return
   */
  Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType, List<ResultMapping> constructorMappings,
                                         List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix) {
    // 标识获取到了值
    boolean foundValues = false;

    // 遍历 constructorMappings 集合, 该过程会将解析出来的构造参数类型与属性值记录到 constructorArgTypes 与 constructorArgs 集合中
    for (ResultMapping constructorMapping : constructorMappings) {
      // 获取当前构造参数的类型
      final Class<?> parameterType = constructorMapping.getJavaType();
      final String column = constructorMapping.getColumn();
      final Object value;
      try {
        if (constructorMapping.getNestedQueryId() != null) {
          // 存在嵌套查询 todo
          value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
        } else if (constructorMapping.getNestedResultMapId() != null) {
          // 存在嵌套映射 todo
          final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
          value = getRowValue(rsw, resultMap, getColumnPrefix(columnPrefix, constructorMapping));
        } else {
          // 直接获取该列的值，然后经过 TypeHandler 对象的转换，得到构造函数的参数值
          final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
          value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
        }
      } catch (ResultMapException | SQLException e) {
        throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
      }
      // 记录构造函数的类型
      constructorArgTypes.add(parameterType);
      // 记录构造函数的参数
      constructorArgs.add(value);
      // 修改标识
      foundValues = value != null || foundValues;
    }
    // 根据 foundValues 标识判断是否创建对象
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  /**
   * 通过自动映射的方式查找合适的构造方法并创建对象
   *
   * @param rsw
   * @param resultType
   * @param constructorArgTypes
   * @param constructorArgs
   * @return
   * @throws SQLException
   */
  private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) throws SQLException {
    // 获取所有的构造函数
    final Constructor<?>[] constructors = resultType.getDeclaredConstructors();
    // 获取默认的构造函数
    final Constructor<?> defaultConstructor = findDefaultConstructor(constructors);

    // 使用默认构造函数创建对象
    if (defaultConstructor != null) {
      return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, defaultConstructor);
    } else {
      // 遍历所有的构造函数
      for (Constructor<?> constructor : constructors) {
        // 检测构造函数否则是否符合条件(参数个数与列个数一致,并且存在对应的TypeHandler)
        if (allowedConstructorUsingTypeHandlers(constructor, rsw.getJdbcTypes())) {
          return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, constructor);
        }
      }
    }
    // 找不到符合条件的构造函数时抛出异常
    throw new ExecutorException("No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
  }

  /**
   * 使用构造函数创建对象
   *
   * @see #createParameterizedResultObject(ResultSetWrapper, Class, List, List, List, String)
   * @param rsw
   * @param resultType
   * @param constructorArgTypes
   * @param constructorArgs
   * @param constructor
   * @return
   * @throws SQLException
   */
  private Object createUsingConstructor(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, Constructor<?> constructor) throws SQLException {
    // 标识获取到了值
    boolean foundValues = false;
    // 遍历构造方法参数类型
    for (int i = 0; i < constructor.getParameterTypes().length; i++) {
      Class<?> parameterType = constructor.getParameterTypes()[i];
      String columnName = rsw.getColumnNames().get(i);
      // 获取 TypeHandler 对象
      TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
      // 获取该列的值，然后经过 TypeHandler 对象的转换，得到构造函数的参数值
      Object value = typeHandler.getResult(rsw.getResultSet(), columnName);
      // 记录构造函数的类型
      constructorArgTypes.add(parameterType);
      // 记录构造函数的参数
      constructorArgs.add(value);
      // 修改标识
      foundValues = value != null || foundValues;
    }
    // 根据 foundValues 标识判断是否创建对象
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  /**
   * 获取默认的构造函数或添加了{@link AutomapConstructor}注解的构造函数
   *
   * @param constructors
   * @return
   */
  private Constructor<?> findDefaultConstructor(final Constructor<?>[] constructors) {
    // 当构造函数只有一个的时候直接获取第一个
    if (constructors.length == 1) {
      return constructors[0];
    }

    // 查找添加了 AutomapConstructor 注解的构造函数
    for (final Constructor<?> constructor : constructors) {
      if (constructor.isAnnotationPresent(AutomapConstructor.class)) {
        return constructor;
      }
    }
    return null;
  }

  /**
   * 构造函数是否符合条件
   *
   * @param constructor
   * @param jdbcTypes
   * @return
   */
  private boolean allowedConstructorUsingTypeHandlers(final Constructor<?> constructor, final List<JdbcType> jdbcTypes) {
    // 参数个数与列个数一致
    final Class<?>[] parameterTypes = constructor.getParameterTypes();
    if (parameterTypes.length != jdbcTypes.size()) {
      return false;
    }
    // 存在参数与 JdbcType 类型对应的 TypeHandler
    for (int i = 0; i < parameterTypes.length; i++) {
      if (!typeHandlerRegistry.hasTypeHandler(parameterTypes[i], jdbcTypes.get(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * 处理结果集只有一列，并且结果对象存在对应的 TypeHandler
   *
   * 使用 TypeHandler 创建结果对象
   *
   * @param rsw
   * @param resultMap
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
    final Class<?> resultType = resultMap.getType();
    final String columnName;

    // 获取一列的字段名
    if (!resultMap.getResultMappings().isEmpty()) {
      final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
      final ResultMapping mapping = resultMappingList.get(0);
      columnName = prependPrefix(mapping.getColumn(), columnPrefix);
    } else {
      columnName = rsw.getColumnNames().get(0);
    }
    // 获取对应的 TypeHandler 创建结果对象
    final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
    return typeHandler.getResult(rsw.getResultSet(), columnName);
  }

  //
  // NESTED QUERY
  //

  private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix) throws SQLException {
    final String nestedQueryId = constructorMapping.getNestedQueryId();
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    if (nestedQueryParameterObject != null) {
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      final Class<?> targetType = constructorMapping.getJavaType();
      final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
      value = resultLoader.loadResult();
    }
    return value;
  }

  /**
   * 获取嵌套查询的值
   */
  private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {

    // 嵌套查询的MappedStatementId
    final String nestedQueryId = propertyMapping.getNestedQueryId();

    // 嵌套查询的属性名
    final String property = propertyMapping.getProperty();

    // 获取嵌套查询的MappedStatement
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);

    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    if (nestedQueryParameterObject != null) {
      // 获取boundSql
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      // 创建缓存key
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      final Class<?> targetType = propertyMapping.getJavaType();

      if (executor.isCached(nestedQuery, key)) {
        executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
        value = DEFERRED;
      } else {
        // 创建结果加载对象(包含加载逻辑)
        final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);

        // 如果是延迟加载
        if (propertyMapping.isLazy()) {
          // 将结果对象存再延迟加载器中
          lazyLoader.addLoader(property, metaResultObject, resultLoader);
          // 返回默认的object对象
          value = DEFERRED;
        } else {
          // 否则立即加载
          value = resultLoader.loadResult();
        }
      }
    }
    return value;
  }

  private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    if (resultMapping.isCompositeResult()) {
      return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    } else {
      return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    }
  }

  private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    final TypeHandler<?> typeHandler;
    if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
      typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
    } else {
      typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
    }
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    final Object parameterObject = instantiateParameterObject(parameterType);
    final MetaObject metaObject = configuration.newMetaObject(parameterObject);
    boolean foundValues = false;
    for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
      final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
      final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
      final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
      // issue #353 & #560 do not execute nested query if key is null
      if (propValue != null) {
        metaObject.setValue(innerResultMapping.getProperty(), propValue);
        foundValues = true;
      }
    }
    return foundValues ? parameterObject : null;
  }

  private Object instantiateParameterObject(Class<?> parameterType) {
    if (parameterType == null) {
      return new HashMap<>();
    } else if (ParamMap.class.equals(parameterType)) {
      return new HashMap<>(); // issue #649
    } else {
      return objectFactory.create(parameterType);
    }
  }

  //
  // DISCRIMINATOR
  //

  /**
   * 根据 ResultMap 中的 Discriminator 对象选择出正确的 ResultMap
   *
   *  1. 获取 Discriminator 对应 column 的值 {@link #getDiscriminatorValue}
   *  2. 根据 column 的值获取对应 ResultMap 的 Id
   *
   * @param rs
   * @param resultMap
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix) throws SQLException {
    // 记录已经处理过的 ResultMap 的id
    Set<String> pastDiscriminators = new HashSet<>();
    // 获取 ResultMap 中的 Discriminator 对象
    Discriminator discriminator = resultMap.getDiscriminator();

    // 检测 Discriminator 对象是否存在, 不存在直接返回参数中的 ResultMap. 存在则解析出正确的 ResultMap
    while (discriminator != null) {
      // 获取 Discriminator 对应 column 的值
      final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
      // 根据 column 的值获取对应的 ResultMap 的 id
      final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));

      // 判断是否存在
      if (configuration.hasResultMap(discriminatedMapId)) {
        // 获取 ResultMap
        resultMap = configuration.getResultMap(discriminatedMapId);
        // 记录当前的 Discriminator 引用
        Discriminator lastDiscriminator = discriminator;
        // 修改 discriminator 引用 (递归解析)
        discriminator = resultMap.getDiscriminator();

        // 检测 Discriminator 是否出现了环形引用
        if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
          break;
        }
      } else {
        break;
      }
    }
    return resultMap;
  }

  /**
   * 获取 Discriminator 对应字段的值
   *
   * @param rs
   * @param discriminator
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix) throws SQLException {
    final ResultMapping resultMapping = discriminator.getResultMapping();
    final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
    // 根据 TypeHandler 获取指定列的值
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  /**
   * 处理列前缀
   *
   * @param columnName
   * @param prefix
   * @return
   */
  private String prependPrefix(String columnName, String prefix) {
    if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
      return columnName;
    }
    return prefix + columnName;
  }

  //
  // HANDLE NESTED RESULT MAPS
  //

  private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    // 结果上下文
    final DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    ResultSet resultSet = rsw.getResultSet();
    skipRows(resultSet, rowBounds);

    Object rowValue = previousRowValue;

    while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
      final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
      Object partialObject = nestedResultObjects.get(rowKey);
      // issue #577 && #542
      if (mappedStatement.isResultOrdered()) {
        if (partialObject == null && rowValue != null) {
          nestedResultObjects.clear();

          // 保存对象
          storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
      } else {
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
        if (partialObject == null) {
          storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
      }
    }
    if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(resultContext, rowBounds)) {
      storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
      previousRowValue = null;
    } else if (rowValue != null) {
      previousRowValue = rowValue;
    }
  }

  //
  // NESTED RESULT MAP (JOIN MAPPING)
  //

  private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
    boolean foundValues = false;
    for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
      final String nestedResultMapId = resultMapping.getNestedResultMapId();
      if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
        try {
          final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
          final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
          if (resultMapping.getColumnPrefix() == null) {
            // try to fill circular reference only when columnPrefix
            // is not specified for the nested result map (issue #215)
            Object ancestorObject = ancestorObjects.get(nestedResultMapId);
            if (ancestorObject != null) {
              if (newObject) {
                linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
              }
              continue;
            }
          }
          final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
          final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
          Object rowValue = nestedResultObjects.get(combinedKey);
          boolean knownValue = rowValue != null;
          instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory
          if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
            rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
            if (rowValue != null && !knownValue) {
              linkObjects(metaObject, resultMapping, rowValue);
              foundValues = true;
            }
          }
        } catch (SQLException e) {
          throw new ExecutorException("Error getting nested result map values for '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
        }
      }
    }
    return foundValues;
  }

  private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
    final StringBuilder columnPrefixBuilder = new StringBuilder();
    if (parentPrefix != null) {
      columnPrefixBuilder.append(parentPrefix);
    }
    if (resultMapping.getColumnPrefix() != null) {
      columnPrefixBuilder.append(resultMapping.getColumnPrefix());
    }
    return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
  }

  private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSetWrapper rsw) throws SQLException {
    Set<String> notNullColumns = resultMapping.getNotNullColumns();
    if (notNullColumns != null && !notNullColumns.isEmpty()) {
      ResultSet rs = rsw.getResultSet();
      for (String column : notNullColumns) {
        rs.getObject(prependPrefix(column, columnPrefix));
        if (!rs.wasNull()) {
          return true;
        }
      }
      return false;
    } else if (columnPrefix != null) {
      for (String columnName : rsw.getColumnNames()) {
        if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix.toUpperCase(Locale.ENGLISH))) {
          return true;
        }
      }
      return false;
    }
    return true;
  }

  private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix) throws SQLException {
    ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
    return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
  }

  //
  // UNIQUE RESULT KEY
  //

  private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
    final CacheKey cacheKey = new CacheKey();
    cacheKey.update(resultMap.getId());
    List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
    if (resultMappings.isEmpty()) {
      if (Map.class.isAssignableFrom(resultMap.getType())) {
        createRowKeyForMap(rsw, cacheKey);
      } else {
        createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
      }
    } else {
      createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
    }
    if (cacheKey.getUpdateCount() < 2) {
      return CacheKey.NULL_CACHE_KEY;
    }
    return cacheKey;
  }

  private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
    if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
      CacheKey combinedKey;
      try {
        combinedKey = rowKey.clone();
      } catch (CloneNotSupportedException e) {
        throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
      }
      combinedKey.update(parentRowKey);
      return combinedKey;
    }
    return CacheKey.NULL_CACHE_KEY;
  }

  private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
    List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
    if (resultMappings.isEmpty()) {
      resultMappings = resultMap.getPropertyResultMappings();
    }
    return resultMappings;
  }

  private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
    for (ResultMapping resultMapping : resultMappings) {
      if (resultMapping.isSimple()) {
        final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
        final TypeHandler<?> th = resultMapping.getTypeHandler();
        List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
        // Issue #114
        if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
          final Object value = th.getResult(rsw.getResultSet(), column);
          if (value != null || configuration.isReturnInstanceForEmptyRow()) {
            cacheKey.update(column);
            cacheKey.update(value);
          }
        }
      }
    }
  }

  private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, String columnPrefix) throws SQLException {
    final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
    List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
    for (String column : unmappedColumnNames) {
      String property = column;
      if (columnPrefix != null && !columnPrefix.isEmpty()) {
        // When columnPrefix is specified, ignore columns without the prefix.
        if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
          property = column.substring(columnPrefix.length());
        } else {
          continue;
        }
      }
      if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
        String value = rsw.getResultSet().getString(column);
        if (value != null) {
          cacheKey.update(column);
          cacheKey.update(value);
        }
      }
    }
  }

  private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
    List<String> columnNames = rsw.getColumnNames();
    for (String columnName : columnNames) {
      final String value = rsw.getResultSet().getString(columnName);
      if (value != null) {
        cacheKey.update(columnName);
        cacheKey.update(value);
      }
    }
  }

  private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
    final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
    if (collectionProperty != null) {
      final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
      targetMetaObject.add(rowValue);
    } else {
      metaObject.setValue(resultMapping.getProperty(), rowValue);
    }
  }

  private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
    final String propertyName = resultMapping.getProperty();
    Object propertyValue = metaObject.getValue(propertyName);
    if (propertyValue == null) {
      Class<?> type = resultMapping.getJavaType();
      if (type == null) {
        type = metaObject.getSetterType(propertyName);
      }
      try {
        if (objectFactory.isCollection(type)) {
          propertyValue = objectFactory.create(type);
          metaObject.setValue(propertyName, propertyValue);
          return propertyValue;
        }
      } catch (Exception e) {
        throw new ExecutorException("Error instantiating collection property for result '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
      }
    } else if (objectFactory.isCollection(propertyValue.getClass())) {
      return propertyValue;
    }
    return null;
  }

  /**
   * 结果对象类型是否存在 TypeHandler
   *
   * @param rsw
   * @param resultType
   * @return
   */
  private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
    if (rsw.getColumnNames().size() == 1) {
      return typeHandlerRegistry.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
    }
    return typeHandlerRegistry.hasTypeHandler(resultType);
  }

}
