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

import java.util.List;

/**
 * 对应 <choose> 节点
 *
 * <select id="findActiveBlogLike" resultType="Blog">
 *   SELECT * FROM BLOG WHERE state = ‘ACTIVE’
 *   <choose>
 *     <when test="title != null">
 *       AND title like #{title}
 *     </when>
 *     <when test="author != null and author.name != null">
 *       AND author_name like #{author.name}
 *     </when>
 *     <otherwise>
 *       AND featured = 1
 *     </otherwise>
 *   </choose>
 * </select>
 *
 * @link {https://mybatis.org/mybatis-3/zh/dynamic-sql.html#choose%E3%80%81when%E3%80%81otherwise}
 *
 * @author Clinton Begin
 */
public class ChooseSqlNode implements SqlNode {

  // otherwise 对应的 SQL 节点
  private final SqlNode defaultSqlNode;
  // <when> 对应的 SQL 节点
  private final List<SqlNode> ifSqlNodes;

  public ChooseSqlNode(List<SqlNode> ifSqlNodes, SqlNode defaultSqlNode) {
    this.ifSqlNodes = ifSqlNodes;
    this.defaultSqlNode = defaultSqlNode;
  }

  @Override
  public boolean apply(DynamicContext context) {
    for (SqlNode sqlNode : ifSqlNodes) {
      if (sqlNode.apply(context)) {
        return true;
      }
    }
    if (defaultSqlNode != null) {
      defaultSqlNode.apply(context);
      return true;
    }
    return false;
  }
}
