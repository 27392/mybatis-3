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
package org.apache.ibatis.executor;

import static org.apache.ibatis.executor.ExecutionPlaceholder.EXECUTION_PLACEHOLDER;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementUtil;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.jdbc.ConnectionLogger;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * 抽象的 Executor. 提供了一级缓存功能, 主要对查询进行缓存
 *
 * 定义了四个抽象方法 {@link #doUpdate}、{@link #doFlushStatements}、{@link #doQuery}、{@link #doQueryCursor}
 *
 * @author Clinton Begin
 */
public abstract class BaseExecutor implements Executor {

  private static final Log log = LogFactory.getLog(BaseExecutor.class);

  // 事务对象
  protected Transaction transaction;

  protected Executor wrapper;

  // 延迟加载的队列
  protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads;
  // 一级缓存
  protected PerpetualCache localCache;
  // 存储过程输出参数缓存
  protected PerpetualCache localOutputParameterCache;

  // Configuration 对象
  protected Configuration configuration;

  // 查询层数(用于记录嵌套查询的层数)
  protected int queryStack;

  // 是否关闭
  private boolean closed;

  protected BaseExecutor(Configuration configuration, Transaction transaction) {
    this.transaction = transaction;
    this.deferredLoads = new ConcurrentLinkedQueue<>();
    this.localCache = new PerpetualCache("LocalCache");
    this.localOutputParameterCache = new PerpetualCache("LocalOutputParameterCache");
    this.closed = false;
    this.configuration = configuration;
    this.wrapper = this;
  }

  @Override
  public Transaction getTransaction() {
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    return transaction;
  }

  @Override
  public void close(boolean forceRollback) {
    try {
      try {
        rollback(forceRollback);
      } finally {
        if (transaction != null) {
          transaction.close();
        }
      }
    } catch (SQLException e) {
      // Ignore. There's nothing that can be done at this point.
      log.warn("Unexpected exception on closing transaction.  Cause: " + e);
    } finally {
      transaction = null;
      deferredLoads = null;
      localCache = null;
      localOutputParameterCache = null;
      closed = true;
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public int update(MappedStatement ms, Object parameter) throws SQLException {
    ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    // 清空一级缓存
    clearLocalCache();
    return doUpdate(ms, parameter);
  }

  @Override
  public List<BatchResult> flushStatements() throws SQLException {
    return flushStatements(false);
  }

  /**
   * 执行缓存的 SQL语句
   *
   * @see BatchExecutor#doFlushStatements(boolean)
   * @param isRollBack  是否执行
   * @return
   * @throws SQLException
   */
  public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    return doFlushStatements(isRollBack);
  }

  @Override
  public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
    // 获取 BoundSql
    BoundSql boundSql = ms.getBoundSql(parameter);
    // 创建 CacheKey 对象(MappedStatement.id、RowBounds、sql语句、用户参数、环境等5个信息创建)
    CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
    // 调用重载方法
    return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
    ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
    // 检查是否关闭
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    // 非嵌套查询, 且 flushCache 属性为 true (为 true 的情况下，只要语句被调用，都会导致本地缓存和二级缓存被清空，(insert、update 和 delete 语句）默认值为 true)
    if (queryStack == 0 && ms.isFlushCacheRequired()) {
      // 清空一级缓存
      clearLocalCache();
    }
    List<E> list;
    try {
      // 查询层数增加
      queryStack++;
      // 查询一级缓存
      list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
      if (list != null) {
        // 处理存储过程输出参数
        handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
      } else {
        // 查询数据
        list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
      }
    } finally {
      // 查询完成,查询层数减少
      queryStack--;
    }
    // 当外层对象的查询结束时,所有的嵌套查询也完成了,对应的缓存也已经完全加载
    if (queryStack == 0) {
      // 加载所有的延迟加载
      for (DeferredLoad deferredLoad : deferredLoads) {
        deferredLoad.load();
      }
      // 清空 deferredLoads 队列
      // issue #601
      deferredLoads.clear();

      // 根据Configuration.localCacheScope 属性判断是否需要清空一级缓存 (默认是 SESSION).所以一般情况下是不会清空
      if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
        // 清空一级缓存
        // issue #482
        clearLocalCache();
      }
    }
    return list;
  }

  @Override
  public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
    BoundSql boundSql = ms.getBoundSql(parameter);
    // 不会使用一级缓存
    return doQueryCursor(ms, parameter, rowBounds, boundSql);
  }

  @Override
  public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    // 创建 DeferredLoad 对象
    DeferredLoad deferredLoad = new DeferredLoad(resultObject, property, key, localCache, configuration, targetType);

    // 是否能从一级缓存中加载
    if (deferredLoad.canLoad()) {
      // 从一级缓存中加载对象,并设置到外层对象
      deferredLoad.load();
    } else {
      // 不能从一级缓存中加载时, 将 DeferredLoad 对象添加到 deferredLoads 队列中, 待整个外层查询结束后在加载该对象
      deferredLoads.add(new DeferredLoad(resultObject, property, key, localCache, configuration, targetType));
    }
  }

  @Override
  public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    CacheKey cacheKey = new CacheKey();
    // MappedStatement.id
    cacheKey.update(ms.getId());
    // RowBounds 信息
    cacheKey.update(rowBounds.getOffset());
    cacheKey.update(rowBounds.getLimit());
    // sql 信息
    cacheKey.update(boundSql.getSql());

    // 用户参数信息
    List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    TypeHandlerRegistry typeHandlerRegistry = ms.getConfiguration().getTypeHandlerRegistry();
    // mimic DefaultParameterHandler logic
    for (ParameterMapping parameterMapping : parameterMappings) {
      // 过滤输出类型
      if (parameterMapping.getMode() != ParameterMode.OUT) {
        Object value;
        String propertyName = parameterMapping.getProperty();
        if (boundSql.hasAdditionalParameter(propertyName)) {
          value = boundSql.getAdditionalParameter(propertyName);
        } else if (parameterObject == null) {
          value = null;
        } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
          value = parameterObject;
        } else {
          MetaObject metaObject = configuration.newMetaObject(parameterObject);
          value = metaObject.getValue(propertyName);
        }
        cacheKey.update(value);
      }
    }
    // 环境信息
    if (configuration.getEnvironment() != null) {
      // issue #176
      cacheKey.update(configuration.getEnvironment().getId());
    }
    return cacheKey;
  }

  @Override
  public boolean isCached(MappedStatement ms, CacheKey key) {
    return localCache.getObject(key) != null;
  }

  @Override
  public void commit(boolean required) throws SQLException {
    if (closed) {
      throw new ExecutorException("Cannot commit, transaction is already closed");
    }
    // 清空一级缓存
    clearLocalCache();
    // 执行缓存的SQL语句
    flushStatements();

    // 根据 required 参数决定是否提交事务
    if (required) {
      transaction.commit();
    }
  }

  @Override
  public void rollback(boolean required) throws SQLException {
    if (!closed) {
      try {
        // 清空一级缓存
        clearLocalCache();
        // 执行缓存的SQL语句
        flushStatements(true);
      } finally {
        // 根据 required 参数决定是否回滚事务
        if (required) {
          transaction.rollback();
        }
      }
    }
  }

  @Override
  public void clearLocalCache() {
    if (!closed) {
      localCache.clear();
      localOutputParameterCache.clear();
    }
  }

  protected abstract int doUpdate(MappedStatement ms, Object parameter) throws SQLException;

  protected abstract List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException;

  protected abstract <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
      throws SQLException;

  protected abstract <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
      throws SQLException;

  protected void closeStatement(Statement statement) {
    if (statement != null) {
      try {
        statement.close();
      } catch (SQLException e) {
        // ignore
      }
    }
  }

  /**
   * Apply a transaction timeout.
   *
   * @param statement
   *          a current statement
   * @throws SQLException
   *           if a database access error occurs, this method is called on a closed <code>Statement</code>
   * @since 3.4.0
   * @see StatementUtil#applyTransactionTimeout(Statement, Integer, Integer)
   */
  protected void applyTransactionTimeout(Statement statement) throws SQLException {
    StatementUtil.applyTransactionTimeout(statement, statement.getQueryTimeout(), transaction.getTimeout());
  }

  /**
   * 处理存储过程输出参数 (将输出类型参数设置到用户参数中)
   *
   * @param ms
   * @param key
   * @param parameter
   * @param boundSql
   */
  private void handleLocallyCachedOutputParameters(MappedStatement ms, CacheKey key, Object parameter, BoundSql boundSql) {
    if (ms.getStatementType() == StatementType.CALLABLE) {
      final Object cachedParameter = localOutputParameterCache.getObject(key);
      if (cachedParameter != null && parameter != null) {
        final MetaObject metaCachedParameter = configuration.newMetaObject(cachedParameter);
        final MetaObject metaParameter = configuration.newMetaObject(parameter);
        for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
          if (parameterMapping.getMode() != ParameterMode.IN) {
            final String parameterName = parameterMapping.getProperty();
            final Object cachedValue = metaCachedParameter.getValue(parameterName);
            metaParameter.setValue(parameterName, cachedValue);
          }
        }
      }
    }
  }

  /**
   * 从数据库中查询
   *
   * @param ms
   * @param parameter
   * @param rowBounds
   * @param resultHandler
   * @param key
   * @param boundSql
   * @param <E>
   * @return
   * @throws SQLException
   */
  private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
    List<E> list;
    // 向一级缓存中添加占位符
    localCache.putObject(key, EXECUTION_PLACEHOLDER);
    try {
      // 查询数据库
      list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
    } finally {
      // 删除占位符
      localCache.removeObject(key);
    }
    // 将真正的结果放入一级缓存中
    localCache.putObject(key, list);

    // 存储过程的调用
    if (ms.getStatementType() == StatementType.CALLABLE) {
      localOutputParameterCache.putObject(key, parameter);
    }
    // 返回结果
    return list;
  }

  /**
   * 获取 Connection (数据库连接)
   *
   * @param statementLog
   * @return
   * @throws SQLException
   */
  protected Connection getConnection(Log statementLog) throws SQLException {
    Connection connection = transaction.getConnection();
    if (statementLog.isDebugEnabled()) {
      return ConnectionLogger.newInstance(connection, statementLog, queryStack);
    } else {
      return connection;
    }
  }

  @Override
  public void setExecutorWrapper(Executor wrapper) {
    this.wrapper = wrapper;
  }

  /**
   * 延迟加载
   */
  private static class DeferredLoad {

    // 结果对象的 MetaObject 对象
    private final MetaObject resultObject;
    // 属性
    private final String property;
    // 目标类型
    private final Class<?> targetType;
    // CacheKey
    private final CacheKey key;
    // 一级缓存
    private final PerpetualCache localCache;
    // ObjectFactory 对象
    private final ObjectFactory objectFactory;
    // 结果提取器
    private final ResultExtractor resultExtractor;

    // issue #781
    public DeferredLoad(MetaObject resultObject,
                        String property,
                        CacheKey key,
                        PerpetualCache localCache,
                        Configuration configuration,
                        Class<?> targetType) {
      this.resultObject = resultObject;
      this.property = property;
      this.key = key;
      this.localCache = localCache;
      this.objectFactory = configuration.getObjectFactory();
      this.resultExtractor = new ResultExtractor(configuration, objectFactory);
      this.targetType = targetType;
    }

    /**
     * 检查缓存是否能加载
     *
     * 在执行{@link #queryFromDatabase}前会向一级缓存中添加{@link ExecutionPlaceholder#EXECUTION_PLACEHOLDER} 占位符
     * 在拿到结果后才会向一级缓存中添加
     *
     * 所以一级缓存中存在并且值不是占位符的情况下才表示可以加载
     *
     * @return
     */
    public boolean canLoad() {
      return localCache.getObject(key) != null && localCache.getObject(key) != EXECUTION_PLACEHOLDER;
    }

    /**
     * 加载(从一级缓存中加载)
     */
    public void load() {
      // 从本地缓存中获取数据
      @SuppressWarnings("unchecked")
      // we suppose we get back a List
      List<Object> list = (List<Object>) localCache.getObject(key);

      // 将结果转换成 targetType 类型
      Object value = resultExtractor.extractObjectFromList(list, targetType);

      // 将值设置到外层对象的属性中
      resultObject.setValue(property, value);
    }

  }

}
