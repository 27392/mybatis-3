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
 * DefaultResultSetHandler {@link ResultSetHandler} 的默认实现
 *
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
  // 记录嵌套循环所有结果对象
  private final Map<CacheKey, Object> nestedResultObjects = new HashMap<>();
  // 处理嵌套查询循环引用(key 是 resultMap.id)
  private final Map<String, Object> ancestorObjects = new HashMap<>();
  private Object previousRowValue;

  // multiple resultsets
  // 记录多结果集与 ResultMapping 的关系 (key 是 resultSet, value 是 ResultMapping)
  private final Map<String, ResultMapping> nextResultMaps = new HashMap<>();
  // 记录多结果的字段信息 (key 是 CacheKey, value 是 PendingRelation集合)
  private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<>();

  // Cached Automappings
  // 自动映射缓存 (key 是 ResultMap.Id + ":" + columnPrefix, value 是 ResultSet 中存在但 ResultMap 未映射的列信息).
  private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<>();

  // temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
  // 标识结果对象是否是否由构造函数创建
  private boolean useConstructorMappings;

  /**
   * 多结果的属性关系
   */
  private static class PendingRelation {
    // 结果对象对应的 MetaObject
    public MetaObject metaObject;
    // 使用了 resultSets 的 ResultMapping
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
      // 处理结果集 (根据 ResultMap 中定义的映射规则对 ResultSet 进行映射，并将映射的结果对象添加到 multipleResults 集合中)
      handleResultSet(rsw, resultMap, multipleResults, null);
      // 获取下一个结果集
      rsw = getNextResultSet(stmt);
      // 清空 nestedResultObjects 集合
      cleanUpAfterHandlingResultSet();
      // resultSetCount 递增
      resultSetCount++;
    }

    // 处理多结果集
    // 获取 resultSets 属性 (resultSets="names,items")
    String[] resultSets = mappedStatement.getResultSets();
    if (resultSets != null) {
      while (rsw != null && resultSetCount < resultSets.length) {
          // 根据 resultSet 名称，获取未处理的 ResultMapping
          ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
          if (parentMapping != null) {
            // 获取 ResultMapping 对应的 ResultMap
            String nestedResultMapId = parentMapping.getNestedResultMapId();
            ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
            // 处理结果集 (与前面不同这里没有指定 multipleResults 但指定了 parentMapping)
            handleResultSet(rsw, resultMap, null, parentMapping);
          }
          // 获取下一个结果集
          rsw = getNextResultSet(stmt);
          // 清空 nestedResultObjects 集合
          cleanUpAfterHandlingResultSet();
        // resultSetCount 递增
        resultSetCount++;
      }
    }

    // 如果 multipleResults 只有一个返回第一个，否则直接返回
    return collapseSingleResultList(multipleResults);
  }

  @Override
  public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());

    // 获取第一个 ResultSet 对象(可能存在多个 ResultSet 的情况)
    ResultSetWrapper rsw = getFirstResultSet(stmt);

    // 获取 ResultMap 集合 (如果存在 ResultSet 的情况可以在配置 <resultMap id="result1,result2">)
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();

    // ResultMap 的数量
    int resultMapCount = resultMaps.size();
    // 校验 ResultMap 集合是否为空；为空会抛出异常
    validateResultMapsCount(rsw, resultMapCount);
    // 检查是否只有一个 ResultMap
    if (resultMapCount != 1) {
      throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
    }

    // 使用第一个 ResultMap 对象
    ResultMap resultMap = resultMaps.get(0);
    // 创建 DefaultCursor 返回
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
        // 处理多结果集中的映射
        // https://mybatis.org/mybatis-3/zh/sqlmap-xml.html#%E5%85%B3%E8%81%94%E7%9A%84%E5%A4%9A%E7%BB%93%E6%9E%9C%E9%9B%86%EF%BC%88resultset%EF%BC%89
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

  /**
   * 判断是否是单一的结果集
   *
   * @param multipleResults
   * @return
   */
  @SuppressWarnings("unchecked")
  private List<Object> collapseSingleResultList(List<Object> multipleResults) {
    return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults;
  }

  //
  // HANDLE ROWS FOR SIMPLE RESULTMAP
  //

  /**
   * 处理行值（根据类型）
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
      // 检查是否允许在嵌套语句中使用 ResultHandler
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
      // 将结果对象保存到父对象对应的属性中（嵌套查询或嵌套映射）
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

    // ResultLoaderMap 与延迟加载相关
    final ResultLoaderMap lazyLoader = new ResultLoaderMap();

    // 1. 创建行记录映射会后得到的结果对象(结果对象的类型由<resultMap>节点的 type 属性指定)
    Object rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);

    // 结果对象不为空,并且结果对象没有对应的 TypeHandler.(一般来说只有当结果对象是基本类型的情况下才会出现有对应的 TypeHandler. 像查询总条数等操作)
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

  /**
   * 将行记录映射成对象(嵌套类型)
   *
   * @see #getRowValue(ResultSetWrapper, ResultMap, String)
   *
   * @param rsw
   * @param resultMap
   * @param combinedKey
   * @param columnPrefix
   * @param partialObject
   * @return
   * @throws SQLException
   */
  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix, Object partialObject) throws SQLException {
    final String resultMapId = resultMap.getId();
    // 1. 检测外层对象是否已经存在 (如果外层对象不存在则表示需要处理外层对象的映射)
    Object rowValue = partialObject;
    if (rowValue != null) {
      // 3.1 创建创建外层对象对应的 MetaObject 对象
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      // 3.2 将外层对象添加到 ancestorObjects 属性中 (处理循环引用问题)
      putAncestor(rowValue, resultMapId);
      // 3.3 处理嵌套类型
      applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
      // 3.4 将外层对象从 ancestorObjects 中删除 (处理循环引用问题)
      ancestorObjects.remove(resultMapId);
    } else {
      final ResultLoaderMap lazyLoader = new ResultLoaderMap();
      // 2.1 创建外层对象
      rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);

      // 结果对象不为空,并且结果对象没有对应的 TypeHandler.(一般来说只有当结果对象是基本类型的情况下才会出现有对应的 TypeHandler. 像查询总条数等操作)
      if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
        // 创建创建外层对象对应的 MetaObject 对象
        final MetaObject metaObject = configuration.newMetaObject(rowValue);
        boolean foundValues = this.useConstructorMappings;

        // 检测是否开启自动映射
        if (shouldApplyAutomaticMappings(resultMap, true)) {
          // 2.2 处理自动映射
          foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
        }
        // 2.3 处理 ResultMap 中明确指定需要映射
        foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
        // 2.4 将对象添加到 ancestorObjects 属性中 (处理循环引用问题)
        putAncestor(rowValue, resultMapId);
        // 2.5 处理嵌套映射
        foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
        // 2.6 将外层对象从 ancestorObjects 中删除 (处理循环引用问题)
        ancestorObjects.remove(resultMapId);

        // 2.7 如果没有映射任何的属性, 则根据 returnInstanceForEmptyRow 配置决定返回空对象还是 null
        foundValues = lazyLoader.size() > 0 || foundValues;
        rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
      }
      // 2.8 将对象保存到 nestedResultObjects 集合中
      if (combinedKey != CacheKey.NULL_CACHE_KEY) {
        nestedResultObjects.put(combinedKey, rowValue);
      }
    }
    return rowValue;
  }

  /**
   * 将对象记录到 ancestorObjects 中(主要用来处理嵌套循环引用)
   *
   * @param resultObject
   * @param resultMapId
   */
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
      // 跳过嵌套映射 (它将有其它方法来处理)
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

    // 以下存在三种情况
    // 1. 存在嵌套查询
    // 2. 存在 resultSet 属性
    // 3. 默认情况

    if (propertyMapping.getNestedQueryId() != null) {
      // 获取嵌套查询的属性值
      return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
    } else if (propertyMapping.getResultSet() != null) {
      // 1. 将 ResultMapping 与其对应的 resultSet 保存在 nextResultMaps 中
      // 2. 将 ResultMapping 与其对应的 MetaObject 保存在 pendingRelations 中
      // 3. 返回默认值
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

  /**
   * 将结果对象保存到父对象对应的属性中（嵌套查询或嵌套映射）
   *
   * @param rs
   * @param parentMapping
   * @param rowValue
   * @throws SQLException
   */
  private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
    // 创建多结果缓存 CacheKey
    CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
    // 获取多结果映射关系
    List<PendingRelation> parents = pendingRelations.get(parentKey);
    if (parents != null) {
      // 遍历属性
      for (PendingRelation parent : parents) {
        if (parent != null && rowValue != null) {
          // 将结果设置到外层对象中
          linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
        }
      }
    }
  }

  /**
   * 记录待加载映射与父对象的关系
   *
   * @param rs
   * @param metaResultObject  对象
   * @param parentMapping     属性映射
   * @throws SQLException
   */
  private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping) throws SQLException {
    // 创建多结果集缓存 CacheKey
    CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());

    // 创建 PendingRelation 对象
    PendingRelation deferLoad = new PendingRelation();
    deferLoad.metaObject = metaResultObject;
    deferLoad.propertyMapping = parentMapping;

    // 将 PendingRelation 对象添加到 pendingRelations 集合中
    List<PendingRelation> relations = MapUtil.computeIfAbsent(pendingRelations, cacheKey, k -> new ArrayList<>());
    // issue #255
    relations.add(deferLoad);

    // 记录 ResultMapping 与其对应的结果集名称
    ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
    if (previous == null) {
      nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
    } else {
      // 如果同名的结果集对应不同的 ResultMapping , 抛出异常
      if (!previous.equals(parentMapping)) {
        throw new ExecutorException("Two different properties are mapped to the same resultSet");
      }
    }
  }

  /**
   * 创建多结果缓存 CacheKey
   *
   * @param rs
   * @param resultMapping
   * @param names
   * @param columns
   * @return
   * @throws SQLException
   */
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
   * 创建结果对象(包括创建代理对象)
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
    // 记录构造函数的参数类型 (用于创建动态代理对象)
    final List<Class<?>> constructorArgTypes = new ArrayList<>();
    // 记录构造函数的参数 (用于创建动态代理对象)
    final List<Object> constructorArgs = new ArrayList<>();

    // 创建当前行对应的结果对象,调用重载方法 (如果由构造方法创建则会将构造参数类型与属性值记录到 constructorArgTypes 与 constructorArgs 中)
    Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);

    // 结果对象不为空,并且结果对象没有对应的 TypeHandler. (一般来说只有当结果对象是基本类型的情况下才会出现有对应的 TypeHandler. 像查询总条数等操作)
    if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      // 获取结果集映射集合
      final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
      // 遍历
      for (ResultMapping propertyMapping : propertyMappings) {
        // issue gcode #109 && issue #149
        // 如果存在嵌套查询, 并且是延迟加载. 那么创建代理对象并返回
        if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
          // 创建代理对象
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
   * @see #createResultObject(ResultSetWrapper, ResultMap, ResultLoaderMap, String)
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
      // 场景1. 结果集只有一列, 并且结果对象存在对应的 TypeHandler. (例如返回基本类型的情况 count(*))
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
          // 获取嵌套查询的参数值
          value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
        } else if (constructorMapping.getNestedResultMapId() != null) {
          // 获取嵌套映射的参数值
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

  /**
   * 获取嵌套查询构造函数的值
   *
   * @param rs
   * @param constructorMapping
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix) throws SQLException {
    // 获取嵌套查询的查询id
    final String nestedQueryId = constructorMapping.getNestedQueryId();
    // 获取嵌套查询的 MappedStatement
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);

    // 获取传递给嵌套查询的参数值
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);

    Object value = null;
    if (nestedQueryParameterObject != null) {
      // 获取嵌套查询对应的 BoundSql
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      // 创建嵌套查询对应的 CacheKey
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      // 获取嵌套查询的映射类型
      final Class<?> targetType = constructorMapping.getJavaType();
      // 创建 ResultLoader 对象
      final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
      // 调用 ResultLoader.loadResult() 执行嵌套查询, 得到相应的构造方法参数值
      value = resultLoader.loadResult();
    }
    return value;
  }

  /**
   * 获取嵌套查询的属性值
   *
   * @see #getNestedQueryConstructorValue(ResultSet, ResultMapping, String)
   * @param rs
   * @param metaResultObject
   * @param propertyMapping
   * @param lazyLoader
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {

    // 嵌套查询的 MappedStatement.Id
    final String nestedQueryId = propertyMapping.getNestedQueryId();
    // 嵌套查询的属性名
    final String property = propertyMapping.getProperty();
    // 获取嵌套查询的 MappedStatement
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);

    // 获取嵌套查询
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);

    Object value = null;
    if (nestedQueryParameterObject != null) {
      // 获取嵌套查询的 BoundSql
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      // 创建嵌套查询对应的 CacheKey
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      // 获取嵌套查询对应的映射类型
      final Class<?> targetType = propertyMapping.getJavaType();

      // 检查缓存中是否存在该嵌套查询的结果集
      if (executor.isCached(nestedQuery, key)) {
        executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
        value = DEFERRED;
      } else {
        // 创建 ResultLoader 对象
        final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);

        if (propertyMapping.isLazy()) {
          // 如果该映射配置了延迟加载,则将其添加到 ResultLoaderMap 中, 等待真正使用时再执行嵌套查询并得到结果对象
          lazyLoader.addLoader(property, metaResultObject, resultLoader);
          // 返回默认的 object 对象
          value = DEFERRED;
        } else {
          // 没有配置延迟加载,则直接调用 loadResult() 方法执行嵌套查询，并得到结果对象
          value = resultLoader.loadResult();
        }
      }
    }
    return value;
  }

  /**
   * 获取嵌套查询的参数值
   *
   * @see #prepareCompositeKeyParameter  处理多个传递属性
   * @see #prepareSimpleKeyParameter     处理单个传递属性
   * @param rs
   * @param resultMapping
   * @param parameterType
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    // 是否是传递多个参数的方式
    if (resultMapping.isCompositeResult()) {
      // 处理多个参数
      return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    } else {
      // 处理单一的参数
      return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    }
  }

  /**
   * 获取嵌套查询的参数值(单一参数)
   *
   * @param rs
   * @param resultMapping
   * @param parameterType
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    final TypeHandler<?> typeHandler;
    // 获取 typeHandler
    if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
      typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
    } else {
      typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
    }
    // 使用 typeHandler 获取参数值
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  /**
   * 获取嵌套查询的参数值(多个参数)
   *
   * @param rs
   * @param resultMapping
   * @param parameterType
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    // 初始化嵌套查询参数对象
    final Object parameterObject = instantiateParameterObject(parameterType);
    // 创建嵌套查询对象对应的 MetaObject 对象
    final MetaObject metaObject = configuration.newMetaObject(parameterObject);
    // 标识是否获取到属性值
    boolean foundValues = false;

    // 遍历传递的参数
    for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
      // 获取参数的值
      final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
      final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
      final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));

      // 将参数值设置到嵌套查询参数对象中
      // issue #353 & #560 do not execute nested query if key is null
      if (propValue != null) {
        metaObject.setValue(innerResultMapping.getProperty(), propValue);
        // 修改 foundValues 标识
        foundValues = true;
      }
    }
    // 如果没有找到参数值返回 null
    return foundValues ? parameterObject : null;
  }

  /**
   * 初始化嵌套查询参数对象
   *
   * @param parameterType
   * @return
   */
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

  /**
   * 处理嵌套查询结果集
   *
   * @see #handleRowValuesForSimpleResultMap 处理简单结果集
   * @param rsw
   * @param resultMap
   * @param resultHandler
   * @param rowBounds
   * @param parentMapping
   * @throws SQLException
   */
  private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    // 结果上下文
    final DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    // 获取结果集
    ResultSet resultSet = rsw.getResultSet();

    // 1. 定位到指定行数 (有 RowBounds 的时候才会起作用)
    skipRows(resultSet, rowBounds);

    Object rowValue = previousRowValue;

    // 2. 检测已处理的行数是否超过 BowBounds.limit 以及 ResultSet 中是否还有可处理的参数
    while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      // 3. 根据该行记录以及 ResultMap.Discriminator, 决定映射使用的 ResultMap
      final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
      // 4. 为该行记录生成 CacheKey
      final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
      // 5. 根据 CacheKey 获取对象
      Object partialObject = nestedResultObjects.get(rowKey);
      // issue #577 && #542
      // 6. 检测 <select> 节点中的 resultOrdered 属性
      if (mappedStatement.isResultOrdered()) {
        if (partialObject == null && rowValue != null) {
          nestedResultObjects.clear();
          storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
      } else {
        // 7. 得到映射结果对象(处理完成后会将对象保存到 nestedResultObjects 属性中. key 是 rowKey)
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
        if (partialObject == null) {
          // 8. 保存结果集对象(只有首次才会保存)
          storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
      }
    }
    // 对 resultOrdered 属性为 true 时的特殊处理
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

  /**
   * 映射 ResultMap 中的嵌套列
   *
   * @param rsw
   * @param resultMap
   * @param metaObject
   * @param parentPrefix
   * @param parentRowKey
   * @param newObject
   * @return
   */
  private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
    // 标识是否获取到属性值
    boolean foundValues = false;

    // 遍历 ResultMap.propertyResultMappings集合 (记录了非 <constructor> 节点下的映射关系)
    for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
      // 获取嵌套 ResultMap.id
      final String nestedResultMapId = resultMapping.getNestedResultMapId();
      // 1. 判断 nestedResultMapId 与 resultSet
      if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
        try {
          // 获取列前缀
          final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
          // 2. 确定嵌套类型使用的 ResultMap 对象
          final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);

          // 3. 处理循环引用的情况
          if (resultMapping.getColumnPrefix() == null) {
            // try to fill circular reference only when columnPrefix
            // is not specified for the nested result map (issue #215)
            Object ancestorObject = ancestorObjects.get(nestedResultMapId);
            if (ancestorObject != null) {
              if (newObject) {
                // 从 ancestorObjects 中获取对象放入外层对象中
                linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
              }
              continue;
            }
          }
          // 4. 为嵌套对象创建 CacheKey
          final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
          // 将嵌套对象的 CacheKey 与外层对象的 CacheKey 拼接 (nestedResultObjects 在映射完一个结果集后才会清空, 为了保证后续的结果集不受影响)
          final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
          // 查找 nestedResultObjects 集合中是否存在相同的对象
          Object rowValue = nestedResultObjects.get(combinedKey);
          boolean knownValue = rowValue != null;

          // 5. 初始化外层对象中的 Collection 类型的属性
          instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory

          // 6. 根据 notNullColumns 属性检测结果集中的空值
          if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
            // 7. 获取嵌套结果对象 (递归)
            rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
            // `!knownValue` 条件标识表示当前嵌套对象已经完成映射
            if (rowValue != null && !knownValue) {
              // 8. 将步骤7得到的嵌套对象保存到外层对象的相应属性中
              linkObjects(metaObject, resultMapping, rowValue);
              // 修改 foundValues 标识
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

  /**
   * 获取列前缀
   *
   * 如果 ResultMapping 中指定了 columnPrefix 属性则和指定的前置拼接在一起
   *
   * @param parentPrefix
   * @param resultMapping
   * @return
   */
  private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
    final StringBuilder columnPrefixBuilder = new StringBuilder();
    if (parentPrefix != null) {
      columnPrefixBuilder.append(parentPrefix);
    }
    // ResultMapping.columnPrefix 不为空
    if (resultMapping.getColumnPrefix() != null) {
      columnPrefixBuilder.append(resultMapping.getColumnPrefix());
    }
    return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
  }

  /**
   * 根据 notNullColumns 属性检测结果集中的空值
   *
   * @param resultMapping
   * @param columnPrefix
   * @param rsw
   * @return
   * @throws SQLException
   */
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

  /**
   * 获取嵌套类型使用的 ResultMap
   *
   * @see #resolveDiscriminatedResultMap
   * @param rs
   * @param nestedResultMapId
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix) throws SQLException {
    // 获取嵌套使用的 ResultMap
    ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
    // 根据 ResultMap 中的 Discriminator 对象选择出正确的 ResultMap
    return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
  }

  //
  // UNIQUE RESULT KEY
  //

  /**
   * 创建 CacheKey
   *
   * 1. 尝试使用 ResultMap 下记录的<idArg>和<id>节点对应的 ResultMapping 对象
   *    由 ResultMapping 对应的列名以及其对应的值一起构造 CacheKey 对象(只限以映射的对象) {@link #createRowKeyForMappedProperties}
   * 2. 如果 ResultMap 中没有 <idArg>和<id>节点则获取非 <constructor> 节点下 ResultMapping 对象
   *    由 ResultMapping 对应的列名以及其对应的值一起构造 CacheKey 对象(只限以映射的对象) {@link #createRowKeyForMappedProperties}
   *
   * 3. 如果 ResultMap 不存在 <idArg>和<id>节点与非 <constructor>节点下的 ResultMapping 对象, 且 ResultMap.type 为 Map 类型
   *    由 ResultSet 中的所有列名和所有列的值一起构成 CacheKey 对象 {@link #createRowKeyForMap}
   * 4. 如果 ResultMap.type 不是 Map 类型, 由 ResultSet 中未映射的列名以及其对应的值一起构造 CacheKey 对象 {@link #createRowKeyForUnmappedProperties}
   *
   * @see #createRowKeyForMap
   * @see #createRowKeyForUnmappedProperties
   * @see #createRowKeyForMappedProperties
   *
   * @param resultMap
   * @param rsw
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
    // 创建 CacheKey 对象
    final CacheKey cacheKey = new CacheKey();
    // 将 ResultMap.id 作为 CacheKey 的一部分
    cacheKey.update(resultMap.getId());

    // 获取 ResultMapping 集合
    List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
    // ResultMapping 集合为空的情况
    if (resultMappings.isEmpty()) {
      // ResultMap.type 是 Map 类型
      if (Map.class.isAssignableFrom(resultMap.getType())) {
        // 由 ResultSet 中的所有列名和所有列的值一起构成 CacheKey 对象
        createRowKeyForMap(rsw, cacheKey);
      } else {
        // 由 ResultSet 中未映射的列名以及其对应的值一起构造 CacheKey 对象
        createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
      }
    } else {
      // 由 ResultMapping 集合中列名以及其对应的值一起构造 CacheKey 对象(只限以映射的对象)
      createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
    }
    // 如果没有找到任何的列和值, 则返回 NULL_CACHE_KEY 对象
    if (cacheKey.getUpdateCount() < 2) {
      return CacheKey.NULL_CACHE_KEY;
    }
    return cacheKey;
  }

  /**
   * 组合 CacheKey
   *
   * @param rowKey
   * @param parentRowKey
   * @return
   */
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

  /**
   * 获取 ResultMapping 集合
   *
   * @param resultMap
   * @return
   */
  private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
    // 获取 ResultMap.idResultMappings 集合中记录的<idArg>和<id>节点对应的 ResultMapping 对象
    List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
    if (resultMappings.isEmpty()) {
      // 获取 ResultMap.propertyResultMappings 集合中记录的非 <constructor> 节点下 ResultMapping 对象
      resultMappings = resultMap.getPropertyResultMappings();
    }
    return resultMappings;
  }

  /**
   * 由 ResultMapping 集合中列名以及其对应的值一起构造 CacheKey 对象 (只限以映射的对象)
   *
   * @param resultMap
   * @param rsw
   * @param cacheKey
   * @param resultMappings
   * @param columnPrefix
   * @throws SQLException
   */
  private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
    // 遍历 ResultMapping 集合
    for (ResultMapping resultMapping : resultMappings) {
      // 忽略嵌套类型
      if (resultMapping.isSimple()) {
        // 处理列前缀
        final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
        // 获取列对应的 TypeHandler 对象
        final TypeHandler<?> th = resultMapping.getTypeHandler();
        // 获取以映射的列名
        List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);

        // Issue #114
        // 判断 ResultMapping 是否为以映射
        if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
          // 从 TypeHandler 中获取列的值
          final Object value = th.getResult(rsw.getResultSet(), column);
          if (value != null || configuration.isReturnInstanceForEmptyRow()) {
            // 将列名与值添加到 CacheKey 对象中
            cacheKey.update(column);
            cacheKey.update(value);
          }
        }
      }
    }
  }

  /**
   * 由 ResultSet 中未映射的列名以及其对应的值一起构造 CacheKey 对象
   *
   * @param resultMap
   * @param rsw
   * @param cacheKey
   * @param columnPrefix
   * @throws SQLException
   */
  private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, String columnPrefix) throws SQLException {
    // 创建 ResultMap.type 的 MetaClass 对象
    final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
    // 从 ResultSetWrapper 中获取未映射的列名
    List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);

    // 遍历未映射的列名
    for (String column : unmappedColumnNames) {
      // 处理前缀
      String property = column;
      if (columnPrefix != null && !columnPrefix.isEmpty()) {
        // When columnPrefix is specified, ignore columns without the prefix.
        if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
          property = column.substring(columnPrefix.length());
        } else {
          continue;
        }
      }
      // 查询 ResultMap.type 中是否包含属性
      if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
        // 从 ResultSet 中获取列的值
        String value = rsw.getResultSet().getString(column);
        // 将列名与值添加到 CacheKey 对象中
        if (value != null) {
          cacheKey.update(column);
          cacheKey.update(value);
        }
      }
    }
  }

  /**
   * 由 ResultSet 中的所有列名和所有列的值一起构成 CacheKey 对象
   *
   * @param rsw
   * @param cacheKey
   * @throws SQLException
   */
  private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
    // 获取 ResultSetWrapper 中所有的列名
    List<String> columnNames = rsw.getColumnNames();

    // 遍历所有的列名
    for (String columnName : columnNames) {
      // 从 ResultSet 中获取列的值
      final String value = rsw.getResultSet().getString(columnName);

      // 将列名与值添加到 CacheKey 对象中
      if (value != null) {
        cacheKey.update(columnName);
        cacheKey.update(value);
      }
    }
  }

  /**
   * 将嵌套结果对象设置到外层对象中
   *
   * @param metaObject
   * @param resultMapping
   * @param rowValue
   */
  private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
    // 初始化外层对象中的 Collection 类型的属性
    final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
    if (collectionProperty != null) {
      // <collection>
      final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
      targetMetaObject.add(rowValue);
    } else {
      // <association>
      metaObject.setValue(resultMapping.getProperty(), rowValue);
    }
  }

  /**
   * 初始化集合属性
   *
   * @param resultMapping
   * @param metaObject
   * @return
   */
  private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
    // 获取属性值
    final String propertyName = resultMapping.getProperty();
    Object propertyValue = metaObject.getValue(propertyName);

    if (propertyValue == null) {
      // 获取属性类型
      Class<?> type = resultMapping.getJavaType();
      if (type == null) {
        type = metaObject.getSetterType(propertyName);
      }
      try {
        // 属性类型是集合类型, 对属性进行赋值
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
