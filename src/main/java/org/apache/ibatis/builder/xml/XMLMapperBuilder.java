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

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * XMLMapperBuilder 是 BaseBuilder 的子类
 *
 * 主要负责解析 Mapper.xml 映射配置文件
 *
 * @link {https://mybatis.org/mybatis-3/zh/sqlmap-xml.html}
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLMapperBuilder extends BaseBuilder {

  // XPathParser 对象
  private final XPathParser parser;
  // MapperBuilderAssistant 对象 (负责映射文件中所有元素对应类的构建;包含 Cache、ResultMap等)
  private final MapperBuilderAssistant builderAssistant;
  // TODO
  private final Map<String, XNode> sqlFragments;
  // 文件资源 (url 或 resource 属性的值)
  private final String resource;

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(reader, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(inputStream, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  /**
   * 核心的构造函数 (其他的构造函数最终都会调用这个)
   *
   * @param parser
   * @param configuration
   * @param resource
   * @param sqlFragments
   */
  private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    super(configuration);
    // 创建 MapperBuilderAssistant 对象
    this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
    this.parser = parser;
    this.sqlFragments = sqlFragments;
    this.resource = resource;
  }

  /**
   * 解析 <mapper> 节点
   */
  public void parse() {
    // 判断是否加载过该映射文件
    if (!configuration.isResourceLoaded(resource)) {
      // 解析 <mapper> 节点
      configurationElement(parser.evalNode("/mapper"));
      // 将资源保存到 Configuration 中, 以防重复加载
      configuration.addLoadedResource(resource);
      // 注册 Mapper 接口
      bindMapperForNamespace();
    }

    // 处理解析失败的集合
    parsePendingResultMaps();
    parsePendingCacheRefs();
    parsePendingStatements();
  }

  public XNode getSqlFragment(String refid) {
    return sqlFragments.get(refid);
  }

  /**
   * 解析 <mapper> 节点
   *
   * <mapper namespace="org.apache.ibatis.domain.blog.mappers.AuthorMapper">
   *
   *    <cache-ref namespace="org.apache.ibatis.submitted.resolution.cachereffromxml.UserMapper" />
   *
   *    <cache eviction="FIFO" flushInterval="60000" size="512" readOnly="true"/>
   *
   *    <sql id="BaseColumn">
   *        id,username,password,email,bio
   *    </sql>
   *
   *    <resultMap id="selectAuthor" type="org.apache.ibatis.domain.blog.Author">
   *        <id column="id" property="id" />
   *        <result property="username" column="username" />
   *        <result property="password" column="password" />
   *        <result property="email" column="email" />
   *        <result property="bio" column="bio" />
   *        <result property="favouriteSection" column="favourite_section" />
   *    </resultMap>
   *
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
   * @param context
   */
  private void configurationElement(XNode context) {
    try {
      // 获取 namespace 属性
      String namespace = context.getStringAttribute("namespace");
      // namespace 属性为空抛出异常
      if (namespace == null || namespace.isEmpty()) {
        throw new BuilderException("Mapper's namespace cannot be empty");
      }

      // 记录 namespace 属性到 MapperBuilderAssistant 中
      builderAssistant.setCurrentNamespace(namespace);

      // 解析 <cache-ref> 节点
      cacheRefElement(context.evalNode("cache-ref"));
      // 解析 <cache> 节点
      cacheElement(context.evalNode("cache"));
      // 解析 <parameterMap> 节点 .(该节点已经废弃)
      parameterMapElement(context.evalNodes("/mapper/parameterMap"));
      // 解析 <resultMap> 节点
      resultMapElements(context.evalNodes("/mapper/resultMap"));
      // 解析 <sql> 节点
      sqlElement(context.evalNodes("/mapper/sql"));
      // 解析 <select>、<insert>、<update>、<delete> 等节点
      buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
    }
  }

  /**
   * 解析 <select>、<insert>、<update>、<delete> 等节点
   *
   * @param list
   */
  private void buildStatementFromContext(List<XNode> list) {
    // 是否配置 databaseId
    if (configuration.getDatabaseId() != null) {
      buildStatementFromContext(list, configuration.getDatabaseId());
    }
    buildStatementFromContext(list, null);
  }

  /**
   * 解析 <select>、<insert>、<update>、<delete> 等节点
   *
   * @param list
   * @param requiredDatabaseId
   */
  private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
      // 创建 XMLStatementBuilder 对象
      final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
      try {
        // 解析
        statementParser.parseStatementNode();
      } catch (IncompleteElementException e) {
        // 解析失败将 XMLStatementBuilder 对象保存到 Configuration 中
        configuration.addIncompleteStatement(statementParser);
      }
    }
  }

  private void parsePendingResultMaps() {
    Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
    synchronized (incompleteResultMaps) {
      Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // ResultMap is still missing a resource...
        }
      }
    }
  }

  private void parsePendingCacheRefs() {
    Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
    synchronized (incompleteCacheRefs) {
      Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolveCacheRef();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Cache ref is still missing a resource...
        }
      }
    }
  }

  private void parsePendingStatements() {
    Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
    synchronized (incompleteStatements) {
      Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().parseStatementNode();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Statement is still missing a resource...
        }
      }
    }
  }

  /**
   * 解析 <cache-ref> 节点
   *
   * <cache-ref namespace="org.apache.ibatis.submitted.resolution.cachereffromxml.UserMapper" />
   *
   * @link {https://mybatis.org/mybatis-3/zh/sqlmap-xml.html#cache-ref}
   * @param context
   */
  private void cacheRefElement(XNode context) {
    if (context != null) {
      // 获取当前的 namespace 属性与<cache-ref> 中的 namespace 属性.
      // 将其对应的关系保存到 Configuration 中的 `Map<String, String> cacheRefMap` 属性中, key: 当前 namespace, value: 引用的 namespace
      configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));

      // 创建 CacheRefResolver 对象
      CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
      try {
        // 获取关联的缓存, 并将其设置到当前的缓存中
        cacheRefResolver.resolveCacheRef();
      } catch (IncompleteElementException e) {
        // 被引用的缓存不存在, 将 CacheRefResolver 对象保存到 Configuration 中
        configuration.addIncompleteCacheRef(cacheRefResolver);
      }
    }
  }

  /**
   * 解析 <cache> 节点
   *
   *
   *  <cache type="org.apache.ibatis.submitted.global_variables.CustomCache">
   *     <property name="stringValue" value="${stringProperty}"/>
   *     <property name="integerValue" value="${integerProperty}"/>
   *     <property name="longValue" value="${longProperty}"/>
   *  </cache>
   *
   *   这个更高级的配置创建了一个 FIFO 缓存，每隔 60 秒刷新，最多可以存储结果对象或列表的 512 个引用，而且返回的对象被认为是只读的，因此对它们进行修改可能会在不同线程中的调用者产生冲突。
   *   <cache eviction="FIFO" flushInterval="60000" size="512" readOnly="true"/>
   *
   * 缓存清除策略有以下几种:
   *    LRU – 最近最少使用：移除最长时间不被使用的对象。             {@link org.apache.ibatis.cache.decorators.LruCache}
   *    FIFO – 先进先出：按对象进入缓存的顺序来移除它们。            {@link org.apache.ibatis.cache.decorators.FifoCache}
   *    SOFT – 软引用：基于垃圾回收器状态和软引用规则移除对象。       {@link org.apache.ibatis.cache.decorators.SoftCache}
   *    WEAK – 弱引用：更积极地基于垃圾收集器状态和弱引用规则移除对象。{@link org.apache.ibatis.cache.decorators.WeakCache}
   *
   * 属性的描述:
   *  flushInterval（刷新间隔）属性可以被设置为任意的正整数，
   *    设置的值应该是一个以毫秒为单位的合理时间量。 默认情况是不设置，也就是没有刷新间隔，缓存仅仅会在调用语句时刷新。
   *  size（引用数目）属性可以被设置为任意正整数，
   *    要注意欲缓存对象的大小和运行环境中可用的内存资源。默认值是 1024。
   *  readOnly（只读）属性可以被设置为 true 或 false。
   *    只读的缓存会给所有调用者返回缓存对象的相同实例。 因此这些对象不能被修改。这就提供了可观的性能提升。而可读写的缓存会（通过序列化）返回缓存对象的拷贝。
   *    速度上会慢一些，但是更安全，因此默认值是 false。
   * @link {https://mybatis.org/mybatis-3/zh/sqlmap-xml.html#%E7%BC%93%E5%AD%98}
   * @param context
   */
  private void cacheElement(XNode context) {
    if (context != null) {
      // 获取 type 属性 (缓存类型), 默认值是 PERPETUAL
      String type = context.getStringAttribute("type", "PERPETUAL");
      // 解析 type 属性的 Class 对象. 通过别名注册器解析
      Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
      // 获取 eviction 属性 (缓存清除策略), 默认值是 LRU. 还包含(FIFO, SOFT, WEAK)
      String eviction = context.getStringAttribute("eviction", "LRU");
      // 解析 eviction 属性的 Class 对象. 通过别名注册器解析
      Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);

      // 获取 flushInterval 属性 (缓存属性间隔, 如果设置了则使用 ScheduledCache 进行装饰).
      Long flushInterval = context.getLongAttribute("flushInterval");
      // 获取 size 属性 (缓存大小)
      Integer size = context.getIntAttribute("size");
      // 获取 readOnly 属性 (缓存是否只读, 如果为 true 则使用 SerializedCache 进行装饰)
      boolean readWrite = !context.getBooleanAttribute("readOnly", false);
      // 获取 blocking 属性 (缓存是否为阻塞方式, 如果为 ture 则使用 BlockingCache 进行装饰)
      boolean blocking = context.getBooleanAttribute("blocking", false);
      // 解析所有的 <property> 节点, 并将其转换成 Properties 对象
      Properties props = context.getChildrenAsProperties();

      // 使用 MapperBuilderAssistant 对象创建缓存对象(Cache).
      builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
    }
  }

  /**
   * 解析 <parameterMap> 节点 .(该节点已经废弃)
   *
   * @param list
   */
  private void parameterMapElement(List<XNode> list) {
    for (XNode parameterMapNode : list) {
      String id = parameterMapNode.getStringAttribute("id");
      String type = parameterMapNode.getStringAttribute("type");
      Class<?> parameterClass = resolveClass(type);
      List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
      List<ParameterMapping> parameterMappings = new ArrayList<>();
      for (XNode parameterNode : parameterNodes) {
        String property = parameterNode.getStringAttribute("property");
        String javaType = parameterNode.getStringAttribute("javaType");
        String jdbcType = parameterNode.getStringAttribute("jdbcType");
        String resultMap = parameterNode.getStringAttribute("resultMap");
        String mode = parameterNode.getStringAttribute("mode");
        String typeHandler = parameterNode.getStringAttribute("typeHandler");
        Integer numericScale = parameterNode.getIntAttribute("numericScale");
        ParameterMode modeEnum = resolveParameterMode(mode);
        Class<?> javaTypeClass = resolveClass(javaType);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
        parameterMappings.add(parameterMapping);
      }
      builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
    }
  }

  /**
   * 解析多个 <resultMap> 节点
   *
   * @param list
   */
  private void resultMapElements(List<XNode> list) {
    // 遍历多个 <resultMap> 节点
    for (XNode resultMapNode : list) {
      try {
        // 解析 <resultMap> 节点
        resultMapElement(resultMapNode);
      } catch (IncompleteElementException e) {
        // ignore, it will be retried
      }
    }
  }

  /**
   * 解析 <resultMap> 节点
   *
   * @param resultMapNode
   * @return
   */
  private ResultMap resultMapElement(XNode resultMapNode) {
    return resultMapElement(resultMapNode, Collections.emptyList(), null);
  }

  /**
   * 递归解析 <resultMap>、<collection>、<association>、<discriminator> 等节点
   *
   * 因为 <collection>、<association>、<discriminator> 等节点中子节点与 <resultMap> 基本一样. 所以这是一个通用解析的方法
   *
   * 1. 先将子节点解析成 ResultMapping 对象
   * 2. 在将 ResultMapping 与自身的属性一起创建 ResultMap 对象
   *
   * <resultMap id="blogWithPostsLazy" type="Blog">
   *   <id property="id" column="id"/>
   *   <result property="title" column="title"/>
   *   <association property="author" column="author_id" select="org.apache.ibatis.domain.blog.mappers.AuthorMapper.selectAuthorWithInlineParams" fetchType="lazy"/>
   *   <association property="author" javaType="Author">
   *     <id property="id" column="author_id"/>
   *     <result property="username" column="author_username"/>
   *   </association>
   *   <collection property="posts" ofType="domain.blog.Post">
   *      <id property="id" column="post_id"/>
   *      <result property="subject" column="post_subject"/>
   *      <result property="body" column="post_body"/>
   *   </collection>
   * </resultMap>
   *
   * @link {https://mybatis.org/mybatis-3/zh/sqlmap-xml.html#%E7%BB%93%E6%9E%9C%E6%98%A0%E5%B0%84}
   * @see #resultMapElement(XNode)
   * @param resultMapNode
   * @param additionalResultMappings
   * @param enclosingType
   * @return
   */
  private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings, Class<?> enclosingType) {
    ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());

    // 如果是 <resultMap>     节点获取 type 属性
    // 如果是 <collection>    节点获取 ofType 属性
    // 如果是 <discriminator> 节点获取 resultType 属性
    // 如果是 <association>   节点获取 javaType 属性
    String type = resultMapNode.getStringAttribute("type",
        resultMapNode.getStringAttribute("ofType",
            resultMapNode.getStringAttribute("resultType",
                resultMapNode.getStringAttribute("javaType"))));

    // 将 type 属性解析成 Class 对象
    Class<?> typeClass = resolveClass(type);
    if (typeClass == null) {
      // 如果为空, 则判断是否为 <association>、<case> 节点，并从它们中解析
      typeClass = inheritEnclosingType(resultMapNode, enclosingType);
    }
    Discriminator discriminator = null;

    // 记录映射集合
    List<ResultMapping> resultMappings = new ArrayList<>(additionalResultMappings);
    // 获取所有的子节点
    List<XNode> resultChildren = resultMapNode.getChildren();

    // 遍历
    for (XNode resultChild : resultChildren) {
      // 处理 <constructor> 节点
      if ("constructor".equals(resultChild.getName())) {
        // 根据所有子节点创建 ResultMapping 对象, 并添加到 resultMappings 集合中
        processConstructorElement(resultChild, typeClass, resultMappings);
      } else if ("discriminator".equals(resultChild.getName())) {
        // 处理 <discriminator> 节点
        discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
      } else {
        // 处理 <id> 、<result>、<association>、<collection> 等节点

        List<ResultFlag> flags = new ArrayList<>();
        // 如果是 <id> 节点则向 flags 集合中添加 `ID` 标识
        if ("id".equals(resultChild.getName())) {
          flags.add(ResultFlag.ID);
        }
        // 创建 ResultMapping 对象, 并添加到 resultMappings 集合中
        resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
      }
    }
    // 获取 id 属性
    String id = resultMapNode.getStringAttribute("id", resultMapNode.getValueBasedIdentifier());
    // 获取 extends 属性. 该属性指定了<resultMap>节点的继承关系
    String extend = resultMapNode.getStringAttribute("extends");
    // 获取 autoMapping 属性
    Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");

    // 创建 ResultMapResolver 解析对象
    ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
    try {
      // 使用 ResultMapResolver 创建 ResultMap 对象. 并添加到 Configuration 中
      return resultMapResolver.resolve();
    } catch (IncompleteElementException e) {
      // 创建失败, 将 ResultMapResolver 对象保存到 Configuration 中
      configuration.addIncompleteResultMap(resultMapResolver);
      throw e;
    }
  }

  /**
   * 嵌套类型
   *
   * @param resultMapNode
   * @param enclosingType
   * @return
   */
  protected Class<?> inheritEnclosingType(XNode resultMapNode, Class<?> enclosingType) {
    if ("association".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
      String property = resultMapNode.getStringAttribute("property");
      if (property != null && enclosingType != null) {
        MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
        return metaResultType.getSetterType(property);
      }
    } else if ("case".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
      return enclosingType;
    }
    return null;
  }

  /**
   * 解析 <constructor> 节点
   *
   * 根据所有子节点创建 ResultMapping 对象, 并添加到 resultMappings 集合中
   *
   * <constructor>
   *    <idArg column="id" javaType="int" name="id" />
   *    <arg column="age" javaType="_int" name="age" />
   *    <arg column="username" javaType="String" name="username" />
   * </constructor>
   *
   * @link {https://mybatis.org/mybatis-3/zh/sqlmap-xml.html#%E6%9E%84%E9%80%A0%E6%96%B9%E6%B3%95}
   * @param resultChild
   * @param resultType
   * @param resultMappings
   */
  private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) {
    // 获取 <constructor> 节点下的所有子节点 (<idArg> 与 <arg>)
    List<XNode> argChildren = resultChild.getChildren();

    // 遍历所有子节点 (<idArg> 与 <arg>), 创建 ResultMapping 对象, 并添加到 resultMappings 集合中
    for (XNode argChild : argChildren) {
      List<ResultFlag> flags = new ArrayList<>();
      // 对所有的构造参数添加 `CONSTRUCTOR` 标识
      flags.add(ResultFlag.CONSTRUCTOR);
      if ("idArg".equals(argChild.getName())) {
        // id构造参数添加 `ID` 标识
        flags.add(ResultFlag.ID);
      }
      // 创建 ResultMapping 对象, 并添加到 resultMappings 集合中
      resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
    }
  }

  /**
   * 解析 <discriminator> 节点 (不常用)
   *
   * @param context
   * @param resultType
   * @param resultMappings
   * @return
   */
  private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) {
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String typeHandler = context.getStringAttribute("typeHandler");
    Class<?> javaTypeClass = resolveClass(javaType);
    Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    Map<String, String> discriminatorMap = new HashMap<>();
    for (XNode caseChild : context.getChildren()) {
      String value = caseChild.getStringAttribute("value");
      String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings, resultType));
      discriminatorMap.put(value, resultMap);
    }
    return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
  }

  /**
   * 解析 <sql> 节点
   *
   * @param list
   */
  private void sqlElement(List<XNode> list) {
    // 是否配置 databaseId
    if (configuration.getDatabaseId() != null) {
      sqlElement(list, configuration.getDatabaseId());
    }
    sqlElement(list, null);
  }

  /**
   * 解析 <sql> 节点
   *
   * <sql id="userColumns" database="mysql"> column1, column2, column3 </sql>
   *
   * @param list
   * @param requiredDatabaseId  databaseId
   */
  private void sqlElement(List<XNode> list, String requiredDatabaseId) {
    // 遍历
    for (XNode context : list) {
      // 获取 databaseId 与 id 属性
      String databaseId = context.getStringAttribute("databaseId");
      String id = context.getStringAttribute("id");
      // id = namespace.id
      id = builderAssistant.applyCurrentNamespace(id, false);

      // databaseId 属性是否与配置的 databaseId 相等
      if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
        // 放入 sqlFragments 字段中 (key: 是id, value: XNode对象)
        sqlFragments.put(id, context);
      }
    }
  }

  /**
   * 匹配 databaseId
   *
   * @param id
   * @param databaseId          节点中的 databaseId 属性
   * @param requiredDatabaseId  配置的 databaseId
   * @return
   */
  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    // 配置的 databaseId 属性不为空的情况下进行比较
    if (requiredDatabaseId != null) {
      return requiredDatabaseId.equals(databaseId);
    }
    if (databaseId != null) {
      return false;
    }
    // 不存在的情况返回 true
    if (!this.sqlFragments.containsKey(id)) {
      return true;
    }
    // skip this fragment if there is a previous one with a not null databaseId
    XNode context = this.sqlFragments.get(id);
    return context.getStringAttribute("databaseId") == null;
  }

  /**
   * 创建 ResultMapping 对象
   *
   * 根据 <id> 、<result>、<association>、<collection> 等节点创建
   *
   * @see #processConstructorElement
   * @param context
   * @param resultType
   * @param flags
   * @return
   */
  private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) {
    String property;
    // 如果是 <constructor> 中的元素(<idArg> 与 <arg>), 则获取其中的 name 属性, 其它的元素获取其 property 属性
    if (flags.contains(ResultFlag.CONSTRUCTOR)) {
      property = context.getStringAttribute("name");
    } else {
      property = context.getStringAttribute("property");
    }
    // 获取 column、javaType、jdbcType 等基础映射属性
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");

    // -- 嵌套 Select 查询. 调用其它映射SQL完成查询, 传递参数需要使用 column 字段, fetchType 字段是加载方式
    // https://mybatis.org/mybatis-3/zh/sqlmap-xml.html#%E5%85%B3%E8%81%94%E7%9A%84%E5%B5%8C%E5%A5%97-select-%E6%9F%A5%E8%AF%A2
    // https://mybatis.org/mybatis-3/zh/sqlmap-xml.html#%E9%9B%86%E5%90%88%E7%9A%84%E5%B5%8C%E5%A5%97-select-%E6%9F%A5%E8%AF%A2
    // 获取 select 属性;(该属性用于关联(一对多,一对一等)查询, 依赖于其它的映射SQL. 且只在 <association> 与 <collection> 节点中存在)
    String nestedSelect = context.getStringAttribute("select");

    // -- 处理关联的嵌套结果映射, 一般是多个表联合查询. 与 Select 查询不同的是它不需要关联其它SQL
    // https://mybatis.org/mybatis-3/zh/sqlmap-xml.html#%E5%85%B3%E8%81%94%E7%9A%84%E5%B5%8C%E5%A5%97%E7%BB%93%E6%9E%9C%E6%98%A0%E5%B0%84
    // https://mybatis.org/mybatis-3/zh/sqlmap-xml.html#%E9%9B%86%E5%90%88%E7%9A%84%E5%B5%8C%E5%A5%97%E7%BB%93%E6%9E%9C%E6%98%A0%E5%B0%84
    // 获取 resultMap、notNullColumn、columnPrefix 属性; 关联的嵌套结果映射
    // 这些属性用于处理关联(一对多,一对一等)的结果映射, 且只在 <association> 与 <collection> 节点中存在)
    String nestedResultMap = context.getStringAttribute("resultMap", () ->
        processNestedResultMappings(context, Collections.emptyList(), resultType));
    String notNullColumn = context.getStringAttribute("notNullColumn");
    String columnPrefix = context.getStringAttribute("columnPrefix");

    // 获取 typeHandler 属性
    String typeHandler = context.getStringAttribute("typeHandler");

    // -- 处理多结果集. 只有在使用存储过程时存在多结果集
    // https://mybatis.org/mybatis-3/zh/sqlmap-xml.html#%E5%85%B3%E8%81%94%E7%9A%84%E5%A4%9A%E7%BB%93%E6%9E%9C%E9%9B%86%EF%BC%88resultset%EF%BC%89
    // 获取 resultSet、foreignColumn 等属性
    String resultSet = context.getStringAttribute("resultSet");
    String foreignColumn = context.getStringAttribute("foreignColumn");

    // -- 处理嵌套查询加载方式
    // 获取 fetchType 属性(嵌套查询的加载方式). 如果配置了 `lazyLoadingEnabled` 属性为 true, 且属性值为 lazy .则表示延迟加载, 否则是及时加载
    // 该属性只有在 <association> 与 <collection> 节点中存在
    boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));

    // 解析 javaType、typeHandler、jdbcType. 将其转换成指定的类
    Class<?> javaTypeClass = resolveClass(javaType);
    Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);

    // 使用 MapperBuilderAssistant 创建 ResultMapping
    return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
  }

  /**
   * 解析嵌套的 ResultMap 对象,并返回其对应的id
   *
   * @see #resultMapElement
   * @param context
   * @param resultMappings
   * @param enclosingType
   * @return
   */
  private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings, Class<?> enclosingType) {
    // 必须是 <association>、<collection>、<case> 节点, 并且 select 属性不存在
    if (Arrays.asList("association", "collection", "case").contains(context.getName())
        && context.getStringAttribute("select") == null) {
      // 校验 <collection> 节点的属性
      validateCollection(context, enclosingType);
      // 解析 ResultMap 对象(递归)
      ResultMap resultMap = resultMapElement(context, resultMappings, enclosingType);
      // 返回 ResultMap 的 id
      return resultMap.getId();
    }
    return null;
  }

  /**
   * 校验 <collection> 节点中 posts 属性是否在类中存在
   *
   * @param context
   * @param enclosingType
   */
  protected void validateCollection(XNode context, Class<?> enclosingType) {
    // <collection> 节点
    if ("collection".equals(context.getName()) && context.getStringAttribute("resultMap") == null
        && context.getStringAttribute("javaType") == null) {
      MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
      // 获取 property 属性
      String property = context.getStringAttribute("property");
      // 判断是否存在对应的 setter 方法
      if (!metaResultType.hasSetter(property)) {
        throw new BuilderException(
            "Ambiguous collection type for property '" + property + "'. You must specify 'javaType' or 'resultMap'.");
      }
    }
  }

  /**
   * 注册 Mapper 接口
   */
  private void bindMapperForNamespace() {
    // 获取当前 namespace
    String namespace = builderAssistant.getCurrentNamespace();
    if (namespace != null) {
      Class<?> boundType = null;
      try {
        // 将 namespace 解析成 Class 类型.(等于是找到对应的接口)
        boundType = Resources.classForName(namespace);
      } catch (ClassNotFoundException e) {
        // ignore, bound type is not required
      }
      // Mapper 接口存在, 并且没有绑定过
      if (boundType != null && !configuration.hasMapper(boundType)) {
        // Spring may not know the real resource name so we set a flag
        // to prevent loading again this resource from the mapper interface
        // look at MapperAnnotationBuilder#loadXmlResource

        // 将资源保存到 Configuration 中, 以防重复加载
        configuration.addLoadedResource("namespace:" + namespace);
        // 注册 Mapper 接口. 使用 MapperRegistry 对象;
        configuration.addMapper(boundType);
      }
    }
  }

}
