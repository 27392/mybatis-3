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
package org.apache.ibatis.executor.loader;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.ResultExtractor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionFactory;

/**
 * ResultLoader
 *
 * 保存了延迟加载操作所需的全部信息
 *
 * @author Clinton Begin
 */
public class ResultLoader {

  // Configuration 对象
  protected final Configuration configuration;
  // Executor 对象
  protected final Executor executor;
  // MappedStatement 对象
  protected final MappedStatement mappedStatement;
  // 参数
  protected final Object parameterObject;
  // 结果类型
  protected final Class<?> targetType;
  // ObjectFactory 对象
  protected final ObjectFactory objectFactory;
  // CacheKey 对象
  protected final CacheKey cacheKey;
  // BoundSql 对象
  protected final BoundSql boundSql;
  // ResultExtractor 对象 (负责将加载后的结果转换成 targetType 类型)
  protected final ResultExtractor resultExtractor;
  // 创建对象时的线程id
  protected final long creatorThreadId;
  // 暂时没有用上
  protected boolean loaded;
  // 结果对象
  protected Object resultObject;

  public ResultLoader(Configuration config, Executor executor, MappedStatement mappedStatement, Object parameterObject, Class<?> targetType, CacheKey cacheKey, BoundSql boundSql) {
    this.configuration = config;
    this.executor = executor;
    this.mappedStatement = mappedStatement;
    this.parameterObject = parameterObject;
    this.targetType = targetType;
    this.objectFactory = configuration.getObjectFactory();
    this.cacheKey = cacheKey;
    this.boundSql = boundSql;
    this.resultExtractor = new ResultExtractor(configuration, objectFactory);
    this.creatorThreadId = Thread.currentThread().getId();
  }

  /**
   * 加载结果
   */
  public Object loadResult() throws SQLException {
    // 查询结果
    List<Object> list = selectList();
    // 将结果转换成 targetType 类型
    resultObject = resultExtractor.extractObjectFromList(list, targetType);
    return resultObject;
  }

  /**
   * 查询结果
   *
   * @param <E>
   * @return
   * @throws SQLException
   */
  private <E> List<E> selectList() throws SQLException {
    Executor localExecutor = executor;
    // 判断创建对象的与现在调用的线程是否是同一个或者创建对象时的执行器已经关闭
    if (Thread.currentThread().getId() != this.creatorThreadId || localExecutor.isClosed()) {
      // 重新创建一个执行器
      localExecutor = newExecutor();
    }
    try {
      // 调用 Executor 查询结果
      return localExecutor.query(mappedStatement, parameterObject, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER, cacheKey, boundSql);
    } finally {
      // 如果是新创建的执行器则需要手动关闭
      if (localExecutor != executor) {
        localExecutor.close(false);
      }
    }
  }

  /**
   * 创建 Executor 对象
   * @return
   */
  private Executor newExecutor() {
    final Environment environment = configuration.getEnvironment();
    if (environment == null) {
      throw new ExecutorException("ResultLoader could not load lazily.  Environment was not configured.");
    }
    final DataSource ds = environment.getDataSource();
    if (ds == null) {
      throw new ExecutorException("ResultLoader could not load lazily.  DataSource was not configured.");
    }
    final TransactionFactory transactionFactory = environment.getTransactionFactory();
    final Transaction tx = transactionFactory.newTransaction(ds, null, false);
    return configuration.newExecutor(tx, ExecutorType.SIMPLE);
  }

  /**
   * 结果是否为空
   *
   * @return
   */
  public boolean wasNull() {
    return resultObject == null;
  }

}
