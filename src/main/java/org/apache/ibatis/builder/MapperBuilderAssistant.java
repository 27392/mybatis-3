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
package org.apache.ibatis.builder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.CacheBuilder;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMap;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * MapperBuilderAssistant 是 BaseBuilder 的子类
 *
 * 主要负责在Mapper.xml 映射配置文件中,出现的所有元素进行构建
 *
 * 类主要有 MappedStatement、Cache、ResultMap、ResultMapping、Discriminator、ParameterMap、ParameterMapping、MappedStatement
 *
 * @see #useNewCache(Class, Class, Long, Integer, boolean, boolean, Properties)
 * @see #useCacheRef(String)
 *
 * @see #buildResultMapping(Class, String, String, Class, JdbcType, String, String, String, String, Class, List, String, String, boolean)
 * @see #buildResultMapping(Class, String, String, Class, JdbcType, String, String, String, String, Class, List)
 *
 * @see #addResultMap(String, Class, String, Discriminator, List, Boolean)
 * @see #addMappedStatement(String, SqlSource, StatementType, SqlCommandType, Integer, Integer, String, Class, String, Class, ResultSetType, boolean, boolean, boolean, KeyGenerator, String, String, String, LanguageDriver, String)
 *
 * @author Clinton Begin
 */
public class MapperBuilderAssistant extends BaseBuilder {

  // namespace 属性 (命名空间)
  private String currentNamespace;
  // 资源
  private final String resource;
  // 缓存
  private Cache currentCache;
  // 缓存引用是否成功 (false 为成功, true 表示失败)
  private boolean unresolvedCacheRef; // issue #676

  public MapperBuilderAssistant(Configuration configuration, String resource) {
    super(configuration);
    ErrorContext.instance().resource(resource);
    this.resource = resource;
  }

  /**
   * 获取 namespace 属性
   *
   * @return
   */
  public String getCurrentNamespace() {
    return currentNamespace;
  }

  /**
   * 设置 namespace 属性
   *
   * @param currentNamespace
   */
  public void setCurrentNamespace(String currentNamespace) {
    if (currentNamespace == null) {
      throw new BuilderException("The mapper element requires a namespace attribute to be specified.");
    }

    if (this.currentNamespace != null && !this.currentNamespace.equals(currentNamespace)) {
      throw new BuilderException("Wrong namespace. Expected '"
          + this.currentNamespace + "' but found '" + currentNamespace + "'.");
    }

    this.currentNamespace = currentNamespace;
  }

  /**
   * 使用当前的 namespace
   *
   * @param base
   * @param isReference
   * @return
   */
  public String applyCurrentNamespace(String base, boolean isReference) {
    if (base == null) {
      return null;
    }
    if (isReference) {
      // is it qualified with any namespace yet?
      if (base.contains(".")) {
        return base;
      }
    } else {
      // is it qualified with this namespace yet?
      if (base.startsWith(currentNamespace + ".")) {
        return base;
      }
      if (base.contains(".")) {
        throw new BuilderException("Dots are not allowed in element names, please remove it from " + base);
      }
    }
    return currentNamespace + "." + base;
  }

  /**
   * 使用缓存引用
   *
   * @param namespace 被引用的缓存
   * @return
   */
  public Cache useCacheRef(String namespace) {
    // 如果被引用的缓存名称为空抛出异常
    if (namespace == null) {
      throw new BuilderException("cache-ref element requires a namespace attribute.");
    }
    try {
      // 标记解析引用的缓存未成功
      unresolvedCacheRef = true;
      // 获取被引用的缓存, 数据不存在抛出异常
      Cache cache = configuration.getCache(namespace);
      if (cache == null) {
        throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.");
      }
      // 将被引用的缓存赋值给当前缓存
      currentCache = cache;
      // 标记解析引用的缓存成功
      unresolvedCacheRef = false;
      return cache;
    } catch (IllegalArgumentException e) {
      // 被引用的缓存不存在,抛出 IncompleteElementException 异常
      throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.", e);
    }
  }

