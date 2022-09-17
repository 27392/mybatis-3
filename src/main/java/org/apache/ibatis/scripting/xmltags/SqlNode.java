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
package org.apache.ibatis.scripting.xmltags;

/**
 * SQL节点接口 (在组合模式中的表示的是`抽象组件`角色)
 *
 * 一条sql语句由多个sql节点组成
 *
 * @see MixedSqlNode        包含多个SQL节点 (在组合模式中的表示的是`树枝`角色)
 *
 * 以下类在组合模式中的表示的是`树叶`角色
 * @see StaticTextSqlNode   表示非动态SQL语句节点
 * @see TextSqlNode         表示包含`${}`占位符的动态SQL节点
 * @see ChooseSqlNode       表示的是<choose>节点
 * @see ForEachSqlNode      表示的是<foreach>节点
 * @see IfSqlNode           表示的是<if>节点
 * @see VarDeclSqlNode      表示的是<bind>节点
 * @see TrimSqlNode         表示的是<trim>节点
 * @see WhereSqlNode        表示的是<where>节点,继承与 {@link TrimSqlNode}
 * @see SetSqlNode          表示的是<set>节点,继承与 {@link TrimSqlNode}
 * @author Clinton Begin
 */
public interface SqlNode {

  /**
   * 根据 sql 语句上下文拼接sql.
   *
   * @param context
   * @return
   */
  boolean apply(DynamicContext context);
}
