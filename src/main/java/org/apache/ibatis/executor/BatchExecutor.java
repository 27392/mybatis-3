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

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * 批处理执行器
 * 该类是针对JDBC批处理进行封装. 依赖于 {@link Statement#executeBatch()}{@link Statement#addBatch(String)}
 *
 * JDBC 批处理只支持 insert 、update 、delete 等类型的SQL语句, 不支持select 类型的sql语句

 * 注意{@link #doQuery}与{@link #doQueryCursor}方法在执行前会先执行缓存的SQL语句 {@link #doFlushStatements(boolean)}方法负责执行
 *
 * @author Jeff Butler
 */
public class BatchExecutor extends BaseExecutor {

  // 默认的行数
  public static final int BATCH_UPDATE_RETURN_VALUE = Integer.MIN_VALUE + 1002;

  // 缓存的 Statement 集合
  private final List<Statement> statementList = new ArrayList<>();
  // 缓存的 BatchResult 集合
  private final List<BatchResult> batchResultList = new ArrayList<>();

  // 上一次的SQL语句
  private String currentSql;
  // 上一的 MappedStatement 对象
  private MappedStatement currentStatement;

  public BatchExecutor(Configuration configuration, Transaction transaction) {
    super(configuration, transaction);
  }

  @Override
  public int doUpdate(MappedStatement ms, Object parameterObject) throws SQLException {
    // 获取配置对象
    final Configuration configuration = ms.getConfiguration();
    // 创建 StatementHandler 对象
    final StatementHandler handler = configuration.newStatementHandler(this, ms, parameterObject, RowBounds.DEFAULT, null, null);

    // 获取 BoundSql
    final BoundSql boundSql = handler.getBoundSql();
    // 从 BoundSql 对象中获取SQL语句
    final String sql = boundSql.getSql();

    final Statement stmt;
    // 当前执行的SQL语句与上次执行的SQL语句相同且对应的 MappedStatement 对象相同
    if (sql.equals(currentSql) && ms.equals(currentStatement)) {
      // 获取上次的 Statement
      int last = statementList.size() - 1;
      stmt = statementList.get(last);

      // 设置事务超时时间
      applyTransactionTimeout(stmt);
      // 绑定SQL参数
      handler.parameterize(stmt);// fix Issues 322

      // 记录用户参数
      BatchResult batchResult = batchResultList.get(last);
      batchResult.addParameterObject(parameterObject);
    } else {
      // 获取配置对象
      Connection connection = getConnection(ms.getStatementLog());
      // 创建 Statement 对象
      stmt = handler.prepare(connection, transaction.getTimeout());
      // 绑定 SQL参数
      handler.parameterize(stmt);    // fix Issues 322

      // 更新 currentSql 与 currentStatement 字段
      currentSql = sql;
      currentStatement = ms;

      // 保存 Statement 与 BatchResult
      statementList.add(stmt);
      batchResultList.add(new BatchResult(ms, sql, parameterObject));
    }
    // 处理批处理 (底层其实是调用 Statement.addBatch())
    handler.batch(stmt);
    // 返回默认行数
    return BATCH_UPDATE_RETURN_VALUE;
  }

  @Override
  public <E> List<E> doQuery(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
      throws SQLException {
    Statement stmt = null;
    try {
      // 执行缓存的SQL语句
      flushStatements();

      Configuration configuration = ms.getConfiguration();
      StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameterObject, rowBounds, resultHandler, boundSql);
      Connection connection = getConnection(ms.getStatementLog());
      stmt = handler.prepare(connection, transaction.getTimeout());
      handler.parameterize(stmt);
      return handler.query(stmt, resultHandler);
    } finally {
      closeStatement(stmt);
    }
  }

  @Override
  protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql) throws SQLException {
    // 执行缓存的SQL语句
    flushStatements();

    Configuration configuration = ms.getConfiguration();
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
    Connection connection = getConnection(ms.getStatementLog());
    Statement stmt = handler.prepare(connection, transaction.getTimeout());
    handler.parameterize(stmt);
    Cursor<E> cursor = handler.queryCursor(stmt);
    stmt.closeOnCompletion();
    return cursor;
  }

  @Override
  public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
    try {
      List<BatchResult> results = new ArrayList<>();

      // isRollback 为 true 时不执行
      if (isRollback) {
        return Collections.emptyList();
      }
      // 遍历 Statement 集合
      for (int i = 0, n = statementList.size(); i < n; i++) {
        Statement stmt = statementList.get(i);
        applyTransactionTimeout(stmt);
        BatchResult batchResult = batchResultList.get(i);
        try {
          // 执行并记录受影响的行数
          batchResult.setUpdateCounts(stmt.executeBatch());
          MappedStatement ms = batchResult.getMappedStatement();

          // 处理 KeyGenerator
          List<Object> parameterObjects = batchResult.getParameterObjects();
          KeyGenerator keyGenerator = ms.getKeyGenerator();
          if (Jdbc3KeyGenerator.class.equals(keyGenerator.getClass())) {
            // Jdbc3KeyGenerator 类型处理, 将主键设置回用户参数中
            Jdbc3KeyGenerator jdbc3KeyGenerator = (Jdbc3KeyGenerator) keyGenerator;
            jdbc3KeyGenerator.processBatch(ms, stmt, parameterObjects);
          } else if (!NoKeyGenerator.class.equals(keyGenerator.getClass())) { //issue #141
            // 执行 KeyGenerator.processAfter 方法
            for (Object parameter : parameterObjects) {
              keyGenerator.processAfter(this, ms, stmt, parameter);
            }
          }
          // 关闭 Statement
          // Close statement to close cursor #1109
          closeStatement(stmt);
        } catch (BatchUpdateException e) {
          StringBuilder message = new StringBuilder();
          message.append(batchResult.getMappedStatement().getId())
              .append(" (batch index #")
              .append(i + 1)
              .append(")")
              .append(" failed.");
          if (i > 0) {
            message.append(" ")
                .append(i)
                .append(" prior sub executor(s) completed successfully, but will be rolled back.");
          }
          throw new BatchExecutorException(message.toString(), e, results, batchResult);
        }
        // 记录结果
        results.add(batchResult);
      }
      return results;
    } finally {
      // 关闭所有的 Statement
      for (Statement stmt : statementList) {
        closeStatement(stmt);
      }
      // 清除 currentSql、statementList 与 batchResultList 集合
      currentSql = null;
      statementList.clear();
      batchResultList.clear();
    }
  }

}