  /**
   * 创建缓存
   *
   * @param typeClass
   * @param evictionClass
   * @param flushInterval
   * @param size
   * @param readWrite
   * @param blocking
   * @param props
   * @return
   */
  public Cache useNewCache(Class<? extends Cache> typeClass,
      Class<? extends Cache> evictionClass,
      Long flushInterval,
      Integer size,
      boolean readWrite,
      boolean blocking,
      Properties props) {
    // 使用 CacheBuilder 对象构建 Cache (使用了建造者模式)
    Cache cache = new CacheBuilder(currentNamespace)
        .implementation(valueOrDefault(typeClass, PerpetualCache.class))
        .addDecorator(valueOrDefault(evictionClass, LruCache.class))
        .clearInterval(flushInterval)
        .size(size)
        .readWrite(readWrite)
        .blocking(blocking)
        .properties(props)
        .build();
    // 构造好的缓存对象的 id 是 namespace
    // 将缓存对象添加到 Configuration 中的 `Map<String, Cache> caches` 属性中. 其 key 为类名, value 为具体的缓存对象
    configuration.addCache(cache);
    // 记录缓存对象
    currentCache = cache;
    return cache;
  }

  public ParameterMap addParameterMap(String id, Class<?> parameterClass, List<ParameterMapping> parameterMappings) {
    id = applyCurrentNamespace(id, false);
    ParameterMap parameterMap = new ParameterMap.Builder(configuration, id, parameterClass, parameterMappings).build();
    configuration.addParameterMap(parameterMap);
    return parameterMap;
  }

  public ParameterMapping buildParameterMapping(
      Class<?> parameterType,
      String property,
      Class<?> javaType,
      JdbcType jdbcType,
      String resultMap,
      ParameterMode parameterMode,
      Class<? extends TypeHandler<?>> typeHandler,
      Integer numericScale) {
    resultMap = applyCurrentNamespace(resultMap, true);

    // Class parameterType = parameterMapBuilder.type();
    Class<?> javaTypeClass = resolveParameterJavaType(parameterType, property, javaType, jdbcType);
    TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);

