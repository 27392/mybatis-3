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
package org.apache.ibatis.mapping;

/**
 * SqlSource 接口
 *
 * Represents the content of a mapped statement read from an XML file or an annotation.
 * It creates the SQL that will be passed to the database out of the input parameter received from the user.
 *
 * @see org.apache.ibatis.scripting.xmltags.DynamicSqlSource  负责处理动态SQL(包含动态SQL节点与`${}`占位符)
 * @see org.apache.ibatis.scripting.defaults.RawSqlSource     负责处理静态SQL
 *
 * @see org.apache.ibatis.builder.StaticSqlSource             负责处理创建 BoundSql. DynamicSqlSource与RawSqlSource最后都会使用该类
 * @author Clinton Begin
 */
public interface SqlSource {

  /**
   * 获取 BoundSql 对象
   *
   * @param parameterObject
   * @return
   */
  BoundSql getBoundSql(Object parameterObject);

}
