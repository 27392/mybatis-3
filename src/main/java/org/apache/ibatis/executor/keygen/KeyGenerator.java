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
package org.apache.ibatis.executor.keygen;

import java.sql.Statement;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;

/**
 * 主键生成器接口(除{@link SelectKeyGenerator}外全部使用饿汉单例模式)
 *
 * @see Jdbc3KeyGenerator   用于获取插入数据后的自增主键数值
 * @see SelectKeyGenerator  某些数据库不支持自增主键，需要手动填写主键字段
 * @see NoKeyGenerator      是一个空实现
 *
 * @author Clinton Begin
 */
public interface KeyGenerator {

  /**
   * 在插入前执行
   *
   * @see org.apache.ibatis.executor.statement.BaseStatementHandler#generateKeys(Object)
   * @param executor
   * @param ms
   * @param stmt
   * @param parameter
   */
  void processBefore(Executor executor, MappedStatement ms, Statement stmt, Object parameter);

  /**
   * 在插入后执行
   *
   * @param executor
   * @param ms
   * @param stmt
   * @param parameter
   */
  void processAfter(Executor executor, MappedStatement ms, Statement stmt, Object parameter);

}
