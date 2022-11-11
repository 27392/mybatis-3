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
package org.apache.ibatis.builder.xml;

import java.util.List;
import java.util.Locale;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

/**
 * XMLStatementBuilder
 *
 * 主要负责解析 SQL 语句
 *
 * @author Clinton Begin
 */
public class XMLStatementBuilder extends BaseBuilder {

  // MapperBuilderAssistant
  private final MapperBuilderAssistant builderAssistant;
  // 节点对象
  private final XNode context;
  // Configuration 中的 databaseId
  private final String requiredDatabaseId;

  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context) {
    this(configuration, builderAssistant, context, null);
  }

  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context, String databaseId) {
    super(configuration);
    this.builderAssistant = builderAssistant;
    this.context = context;
    this.requiredDatabaseId = databaseId;
  }

  /**
   * 解析 <select>、<insert>、<update>、<delete> 等 SQL语句节点
   *
   * 1. 解析 <include> 节点
   * 2. 解析 <selectKey> 节点
   * 3. 构建 SqlSource 对象
   * 4. 构建 MappedStatement 对象
   *
   * <mapper>
   *    <insert id="insertAuthor" parameterType="org.apache.ibatis.domain.blog.Author">
   *        insert into Author (id,username,password,email,bio) values (#{id},#{username},#{password},#{email},#{bio})
   *    </insert>
   *
   *    <select id="selectAllAuthors" resultType="org.apache.ibatis.domain.blog.Author">
   *        select * from author
   *    </select>
   *
   *    <update id="updateAuthor" parameterType="org.apache.ibatis.domain.blog.Author">
   *        update Author set username=#{username, javaType=String}, password=#{password}, email=#{email}, bio=#{bio} where id=#{id}
   *    </update>
   *
   *    <delete id="deleteAuthor" parameterType="int">
   *       delete from Author where id = #{id}
   *    </delete>
   * </mapper>
   * @see XMLMapperBuilder#buildStatementFromContext(List, String)
   */
  public void parseStatementNode() {
    // 获取 id 和 databaseId 属性
    String id = context.getStringAttribute("id");
    String databaseId = context.getStringAttribute("databaseId");

    // 匹配节点是否适用于当前使用的数据库. 使用 databaseId 属性与 Configuration 中的 databaseId 进行对比
    if (!databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
      return;
    }

    // 获取节点名称
    String nodeName = context.getNode().getNodeName();
    // 使用节点名称转换成 SqlCommandType 枚举对象
    SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));

    // 获取 flushCache、useCache、resultOrdered 等属性
    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
    boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);
    boolean useCache = context.getBooleanAttribute("useCache", isSelect);
    boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false);

    // 处理 <include>. 使用 XMLIncludeTransformer 对象
    // Include Fragments before parsing
    XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
    includeParser.applyIncludes(context.getNode());

    // 获取 parameterType 属性, 并解析对应的 Class 类型
    String parameterType = context.getStringAttribute("parameterType");
    Class<?> parameterTypeClass = resolveClass(parameterType);

    // 获取 lang 属性, 并解析对应的 LanguageDriver 对象
    String lang = context.getStringAttribute("lang");
    LanguageDriver langDriver = getLanguageDriver(lang);

    // 解析 <selectKey>; 解析后的 KeyGenerator 对象会被添加到 Configuration.keyGenerators 中
    // Parse selectKey after includes and remove them.
    processSelectKeyNodes(id, parameterTypeClass, langDriver);

    // 获取通过 <selectKey> 解析后的 KeyGenerator 对象
    // Parse the SQL (pre: <selectKey> and <include> were parsed and removed)
    KeyGenerator keyGenerator;
    String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);

    // 存在则表示存在 <selectKey>, 且类型为 SelectKeyGenerator
    if (configuration.hasKeyGenerator(keyStatementId)) {
      keyGenerator = configuration.getKeyGenerator(keyStatementId);
    } else {
      // 获取 useGeneratedKeys 属性决定是否使用 Jdbc3KeyGenerator 还是 NoKeyGenerator
      keyGenerator = context.getBooleanAttribute("useGeneratedKeys",
          configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
          ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
    }

    // 创建 SqlSource 对象(通过 SqlSource 可以创建 BoundSql, BoundSql中包含了SQL语句信息). 使用语言驱动
    SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);

    // 获取 statementType, fetchSize, timeout 等属性信息
    StatementType statementType = StatementType.valueOf(context.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    Integer fetchSize = context.getIntAttribute("fetchSize");
    Integer timeout = context.getIntAttribute("timeout");
    String parameterMap = context.getStringAttribute("parameterMap");
    String resultType = context.getStringAttribute("resultType");
    Class<?> resultTypeClass = resolveClass(resultType);
    String resultMap = context.getStringAttribute("resultMap");
    String resultSetType = context.getStringAttribute("resultSetType");
    ResultSetType resultSetTypeEnum = resolveResultSetType(resultSetType);
    if (resultSetTypeEnum == null) {
      resultSetTypeEnum = configuration.getDefaultResultSetType();
    }
    String keyProperty = context.getStringAttribute("keyProperty");
    String keyColumn = context.getStringAttribute("keyColumn");
    String resultSets = context.getStringAttribute("resultSets");

    // 创建 MappedStatement 对象. 并将其保存在 Configuration 的 `Map<String, MappedStatement> mappedStatements` 属性中(key: namespace.id)
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered,
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
  }

  /**
   * 解析当前节点内的多个 <selectKey> 节点
   *
   * 具体的流程:
   *  1. 过滤可用的 <selectKey> 节点
   *  2. 创建并缓存 MappedStatement 对象
   *  3. 创建并缓存 SelectKeyGenerator 对象
   *  4. 删除节点内所有的 <selectKey> 节点
   *
   * @param id
   * @param parameterTypeClass
   * @param langDriver
   */
  private void processSelectKeyNodes(String id, Class<?> parameterTypeClass, LanguageDriver langDriver) {
    // 获取节点内的 selectKey 节点
    List<XNode> selectKeyNodes = context.evalNodes("selectKey");

    // 是否配置 databaseId
    if (configuration.getDatabaseId() != null) {
      // 调用重载方法(使用 databaseId)
      parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, configuration.getDatabaseId());
    }
    // 调用重载方法
    parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);

    // 删除当前节点内的多个 <selectKey> 节点
    removeSelectKeyNodes(selectKeyNodes);
  }

  /**
   * 解析当前节点内的多个 <selectKey> 节点
   *
   * @param parentId
   * @param list
   * @param parameterTypeClass
   * @param langDriver
   * @param skRequiredDatabaseId
   */
  private void parseSelectKeyNodes(String parentId, List<XNode> list, Class<?> parameterTypeClass, LanguageDriver langDriver, String skRequiredDatabaseId) {
    for (XNode nodeToHandle : list) {
      // id = `SQL语句节点的id + !selectKey`
      String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
      // 获取 databaseId 属性
      String databaseId = nodeToHandle.getStringAttribute("databaseId");

      // 匹配节点是否适用于当前使用的数据库
      if (databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
        // 解析 <selectKey> 节点
        parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
      }
    }
  }

  /**
   * 解析 <selectKey> 节点
   *
   * 最后将解析后的 KeyGenerator 对象添加到 Configuration.keyGenerators 中
   *
   * <insert id="insertAuthor">
   *   <selectKey keyProperty="id" resultType="int" order="BEFORE">
   *     select CAST(RANDOM()*1000000 as INTEGER) a from SYSIBM.SYSDUMMY1
   *   </selectKey>
   *   insert into Author (id, username, password, email) values (#{id}, #{username}, #{password}, #{email})
   * </insert>
   *
   * @param id
   * @param nodeToHandle
   * @param parameterTypeClass
   * @param langDriver
   * @param databaseId
   */
  private void parseSelectKeyNode(String id, XNode nodeToHandle, Class<?> parameterTypeClass, LanguageDriver langDriver, String databaseId) {
    // 获取 resultType 属性并将其解析成 Class 对象
    String resultType = nodeToHandle.getStringAttribute("resultType");
    Class<?> resultTypeClass = resolveClass(resultType);

    // 获取 statementType, keyProperty, keyColumn 等属性
    StatementType statementType = StatementType.valueOf(nodeToHandle.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    String keyProperty = nodeToHandle.getStringAttribute("keyProperty");
    String keyColumn = nodeToHandle.getStringAttribute("keyColumn");

    // 获取 order 属性. 判断是否在插入之前执行
    boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER"));

    // 以下是默认的一些配置
    // defaults
    boolean useCache = false;
    boolean resultOrdered = false;
    KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
    Integer fetchSize = null;
    Integer timeout = null;
    boolean flushCache = false;
    String parameterMap = null;
    String resultMap = null;
    ResultSetType resultSetTypeEnum = null;

    // 创建 SqlSource
    SqlSource sqlSource = langDriver.createSqlSource(configuration, nodeToHandle, parameterTypeClass);
    // <selectKey> 只能使用 select 语句
    SqlCommandType sqlCommandType = SqlCommandType.SELECT;

    // 创建 MappedStatement 对象. 并将其保存在 Configuration 的 `Map<String, MappedStatement> mappedStatements` 属性中(key: namespace.id)
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered,
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);

    // id = namespace.id
    id = builderAssistant.applyCurrentNamespace(id, false);

    // 获取创建 MappedStatement 对象
    MappedStatement keyStatement = configuration.getMappedStatement(id, false);
    // 创建 SelectKeyGenerator 对象, 将其保存到 Configuration 中的 `Map<String, KeyGenerator> keyGenerators` 字段中(key: namespace.id + !selectKey, value: SelectKeyGenerator)
    configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));
  }

  /**
   * 删除 <selectKey> 节点
   *
   * @param selectKeyNodes
   */
  private void removeSelectKeyNodes(List<XNode> selectKeyNodes) {
    for (XNode nodeToHandle : selectKeyNodes) {
      nodeToHandle.getParent().getNode().removeChild(nodeToHandle.getNode());
    }
  }

  /**
   * 匹配节点是否适用于当前使用的数据库
   *
   * @param id                  SQL语句节点中的 id 属性
   * @param databaseId          SQL语句节点中的 databaseId 属性
   * @param requiredDatabaseId  Configuration 中的 databaseId
   * @return
   */
  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    if (requiredDatabaseId != null) {
      return requiredDatabaseId.equals(databaseId);
    }
    if (databaseId != null) {
      return false;
    }
    id = builderAssistant.applyCurrentNamespace(id, false);
    if (!this.configuration.hasStatement(id, false)) {
      return true;
    }
    // skip this statement if there is a previous one with a not null databaseId
    MappedStatement previous = this.configuration.getMappedStatement(id, false); // issue #2
    return previous.getDatabaseId() == null;
  }

  /**
   * 获取语言驱动
   *
   * @param lang
   * @return
   */
  private LanguageDriver getLanguageDriver(String lang) {
    Class<? extends LanguageDriver> langClass = null;
    if (lang != null) {
      langClass = resolveClass(lang);
    }
    return configuration.getLanguageDriver(langClass);
  }

}