    return new ParameterMapping.Builder(configuration, property, javaTypeClass)
        .jdbcType(jdbcType)
        .resultMapId(resultMap)
        .mode(parameterMode)
        .numericScale(numericScale)
        .typeHandler(typeHandlerInstance)
        .build();
  }

  /**
   * 创建 ResultMap 对象
   *
   * @param id
   * @param type
   * @param extend
   * @param discriminator
   * @param resultMappings
   * @param autoMapping
   * @return
   */
  public ResultMap addResultMap(
      String id,
      Class<?> type,
      String extend,
      Discriminator discriminator,
      List<ResultMapping> resultMappings,
      Boolean autoMapping) {
    // id = namespace.id
    id = applyCurrentNamespace(id, false);
    // extend = namespace.extend
    extend = applyCurrentNamespace(extend, true);

    // 处理继承关系
    if (extend != null) {
      // 不存在被继承的 ResultMap, 抛出异常
      if (!configuration.hasResultMap(extend)) {
        throw new IncompleteElementException("Could not find a parent resultmap with id '" + extend + "'");
      }
      // 获取父 ResultMap
      ResultMap resultMap = configuration.getResultMap(extend);
      // 删除与父 ResultMap 重复的 ResultMapping
      List<ResultMapping> extendedResultMappings = new ArrayList<>(resultMap.getResultMappings());
      extendedResultMappings.removeAll(resultMappings);

      // 如果当前定义了 <constructor> 节点,则需要将父 ResultMap 的 ResultMapping 删除
      // Remove parent constructor if this resultMap declares a constructor.
      boolean declaresConstructor = false;
      for (ResultMapping resultMapping : resultMappings) {
        if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
          declaresConstructor = true;
          break;
        }
      }
      if (declaresConstructor) {
        extendedResultMappings.removeIf(resultMapping -> resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR));
      }
      // 添加需要被继承的 ResultMapping
      resultMappings.addAll(extendedResultMappings);
    }
    // 创建 ResultMap 对象
    ResultMap resultMap = new ResultMap.Builder(configuration, id, type, resultMappings, autoMapping)
        .discriminator(discriminator)
        .build();
    // 将 ResultMap 对象保存到 Configuration 中的 `Map<String, ResultMap> resultMaps` 属性中其 key (namespace.id), value 为 ResultMap 对象
    configuration.addResultMap(resultMap);
    // 返回 ResultMap
    return resultMap;
  }

  /**
   * 创建 Discriminator 对象
   *
   * @param resultType
   * @param column
   * @param javaType
   * @param jdbcType
   * @param typeHandler
   * @param discriminatorMap
   * @return
   */
  public Discriminator buildDiscriminator(
      Class<?> resultType,
      String column,
      Class<?> javaType,
      JdbcType jdbcType,
      Class<? extends TypeHandler<?>> typeHandler,
      Map<String, String> discriminatorMap) {

    // 根据 <discriminator> 节点的属性创建 ResultMapping 对象
    ResultMapping resultMapping = buildResultMapping(
        resultType,
        null,
        column,
        javaType,
        jdbcType,
        null,
        null,
        null,
        null,
        typeHandler,
        new ArrayList<>(),
        null,
        null,
        false);
    Map<String, String> namespaceDiscriminatorMap = new HashMap<>();
    for (Map.Entry<String, String> e : discriminatorMap.entrySet()) {
      String resultMap = e.getValue();
      resultMap = applyCurrentNamespace(resultMap, true);
      namespaceDiscriminatorMap.put(e.getKey(), resultMap);
    }
    return new Discriminator.Builder(configuration, resultMapping, namespaceDiscriminatorMap).build();
  }

  /**
   * 创建 MappedStatement 对象
   *
   * @param id
   * @param sqlSource
   * @param statementType
   * @param sqlCommandType
   * @param fetchSize
   * @param timeout
   * @param parameterMap
   * @param parameterType
   * @param resultMap
   * @param resultType
   * @param resultSetType
   * @param flushCache
   * @param useCache
   * @param resultOrdered
   * @param keyGenerator
   * @param keyProperty
   * @param keyColumn
   * @param databaseId
   * @param lang
   * @param resultSets
   * @return
   */
  public MappedStatement addMappedStatement(
      String id,
      SqlSource sqlSource,
      StatementType statementType,
      SqlCommandType sqlCommandType,
      Integer fetchSize,
      Integer timeout,
      String parameterMap,
      Class<?> parameterType,
      String resultMap,
      Class<?> resultType,
      ResultSetType resultSetType,
      boolean flushCache,
      boolean useCache,
      boolean resultOrdered,
      KeyGenerator keyGenerator,
      String keyProperty,
      String keyColumn,
      String databaseId,
      LanguageDriver lang,
      String resultSets) {

    // 缓存引用失败抛出异常
    if (unresolvedCacheRef) {
      throw new IncompleteElementException("Cache-ref not yet resolved");
    }

    // id = namespace + '.' + id
    id = applyCurrentNamespace(id, false);
    // 是否是 select
    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;

    // 使用具体的构造者
    MappedStatement.Builder statementBuilder = new MappedStatement.Builder(configuration, id, sqlSource, sqlCommandType)
        .resource(resource)
        .fetchSize(fetchSize)
        .timeout(timeout)
        .statementType(statementType)
        .keyGenerator(keyGenerator)
        .keyProperty(keyProperty)
        .keyColumn(keyColumn)
        .databaseId(databaseId)
        .lang(lang)
        .resultOrdered(resultOrdered)
        .resultSets(resultSets)
      // 通过 resultMap 属性, 获取已经解析完成的 ResultMap 对象
        .resultMaps(getStatementResultMaps(resultMap, resultType, id))
        .resultSetType(resultSetType)
        .flushCacheRequired(valueOrDefault(flushCache, !isSelect))
        .useCache(valueOrDefault(useCache, isSelect))
        .cache(currentCache);

    // 根据 parameterMap 属性, 获取已经解析完成的 ParameterMap 对象 (废弃)
    ParameterMap statementParameterMap = getStatementParameterMap(parameterMap, parameterType, id);
    if (statementParameterMap != null) {
      statementBuilder.parameterMap(statementParameterMap);
    }

    // 构造 MappedStatement
    MappedStatement statement = statementBuilder.build();
    // 将 MappedStatement 保存在 Configuration 的 `Map<String, MappedStatement> mappedStatements` 属性中(key: namespace.id)
    configuration.addMappedStatement(statement);

    // 返回 MappedStatement
    return statement;
  }

  /**
   * Backward compatibility signature 'addMappedStatement'.
   *
   * @param id
   *          the id
   * @param sqlSource
   *          the sql source
   * @param statementType
   *          the statement type
   * @param sqlCommandType
   *          the sql command type
   * @param fetchSize
   *          the fetch size
   * @param timeout
   *          the timeout
   * @param parameterMap
   *          the parameter map
   * @param parameterType
   *          the parameter type
   * @param resultMap
   *          the result map
   * @param resultType
   *          the result type
   * @param resultSetType
   *          the result set type
   * @param flushCache
   *          the flush cache
   * @param useCache
   *          the use cache
   * @param resultOrdered
   *          the result ordered
   * @param keyGenerator
   *          the key generator
   * @param keyProperty
   *          the key property
   * @param keyColumn
   *          the key column
   * @param databaseId
   *          the database id
   * @param lang
   *          the lang
   * @return the mapped statement
   */
  public MappedStatement addMappedStatement(String id, SqlSource sqlSource, StatementType statementType,
      SqlCommandType sqlCommandType, Integer fetchSize, Integer timeout, String parameterMap, Class<?> parameterType,
      String resultMap, Class<?> resultType, ResultSetType resultSetType, boolean flushCache, boolean useCache,
      boolean resultOrdered, KeyGenerator keyGenerator, String keyProperty, String keyColumn, String databaseId,
      LanguageDriver lang) {
    return addMappedStatement(
      id, sqlSource, statementType, sqlCommandType, fetchSize, timeout,
      parameterMap, parameterType, resultMap, resultType, resultSetType,
      flushCache, useCache, resultOrdered, keyGenerator, keyProperty,
      keyColumn, databaseId, lang, null);
  }

  /**
   * 处理属性值为空的情况
   *
   * @param value
   * @param defaultValue
   * @param <T>
   * @return
   */
  private <T> T valueOrDefault(T value, T defaultValue) {
    return value == null ? defaultValue : value;
  }

  private ParameterMap getStatementParameterMap(
      String parameterMapName,
      Class<?> parameterTypeClass,
      String statementId) {
    parameterMapName = applyCurrentNamespace(parameterMapName, true);
    ParameterMap parameterMap = null;
    if (parameterMapName != null) {
      try {
        parameterMap = configuration.getParameterMap(parameterMapName);
      } catch (IllegalArgumentException e) {
        throw new IncompleteElementException("Could not find parameter map " + parameterMapName, e);
      }
    } else if (parameterTypeClass != null) {
      List<ParameterMapping> parameterMappings = new ArrayList<>();
      parameterMap = new ParameterMap.Builder(
          configuration,
          statementId + "-Inline",
          parameterTypeClass,
          parameterMappings).build();
    }
    return parameterMap;
  }

  /**
   * 获取 resultMap 名称获取 ResultMap 对象
   *
   *  如果指定 resultMap 属性值, 则从 Configuration 中获取已经解析好的 ResultMap 对象
   *  如果没有指定 resultMap 属性值, 而是指定 resultType 属性值. 则创建一个没有属性映射关系的 ResultMap 对象
   *  resultMap 与 resultType 属性值都没有指定则返回空
   *
   * @see #addResultMap(String, Class, String, Discriminator, List, Boolean)
   * @param resultMap   resultMap 属性值
   * @param resultType  resultType 属性值解析后的 Class 对象
   * @param statementId
   * @return
   */
  private List<ResultMap> getStatementResultMaps(
      String resultMap,
      Class<?> resultType,
      String statementId) {
    // 得到 resultMap 的 id; resultMap = namespace.resultMap
    resultMap = applyCurrentNamespace(resultMap, true);

    List<ResultMap> resultMaps = new ArrayList<>();
    // 指定了 resultMap 属性
    if (resultMap != null) {
      // https://zhuanlan.zhihu.com/p/72982781
      // 将名称使用逗号拆分 (如果配置了多个 resetMap 则表示该 SQL 语句能产生多个 ResultSet)
      String[] resultMapNames = resultMap.split(",");
      // 从 Configuration 对象中获取 ResultMap 并添加到集合中
      for (String resultMapName : resultMapNames) {
        try {
          resultMaps.add(configuration.getResultMap(resultMapName.trim()));
        } catch (IllegalArgumentException e) {
          throw new IncompleteElementException("Could not find result map '" + resultMapName + "' referenced from '" + statementId + "'", e);
        }
      }
    } else if (resultType != null) {
      // 没有指定 resultMap 属性, 但指定了 resultType 属性

      // 创建一个不带属性映射的 ResultMap
      ResultMap inlineResultMap = new ResultMap.Builder(
          configuration,
          statementId + "-Inline",
          resultType,
          new ArrayList<>(),
          null).build();
      // 添加到集合中
      resultMaps.add(inlineResultMap);
    }
    return resultMaps;
  }

  /**
   * 创建 ResultMapping 对象
   *
   * @param resultType
   * @param property
   * @param column
   * @param javaType
   * @param jdbcType
   * @param nestedSelect
   * @param nestedResultMap
   * @param notNullColumn
   * @param columnPrefix
   * @param typeHandler
   * @param flags
   * @param resultSet
   * @param foreignColumn
   * @param lazy
   * @return
   */
  public ResultMapping buildResultMapping(
      Class<?> resultType,
      String property,
      String column,
      Class<?> javaType,
      JdbcType jdbcType,
      String nestedSelect,
      String nestedResultMap,
      String notNullColumn,
      String columnPrefix,
      Class<? extends TypeHandler<?>> typeHandler,
      List<ResultFlag> flags,
      String resultSet,
      String foreignColumn,
      boolean lazy) {

    // 根据 property 属性值解析出对应的 Class 类型
    Class<?> javaTypeClass = resolveResultJavaType(resultType, property, javaType);

    // 获取属性对应的类型处理器
    TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);

    // 如果嵌套 select 查询, 并且设置 foreignColumn 参数
    List<ResultMapping> composites;
    if ((nestedSelect == null || nestedSelect.isEmpty()) && (foreignColumn == null || foreignColumn.isEmpty())) {
      composites = Collections.emptyList();
    } else {
      // 解析传递的参数是否是多个
      // 注意：在使用复合主键的时候，你可以使用 column="{prop1=col1,prop2=col2}" 这样的语法来指定多个传递给嵌套 Select 查询语句的列名。这会使得 prop1 和 prop2 作为参数对象，被设置为对应嵌套 Select 语句的参数。
      // https://mybatis.org/mybatis-3/zh/sqlmap-xml.html#%E5%85%B3%E8%81%94%E7%9A%84%E5%B5%8C%E5%A5%97-select-%E6%9F%A5%E8%AF%A2
      composites = parseCompositeColumnName(column);
    }
    // 创建 ResultMapping 对象
    return new ResultMapping.Builder(configuration, property, column, javaTypeClass)
        .jdbcType(jdbcType)
        .nestedQueryId(applyCurrentNamespace(nestedSelect, true))
        .nestedResultMapId(applyCurrentNamespace(nestedResultMap, true))
        .resultSet(resultSet)
        .typeHandler(typeHandlerInstance)
        .flags(flags == null ? new ArrayList<>() : flags)
        .composites(composites)
        .notNullColumns(parseMultipleColumnNames(notNullColumn))
        .columnPrefix(columnPrefix)
        .foreignColumn(foreignColumn)
        .lazy(lazy)
        .build();
  }

  /**
   * Backward compatibility signature 'buildResultMapping'.
   *
   * @param resultType
   *          the result type
   * @param property
   *          the property
   * @param column
   *          the column
   * @param javaType
   *          the java type
   * @param jdbcType
   *          the jdbc type
   * @param nestedSelect
   *          the nested select
   * @param nestedResultMap
   *          the nested result map
   * @param notNullColumn
   *          the not null column
   * @param columnPrefix
   *          the column prefix
   * @param typeHandler
   *          the type handler
   * @param flags
   *          the flags
   * @return the result mapping
   */
  public ResultMapping buildResultMapping(Class<?> resultType, String property, String column, Class<?> javaType,
      JdbcType jdbcType, String nestedSelect, String nestedResultMap, String notNullColumn, String columnPrefix,
      Class<? extends TypeHandler<?>> typeHandler, List<ResultFlag> flags) {
    return buildResultMapping(
      resultType, property, column, javaType, jdbcType, nestedSelect,
      nestedResultMap, notNullColumn, columnPrefix, typeHandler, flags, null, null, configuration.isLazyLoadingEnabled());
  }

  /**
   * Gets the language driver.
   *
   * @param langClass
   *          the lang class
   * @return the language driver
   * @deprecated Use {@link Configuration#getLanguageDriver(Class)}
   */
  @Deprecated
  public LanguageDriver getLanguageDriver(Class<? extends LanguageDriver> langClass) {
    return configuration.getLanguageDriver(langClass);
  }

  /**
   * 将 notNullColumn 属性解析成多个
   *
   * {child_id, child_name}
   *
   * @param columnName
   * @return
   */
  private Set<String> parseMultipleColumnNames(String columnName) {
    Set<String> columns = new HashSet<>();
    if (columnName != null) {
      if (columnName.indexOf(',') > -1) {
        StringTokenizer parser = new StringTokenizer(columnName, "{}, ", false);
        while (parser.hasMoreTokens()) {
          String column = parser.nextToken();
          columns.add(column);
        }
      } else {
        columns.add(columnName);
      }
    }
    return columns;
  }

  /**
   * 解析多列名
   *
   * column="{prop1=col1,prop2=col2}"
   *
   * @param columnName
   * @return
   */
  private List<ResultMapping> parseCompositeColumnName(String columnName) {
    List<ResultMapping> composites = new ArrayList<>();
    if (columnName != null && (columnName.indexOf('=') > -1 || columnName.indexOf(',') > -1)) {
      StringTokenizer parser = new StringTokenizer(columnName, "{}=, ", false);
      while (parser.hasMoreTokens()) {
        String property = parser.nextToken();
        String column = parser.nextToken();
        ResultMapping complexResultMapping = new ResultMapping.Builder(
            configuration, property, column, configuration.getTypeHandlerRegistry().getUnknownTypeHandler()).build();
        composites.add(complexResultMapping);
      }
    }
    return composites;
  }

  /**
   * 解析 ResultMapping 的 property 属性类型
   *
   * @param resultType  ResultMap 的类型
   * @param property    属性
   * @param javaType    属性的 Class 类型
   * @return
   */
  private Class<?> resolveResultJavaType(Class<?> resultType, String property, Class<?> javaType) {
    // 如果属性类型为空, 属性不为空. 尝试从 <ResultMap> 节点的 type 属性类型中获取
    if (javaType == null && property != null) {
      try {
        // 获取属性在. <ResultMap> 节点的 type 属性类型中的对应的类型
        MetaClass metaResultType = MetaClass.forClass(resultType, configuration.getReflectorFactory());
        javaType = metaResultType.getSetterType(property);
      } catch (Exception e) {
        // ignore, following null check statement will deal with the situation
      }
    }
    if (javaType == null) {
      javaType = Object.class;
    }
    return javaType;
  }

  private Class<?> resolveParameterJavaType(Class<?> resultType, String property, Class<?> javaType, JdbcType jdbcType) {
    if (javaType == null) {
      if (JdbcType.CURSOR.equals(jdbcType)) {
        javaType = java.sql.ResultSet.class;
      } else if (Map.class.isAssignableFrom(resultType)) {
        javaType = Object.class;
      } else {
        MetaClass metaResultType = MetaClass.forClass(resultType, configuration.getReflectorFactory());
        javaType = metaResultType.getGetterType(property);
      }
    }
    if (javaType == null) {
      javaType = Object.class;
    }
    return javaType;
  }

}
