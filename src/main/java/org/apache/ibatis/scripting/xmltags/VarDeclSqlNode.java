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
 * 对应 <bind> 节点
 *
 * <select id="selectBlogsLike" resultType="Blog">
 *   <bind name="pattern" value="'%' + _parameter.getTitle() + '%'" />
 *   SELECT * FROM BLOG WHERE title LIKE #{pattern}
 * </select>
 *
 * @author Frank D. Martinez [mnesarco]
 * @link {https://mybatis.org/mybatis-3/zh/dynamic-sql.html#bind}
 */
public class VarDeclSqlNode implements SqlNode {

  // name 属性
  private final String name;
  // 表达式
  private final String expression;

  public VarDeclSqlNode(String var, String exp) {
    name = var;
    expression = exp;
  }

  @Override
  public boolean apply(DynamicContext context) {
    // 解析表达式的值
    final Object value = OgnlCache.getValue(expression, context.getBindings());
    // 将 name 与 表达式的值添加到 DynamicContext.bindings 中
    context.bind(name, value);
    return true;
  }

}
