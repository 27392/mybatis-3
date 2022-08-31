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
package org.apache.ibatis.session;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiFunction;

import org.apache.ibatis.binding.MapperRegistry;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.builder.annotation.MethodResolver;
import org.apache.ibatis.builder.xml.XMLStatementBuilder;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.FifoCache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.decorators.SoftCache;
import org.apache.ibatis.cache.decorators.WeakCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.datasource.jndi.JndiDataSourceFactory;
import org.apache.ibatis.datasource.pooled.PooledDataSourceFactory;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSourceFactory;
import org.apache.ibatis.executor.BatchExecutor;
import org.apache.ibatis.executor.CachingExecutor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ReuseExecutor;
import org.apache.ibatis.executor.SimpleExecutor;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.executor.loader.cglib.CglibProxyFactory;
import org.apache.ibatis.executor.loader.javassist.JavassistProxyFactory;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.executor.statement.RoutingStatementHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.commons.JakartaCommonsLoggingImpl;
import org.apache.ibatis.logging.jdk14.Jdk14LoggingImpl;
import org.apache.ibatis.logging.log4j.Log4jImpl;
import org.apache.ibatis.logging.log4j2.Log4j2Impl;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.logging.slf4j.Slf4jImpl;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMap;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.InterceptorChain;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.DefaultObjectFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.DefaultObjectWrapperFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.scripting.LanguageDriverRegistry;
import org.apache.ibatis.scripting.defaults.RawLanguageDriver;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeAliasRegistry;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 */
public class Configuration {

  // 环境对象 (包含事务工厂, 数据源)
  protected Environment environment;

  /**
   * 是否允许在嵌套语句中使用分页（RowBounds）。如果允许使用则设置为 false。
   */
  protected boolean safeRowBoundsEnabled;

  /**
   * 是否允许在嵌套语句中使用结果处理器（ResultHandler）。如果允许使用则设置为 false。
   */
  protected boolean safeResultHandlerEnabled = true;
  protected boolean mapUnderscoreToCamelCase;

  /**
   * [延迟加载] 开启时，任一方法的调用都会加载该对象的所有延迟加载属性。 否则，每个延迟加载属性会按需加载
   */
  protected boolean aggressiveLazyLoading;
  protected boolean multipleResultSetsEnabled = true;
  protected boolean useGeneratedKeys;
  protected boolean useColumnLabel = true;
  protected boolean cacheEnabled = true;
  protected boolean callSettersOnNulls;
  protected boolean useActualParamName = true;
  protected boolean returnInstanceForEmptyRow;
  protected boolean shrinkWhitespacesInSql;
  protected boolean nullableOnForEach;

  protected String logPrefix;
  // 日志实现类
  protected Class<? extends Log> logImpl;
  // vfs 实现类
  protected Class<? extends VFS> vfsImpl;
  protected Class<?> defaultSqlProviderType;
  protected LocalCacheScope localCacheScope = LocalCacheScope.SESSION;
  protected JdbcType jdbcTypeForNull = JdbcType.OTHER;

  /**
   * [延迟加载] 指定对象的哪些方法触发一次延迟加载。
   */
  protected Set<String> lazyLoadTriggerMethods = new HashSet<>(Arrays.asList("equals", "clone", "hashCode", "toString"));
  protected Integer defaultStatementTimeout;
  protected Integer defaultFetchSize;
  protected ResultSetType defaultResultSetType;
  protected ExecutorType defaultExecutorType = ExecutorType.SIMPLE;
  protected AutoMappingBehavior autoMappingBehavior = AutoMappingBehavior.PARTIAL;
  protected AutoMappingUnknownColumnBehavior autoMappingUnknownColumnBehavior = AutoMappingUnknownColumnBehavior.NONE;

  // 环境变量
  protected Properties variables = new Properties();
  // 反射器工厂 (主要实现了对 Reflector 对象的创建与缓存)
  protected ReflectorFactory reflectorFactory = new DefaultReflectorFactory();
  // 对象工厂 (主要用来创建对象)
  protected ObjectFactory objectFactory = new DefaultObjectFactory();
  // 对象包装工厂 (主要负责创建 ObjectWrapper 对象)
  protected ObjectWrapperFactory objectWrapperFactory = new DefaultObjectWrapperFactory();

  // 延迟加载的全局开关。当开启时，所有关联对象都会延迟加载。 特定关联关系中可通过设置 fetchType 属性来覆盖该项的开关状态。
  protected boolean lazyLoadingEnabled = false;

  /**
   * [延迟加载] 代理工厂(只要用来做延迟加载)。默认是JavassistProxyFactory
   */
  protected ProxyFactory proxyFactory = new JavassistProxyFactory(); // #224 Using internal Javassist instead of OGNL

  // 当前使用的数据库 ID
  protected String databaseId;

  /**
   * Configuration factory class.
   * Used to create Configuration for loading deserialized unread properties.
   *
   * @see <a href='https://github.com/mybatis/old-google-code-issues/issues/300'>Issue 300 (google code)</a>
   */
  protected Class<?> configurationFactory;

  // Mapper 注册器
  protected final MapperRegistry mapperRegistry = new MapperRegistry(this);
  // 拦截器链(包含了多个拦截器信息)
  protected final InterceptorChain interceptorChain = new InterceptorChain();
  // 类型注册器 (全局唯一)
  protected final TypeHandlerRegistry typeHandlerRegistry = new TypeHandlerRegistry(this);
  // 别名注册器 (全局唯一)
  protected final TypeAliasRegistry typeAliasRegistry = new TypeAliasRegistry();
  protected final LanguageDriverRegistry languageRegistry = new LanguageDriverRegistry();

  protected final Map<String, MappedStatement> mappedStatements = new StrictMap<MappedStatement>("Mapped Statements collection")
      .conflictMessageProducer((savedValue, targetValue) ->
          ". please check " + savedValue.getResource() + " and " + targetValue.getResource());
  // 缓存映射 (key: namespace)
  protected final Map<String, Cache> caches = new StrictMap<>("Caches collection");
  // ResultMap 映射 (key: namespace.id)
  protected final Map<String, ResultMap> resultMaps = new StrictMap<>("Result Maps collection");
  protected final Map<String, ParameterMap> parameterMaps = new StrictMap<>("Parameter Maps collection");
  protected final Map<String, KeyGenerator> keyGenerators = new StrictMap<>("Key Generators collection");

  // 已加载的资源列表 (用来判断是否重复加载)
  protected final Set<String> loadedResources = new HashSet<>();
  // sql 片段映射, 映射配置文件中 <sql> 节点 (key: namespace.id)
  protected final Map<String, XNode> sqlFragments = new StrictMap<>("XML fragments parsed from previous mappers");

  // 解析失败的 XMLStatementBuilder 对象集合
  protected final Collection<XMLStatementBuilder> incompleteStatements = new LinkedList<>();
  // 引用缓存失败的对象集合
  protected final Collection<CacheRefResolver> incompleteCacheRefs = new LinkedList<>();
  // 创建 ResultMap 失败的对象集合
  protected final Collection<ResultMapResolver> incompleteResultMaps = new LinkedList<>();
  protected final Collection<MethodResolver> incompleteMethods = new LinkedList<>();

  /*
   * A map holds cache-ref relationship. The key is the namespace that
   * references a cache bound to another namespace and the value is the
   * namespace which the actual cache is bound to.
   */
  // 缓存引用的关系(key: 使用方,value: 被应用方)
  protected final Map<String, String> cacheRefMap = new HashMap<>();

  public Configuration(Environment environment) {
    this();
    this.environment = environment;
  }

  public Configuration() {
    typeAliasRegistry.registerAlias("JDBC", JdbcTransactionFactory.class);
    typeAliasRegistry.registerAlias("MANAGED", ManagedTransactionFactory.class);

    typeAliasRegistry.registerAlias("JNDI", JndiDataSourceFactory.class);
    typeAliasRegistry.registerAlias("POOLED", PooledDataSourceFactory.class);
    typeAliasRegistry.registerAlias("UNPOOLED", UnpooledDataSourceFactory.class);

    typeAliasRegistry.registerAlias("PERPETUAL", PerpetualCache.class);
    typeAliasRegistry.registerAlias("FIFO", FifoCache.class);
    typeAliasRegistry.registerAlias("LRU", LruCache.class);
    typeAliasRegistry.registerAlias("SOFT", SoftCache.class);
    typeAliasRegistry.registerAlias("WEAK", WeakCache.class);

    typeAliasRegistry.registerAlias("DB_VENDOR", VendorDatabaseIdProvider.class);

    typeAliasRegistry.registerAlias("XML", XMLLanguageDriver.class);
    typeAliasRegistry.registerAlias("RAW", RawLanguageDriver.class);

    typeAliasRegistry.registerAlias("SLF4J", Slf4jImpl.class);
    typeAliasRegistry.registerAlias("COMMONS_LOGGING", JakartaCommonsLoggingImpl.class);
    typeAliasRegistry.registerAlias("LOG4J", Log4jImpl.class);
    typeAliasRegistry.registerAlias("LOG4J2", Log4j2Impl.class);
    typeAliasRegistry.registerAlias("JDK_LOGGING", Jdk14LoggingImpl.class);
    typeAliasRegistry.registerAlias("STDOUT_LOGGING", StdOutImpl.class);
    typeAliasRegistry.registerAlias("NO_LOGGING", NoLoggingImpl.class);

    typeAliasRegistry.registerAlias("CGLIB", CglibProxyFactory.class);
    typeAliasRegistry.registerAlias("JAVASSIST", JavassistProxyFactory.class);

    languageRegistry.setDefaultDriverClass(XMLLanguageDriver.class);
    languageRegistry.register(RawLanguageDriver.class);
  }

  public String getLogPrefix() {
    return logPrefix;
  }

  public void setLogPrefix(String logPrefix) {
    this.logPrefix = logPrefix;
  }

  public Class<? extends Log> getLogImpl() {
    return logImpl;
  }

  /**
   * 设置日志实现类
   *
   * @param logImpl
   */
  public void setLogImpl(Class<? extends Log> logImpl) {
    // 非空判断
    if (logImpl != null) {
      // 记录自定义日志实现类
      this.logImpl = logImpl;
      // 设置自定义日志类
      LogFactory.useCustomLogging(this.logImpl);
    }
  }

  /**
   * 获取 vfs 实现类
   *
   * @return
   */
  public Class<? extends VFS> getVfsImpl() {
    return this.vfsImpl;
  }

  /**
   * 设置 vfs 实现类
   *
   * @param vfsImpl
   */
  public void setVfsImpl(Class<? extends VFS> vfsImpl) {
    // 非空判断
    if (vfsImpl != null) {
      // 记录自定义 vfs 实现类
      this.vfsImpl = vfsImpl;
      // 记录自定义 vfs 实现类
      VFS.addImplClass(this.vfsImpl);
    }
  }

  /**
   * Gets an applying type when omit a type on sql provider annotation(e.g. {@link org.apache.ibatis.annotations.SelectProvider}).
   *
   * @return the default type for sql provider annotation
   * @since 3.5.6
   */
  public Class<?> getDefaultSqlProviderType() {
    return defaultSqlProviderType;
  }

  /**
   * Sets an applying type when omit a type on sql provider annotation(e.g. {@link org.apache.ibatis.annotations.SelectProvider}).
   *
   * @param defaultSqlProviderType
   *          the default type for sql provider annotation
   * @since 3.5.6
   */
  public void setDefaultSqlProviderType(Class<?> defaultSqlProviderType) {
    this.defaultSqlProviderType = defaultSqlProviderType;
  }

  public boolean isCallSettersOnNulls() {
    return callSettersOnNulls;
  }

  public void setCallSettersOnNulls(boolean callSettersOnNulls) {
    this.callSettersOnNulls = callSettersOnNulls;
  }

  public boolean isUseActualParamName() {
    return useActualParamName;
  }

  public void setUseActualParamName(boolean useActualParamName) {
    this.useActualParamName = useActualParamName;
  }

  public boolean isReturnInstanceForEmptyRow() {
    return returnInstanceForEmptyRow;
  }

  public void setReturnInstanceForEmptyRow(boolean returnEmptyInstance) {
    this.returnInstanceForEmptyRow = returnEmptyInstance;
  }

  public boolean isShrinkWhitespacesInSql() {
    return shrinkWhitespacesInSql;
  }

  public void setShrinkWhitespacesInSql(boolean shrinkWhitespacesInSql) {
    this.shrinkWhitespacesInSql = shrinkWhitespacesInSql;
  }

  /**
   * Sets the default value of 'nullable' attribute on 'foreach' tag.
   *
   * @param nullableOnForEach If nullable, set to {@code true}
   * @since 3.5.9
   */
  public void setNullableOnForEach(boolean nullableOnForEach) {
    this.nullableOnForEach = nullableOnForEach;
  }

  /**
   * Returns the default value of 'nullable' attribute on 'foreach' tag.
   *
   * <p>Default is {@code false}.
   *
   * @return If nullable, set to {@code true}
   * @since 3.5.9
   */
  public boolean isNullableOnForEach() {
    return nullableOnForEach;
  }

  /**
   * 获取数据库 id
   *
   * @return
   */
  public String getDatabaseId() {
    return databaseId;
  }

  /**
   * 设置数据库 id
   *
   * @param databaseId
   */
  public void setDatabaseId(String databaseId) {
    this.databaseId = databaseId;
  }

  public Class<?> getConfigurationFactory() {
    return configurationFactory;
  }

  public void setConfigurationFactory(Class<?> configurationFactory) {
    this.configurationFactory = configurationFactory;
  }

  public boolean isSafeResultHandlerEnabled() {
    return safeResultHandlerEnabled;
  }

  public void setSafeResultHandlerEnabled(boolean safeResultHandlerEnabled) {
    this.safeResultHandlerEnabled = safeResultHandlerEnabled;
  }

  public boolean isSafeRowBoundsEnabled() {
    return safeRowBoundsEnabled;
  }

  public void setSafeRowBoundsEnabled(boolean safeRowBoundsEnabled) {
    this.safeRowBoundsEnabled = safeRowBoundsEnabled;
  }

  public boolean isMapUnderscoreToCamelCase() {
    return mapUnderscoreToCamelCase;
  }

  public void setMapUnderscoreToCamelCase(boolean mapUnderscoreToCamelCase) {
    this.mapUnderscoreToCamelCase = mapUnderscoreToCamelCase;
  }

  /**
   * 记录资源到已加载资源列表
   *
   * @param resource
   */
  public void addLoadedResource(String resource) {
    loadedResources.add(resource);
  }

  /**
   * 是否加载过该资源
   *
   * @param resource
   * @return
   */
  public boolean isResourceLoaded(String resource) {
    return loadedResources.contains(resource);
  }

  /**
   * 获取 Environment 对象
   *
   * @return
   */
  public Environment getEnvironment() {
    return environment;
  }

  /**
   * 设置 Environment 对象
   *
   * @param environment
   */
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  public AutoMappingBehavior getAutoMappingBehavior() {
    return autoMappingBehavior;
  }

  public void setAutoMappingBehavior(AutoMappingBehavior autoMappingBehavior) {
    this.autoMappingBehavior = autoMappingBehavior;
  }

  /**
   * Gets the auto mapping unknown column behavior.
   *
   * @return the auto mapping unknown column behavior
   * @since 3.4.0
   */
  public AutoMappingUnknownColumnBehavior getAutoMappingUnknownColumnBehavior() {
    return autoMappingUnknownColumnBehavior;
  }

  /**
   * Sets the auto mapping unknown column behavior.
   *
   * @param autoMappingUnknownColumnBehavior
   *          the new auto mapping unknown column behavior
   * @since 3.4.0
   */
  public void setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior autoMappingUnknownColumnBehavior) {
    this.autoMappingUnknownColumnBehavior = autoMappingUnknownColumnBehavior;
  }

  public boolean isLazyLoadingEnabled() {
    return lazyLoadingEnabled;
  }

  public void setLazyLoadingEnabled(boolean lazyLoadingEnabled) {
    this.lazyLoadingEnabled = lazyLoadingEnabled;
  }

  public ProxyFactory getProxyFactory() {
    return proxyFactory;
  }

  public void setProxyFactory(ProxyFactory proxyFactory) {
    if (proxyFactory == null) {
      proxyFactory = new JavassistProxyFactory();
    }
    this.proxyFactory = proxyFactory;
  }

  public boolean isAggressiveLazyLoading() {
    return aggressiveLazyLoading;
  }

  public void setAggressiveLazyLoading(boolean aggressiveLazyLoading) {
    this.aggressiveLazyLoading = aggressiveLazyLoading;
  }

  public boolean isMultipleResultSetsEnabled() {
    return multipleResultSetsEnabled;
  }

  public void setMultipleResultSetsEnabled(boolean multipleResultSetsEnabled) {
    this.multipleResultSetsEnabled = multipleResultSetsEnabled;
  }

  public Set<String> getLazyLoadTriggerMethods() {
    return lazyLoadTriggerMethods;
  }

  public void setLazyLoadTriggerMethods(Set<String> lazyLoadTriggerMethods) {
    this.lazyLoadTriggerMethods = lazyLoadTriggerMethods;
  }

  public boolean isUseGeneratedKeys() {
    return useGeneratedKeys;
  }

  public void setUseGeneratedKeys(boolean useGeneratedKeys) {
    this.useGeneratedKeys = useGeneratedKeys;
  }

  public ExecutorType getDefaultExecutorType() {
    return defaultExecutorType;
  }

  public void setDefaultExecutorType(ExecutorType defaultExecutorType) {
    this.defaultExecutorType = defaultExecutorType;
  }

  public boolean isCacheEnabled() {
    return cacheEnabled;
  }

  public void setCacheEnabled(boolean cacheEnabled) {
    this.cacheEnabled = cacheEnabled;
  }

  public Integer getDefaultStatementTimeout() {
    return defaultStatementTimeout;
  }

  public void setDefaultStatementTimeout(Integer defaultStatementTimeout) {
    this.defaultStatementTimeout = defaultStatementTimeout;
  }

  /**
   * Gets the default fetch size.
   *
   * @return the default fetch size
   * @since 3.3.0
   */
  public Integer getDefaultFetchSize() {
    return defaultFetchSize;
  }

  /**
   * Sets the default fetch size.
   *
   * @param defaultFetchSize
   *          the new default fetch size
   * @since 3.3.0
   */
  public void setDefaultFetchSize(Integer defaultFetchSize) {
    this.defaultFetchSize = defaultFetchSize;
  }

  /**
   * Gets the default result set type.
   *
   * @return the default result set type
   * @since 3.5.2
   */
  public ResultSetType getDefaultResultSetType() {
    return defaultResultSetType;
  }

  /**
   * Sets the default result set type.
   *
   * @param defaultResultSetType
   *          the new default result set type
   * @since 3.5.2
   */
  public void setDefaultResultSetType(ResultSetType defaultResultSetType) {
    this.defaultResultSetType = defaultResultSetType;
  }

  public boolean isUseColumnLabel() {
    return useColumnLabel;
  }

  public void setUseColumnLabel(boolean useColumnLabel) {
    this.useColumnLabel = useColumnLabel;
  }

  public LocalCacheScope getLocalCacheScope() {
    return localCacheScope;
  }

  public void setLocalCacheScope(LocalCacheScope localCacheScope) {
    this.localCacheScope = localCacheScope;
  }

  public JdbcType getJdbcTypeForNull() {
    return jdbcTypeForNull;
  }

  public void setJdbcTypeForNull(JdbcType jdbcTypeForNull) {
    this.jdbcTypeForNull = jdbcTypeForNull;
  }

  public Properties getVariables() {
    return variables;
  }

  public void setVariables(Properties variables) {
    this.variables = variables;
  }

  public TypeHandlerRegistry getTypeHandlerRegistry() {
    return typeHandlerRegistry;
  }

  /**
   * Set a default {@link TypeHandler} class for {@link Enum}.
   * A default {@link TypeHandler} is {@link org.apache.ibatis.type.EnumTypeHandler}.
   * @param typeHandler a type handler class for {@link Enum}
   * @since 3.4.5
   */
  public void setDefaultEnumTypeHandler(Class<? extends TypeHandler> typeHandler) {
    if (typeHandler != null) {
      getTypeHandlerRegistry().setDefaultEnumTypeHandler(typeHandler);
    }
  }

  public TypeAliasRegistry getTypeAliasRegistry() {
    return typeAliasRegistry;
  }

  /**
   * Gets the mapper registry.
   *
   * @return the mapper registry
   * @since 3.2.2
   */
  public MapperRegistry getMapperRegistry() {
    return mapperRegistry;
  }

  /**
   * 获取 ReflectorFactory 对象
   *
   * @return
   */
  public ReflectorFactory getReflectorFactory() {
    return reflectorFactory;
  }

  /**
   * 设置 ReflectorFactory 对象
   *
   * @param reflectorFactory
   */
  public void setReflectorFactory(ReflectorFactory reflectorFactory) {
    this.reflectorFactory = reflectorFactory;
  }

  /**
   * 获取 ObjectFactory 对象
   *
   * @return
   */
  public ObjectFactory getObjectFactory() {
    return objectFactory;
  }

  /**
   * 设置 ObjectFactory 对象
   *
   * @param objectFactory
   */
  public void setObjectFactory(ObjectFactory objectFactory) {
    this.objectFactory = objectFactory;
  }

  /**
   * 获取 ObjectWrapperFactory 对象
   *
   * @return
   */
  public ObjectWrapperFactory getObjectWrapperFactory() {
    return objectWrapperFactory;
  }

  /**
   * 设置 ObjectWrapperFactory 对象
   *
   * @param objectWrapperFactory
   */
  public void setObjectWrapperFactory(ObjectWrapperFactory objectWrapperFactory) {
    this.objectWrapperFactory = objectWrapperFactory;
  }

  /**
   * 获取所有的插件
   *
   * Gets the interceptors.
   *
   * @return the interceptors
   * @since 3.2.2
   */
  public List<Interceptor> getInterceptors() {
    return interceptorChain.getInterceptors();
  }

  public LanguageDriverRegistry getLanguageRegistry() {
    return languageRegistry;
  }

  public void setDefaultScriptingLanguage(Class<? extends LanguageDriver> driver) {
    if (driver == null) {
      driver = XMLLanguageDriver.class;
    }
    getLanguageRegistry().setDefaultDriverClass(driver);
  }

  public LanguageDriver getDefaultScriptingLanguageInstance() {
    return languageRegistry.getDefaultDriver();
  }

  /**
   * Gets the language driver.
   *
   * @param langClass
   *          the lang class
   * @return the language driver
   * @since 3.5.1
   */
  public LanguageDriver getLanguageDriver(Class<? extends LanguageDriver> langClass) {
    if (langClass == null) {
      return languageRegistry.getDefaultDriver();
    }
    languageRegistry.register(langClass);
    return languageRegistry.getDriver(langClass);
  }

  /**
   * Gets the default scripting language instance.
   *
   * @return the default scripting language instance
   * @deprecated Use {@link #getDefaultScriptingLanguageInstance()}
   */
  @Deprecated
  public LanguageDriver getDefaultScriptingLanuageInstance() {
    return getDefaultScriptingLanguageInstance();
  }

  public MetaObject newMetaObject(Object object) {
    return MetaObject.forObject(object, objectFactory, objectWrapperFactory, reflectorFactory);
  }

  public ParameterHandler newParameterHandler(MappedStatement mappedStatement, Object parameterObject, BoundSql boundSql) {
    ParameterHandler parameterHandler = mappedStatement.getLang().createParameterHandler(mappedStatement, parameterObject, boundSql);
    parameterHandler = (ParameterHandler) interceptorChain.pluginAll(parameterHandler);
    return parameterHandler;
  }

  public ResultSetHandler newResultSetHandler(Executor executor, MappedStatement mappedStatement, RowBounds rowBounds, ParameterHandler parameterHandler,
      ResultHandler resultHandler, BoundSql boundSql) {
    ResultSetHandler resultSetHandler = new DefaultResultSetHandler(executor, mappedStatement, parameterHandler, resultHandler, boundSql, rowBounds);
    resultSetHandler = (ResultSetHandler) interceptorChain.pluginAll(resultSetHandler);
    return resultSetHandler;
  }

  public StatementHandler newStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
    StatementHandler statementHandler = new RoutingStatementHandler(executor, mappedStatement, parameterObject, rowBounds, resultHandler, boundSql);
    statementHandler = (StatementHandler) interceptorChain.pluginAll(statementHandler);
    return statementHandler;
  }

  public Executor newExecutor(Transaction transaction) {
    return newExecutor(transaction, defaultExecutorType);
  }

  public Executor newExecutor(Transaction transaction, ExecutorType executorType) {
    executorType = executorType == null ? defaultExecutorType : executorType;
    executorType = executorType == null ? ExecutorType.SIMPLE : executorType;
    Executor executor;
    if (ExecutorType.BATCH == executorType) {
      executor = new BatchExecutor(this, transaction);
    } else if (ExecutorType.REUSE == executorType) {
      executor = new ReuseExecutor(this, transaction);
    } else {
      executor = new SimpleExecutor(this, transaction);
    }
    if (cacheEnabled) {
      executor = new CachingExecutor(executor);
    }
    executor = (Executor) interceptorChain.pluginAll(executor);
    return executor;
  }

  public void addKeyGenerator(String id, KeyGenerator keyGenerator) {
    keyGenerators.put(id, keyGenerator);
  }

  public Collection<String> getKeyGeneratorNames() {
    return keyGenerators.keySet();
  }

  public Collection<KeyGenerator> getKeyGenerators() {
    return keyGenerators.values();
  }

  public KeyGenerator getKeyGenerator(String id) {
    return keyGenerators.get(id);
  }

  public boolean hasKeyGenerator(String id) {
    return keyGenerators.containsKey(id);
  }

  /**
   * 添加缓存
   * Cache 的 id 是 namespace
   *
   * @param cache
   */
  public void addCache(Cache cache) {
    caches.put(cache.getId(), cache);
  }

  /**
   * 获取所有缓存的名称
   *
   * @return
   */
  public Collection<String> getCacheNames() {
    return caches.keySet();
  }

  /**
   * 获取所有的缓存对象
   *
   * @return
   */
  public Collection<Cache> getCaches() {
    return caches.values();
  }

  /**
   * 获取具体的缓存对象
   *
   * @param id
   * @return
   */
  public Cache getCache(String id) {
    return caches.get(id);
  }

  /**
   * 是否存在缓存对象
   *
   * @param id
   * @return
   */
  public boolean hasCache(String id) {
    return caches.containsKey(id);
  }

  /**
   * 添加 ResultMap
   *
   * @param rm
   */
  public void addResultMap(ResultMap rm) {
    resultMaps.put(rm.getId(), rm);
    checkLocallyForDiscriminatedNestedResultMaps(rm);
    checkGloballyForDiscriminatedNestedResultMaps(rm);
  }

  /**
   * 获取所有的 ResultMap 的 id
   *
   * @return
   */
  public Collection<String> getResultMapNames() {
    return resultMaps.keySet();
  }

  /**
   * 获取所有的 ResultMap
   *
   * @return
   */
  public Collection<ResultMap> getResultMaps() {
    return resultMaps.values();
  }

  /**
   * 获取 ResultMap
   *
   * @param id
   * @return
   */
  public ResultMap getResultMap(String id) {
    return resultMaps.get(id);
  }

  /**
   * ResultMap 是否存在
   *
   * @param id
   * @return
   */
  public boolean hasResultMap(String id) {
    return resultMaps.containsKey(id);
  }

  public void addParameterMap(ParameterMap pm) {
    parameterMaps.put(pm.getId(), pm);
  }

  public Collection<String> getParameterMapNames() {
    return parameterMaps.keySet();
  }

  public Collection<ParameterMap> getParameterMaps() {
    return parameterMaps.values();
  }

  public ParameterMap getParameterMap(String id) {
    return parameterMaps.get(id);
  }

  public boolean hasParameterMap(String id) {
    return parameterMaps.containsKey(id);
  }

  public void addMappedStatement(MappedStatement ms) {
    mappedStatements.put(ms.getId(), ms);
  }

  public Collection<String> getMappedStatementNames() {
    buildAllStatements();
    return mappedStatements.keySet();
  }

  public Collection<MappedStatement> getMappedStatements() {
    buildAllStatements();
    return mappedStatements.values();
  }

  /**
   * 获取解析失败的 XMLStatementBuilder 集合
   *
   * @return
   */
  public Collection<XMLStatementBuilder> getIncompleteStatements() {
    return incompleteStatements;
  }

  /**
   * 添加 XMLStatementBuilder 到解析失败的集合
   *
   * @param incompleteStatement
   */
  public void addIncompleteStatement(XMLStatementBuilder incompleteStatement) {
    incompleteStatements.add(incompleteStatement);
  }

  /**
   * 获取解析失败的 CacheRefResolver 集合
   *
   * @return
   */
  public Collection<CacheRefResolver> getIncompleteCacheRefs() {
    return incompleteCacheRefs;
  }

  /**
   * 添加 CacheRefResolver 到解析失败的集合
   *
   * @param incompleteCacheRef
   */
  public void addIncompleteCacheRef(CacheRefResolver incompleteCacheRef) {
    incompleteCacheRefs.add(incompleteCacheRef);
  }

  /**
   * 获取解析失败的 ResultMapResolver 集合
   *
   * @return
   */
  public Collection<ResultMapResolver> getIncompleteResultMaps() {
    return incompleteResultMaps;
  }

  /**
   * 添加 ResultMapResolver 到解析失败的集合
   *
   * @param resultMapResolver
   */
  public void addIncompleteResultMap(ResultMapResolver resultMapResolver) {
    incompleteResultMaps.add(resultMapResolver);
  }

  public void addIncompleteMethod(MethodResolver builder) {
    incompleteMethods.add(builder);
  }

  public Collection<MethodResolver> getIncompleteMethods() {
    return incompleteMethods;
  }

  public MappedStatement getMappedStatement(String id) {
    return this.getMappedStatement(id, true);
  }

  public MappedStatement getMappedStatement(String id, boolean validateIncompleteStatements) {
    if (validateIncompleteStatements) {
      buildAllStatements();
    }
    return mappedStatements.get(id);
  }

  /**
   * 获取 sql 片段, <sql> 节点
   *
   * @return
   */
  public Map<String, XNode> getSqlFragments() {
    return sqlFragments;
  }

  /**
   * 添加插件(拦截器)
   *
   * @param interceptor
   */
  public void addInterceptor(Interceptor interceptor) {
    interceptorChain.addInterceptor(interceptor);
  }

  /**
   * 添加指定包下指定父类的所有 Mapper 接口
   *
   * @param packageName
   * @param superType
   */
  public void addMappers(String packageName, Class<?> superType) {
    mapperRegistry.addMappers(packageName, superType);
  }

  /**
   * 添加指定包下所有的 Mapper 接口
   *
   * @param packageName
   */
  public void addMappers(String packageName) {
    mapperRegistry.addMappers(packageName);
  }

  /**
   * 添加单个 Mapper 接口
   *
   * @param type
   * @param <T>
   */
  public <T> void addMapper(Class<T> type) {
    mapperRegistry.addMapper(type);
  }

  /**
   * 获取 Mapper 接口
   *
   * @param type
   * @param sqlSession
   * @param <T>
   * @return
   */
  public <T> T getMapper(Class<T> type, SqlSession sqlSession) {
    return mapperRegistry.getMapper(type, sqlSession);
  }

  /**
   * 是否存在 Mapper 接口
   *
   * @param type
   * @return
   */
  public boolean hasMapper(Class<?> type) {
    return mapperRegistry.hasMapper(type);
  }

  public boolean hasStatement(String statementName) {
    return hasStatement(statementName, true);
  }

  public boolean hasStatement(String statementName, boolean validateIncompleteStatements) {
    if (validateIncompleteStatements) {
      buildAllStatements();
    }
    return mappedStatements.containsKey(statementName);
  }

  public void addCacheRef(String namespace, String referencedNamespace) {
    cacheRefMap.put(namespace, referencedNamespace);
  }

  /*
   * Parses all the unprocessed statement nodes in the cache. It is recommended
   * to call this method once all the mappers are added as it provides fail-fast
   * statement validation.
   */
  protected void buildAllStatements() {
    parsePendingResultMaps();
    if (!incompleteCacheRefs.isEmpty()) {
      synchronized (incompleteCacheRefs) {
        incompleteCacheRefs.removeIf(x -> x.resolveCacheRef() != null);
      }
    }
    if (!incompleteStatements.isEmpty()) {
      synchronized (incompleteStatements) {
        incompleteStatements.removeIf(x -> {
          x.parseStatementNode();
          return true;
        });
      }
    }
    if (!incompleteMethods.isEmpty()) {
      synchronized (incompleteMethods) {
        incompleteMethods.removeIf(x -> {
          x.resolve();
          return true;
        });
      }
    }
  }

  private void parsePendingResultMaps() {
    if (incompleteResultMaps.isEmpty()) {
      return;
    }
    synchronized (incompleteResultMaps) {
      boolean resolved;
      IncompleteElementException ex = null;
      do {
        resolved = false;
        Iterator<ResultMapResolver> iterator = incompleteResultMaps.iterator();
        while (iterator.hasNext()) {
          try {
            iterator.next().resolve();
            iterator.remove();
            resolved = true;
          } catch (IncompleteElementException e) {
            ex = e;
          }
        }
      } while (resolved);
      if (!incompleteResultMaps.isEmpty() && ex != null) {
        // At least one result map is unresolvable.
        throw ex;
      }
    }
  }

  /**
   * Extracts namespace from fully qualified statement id.
   *
   * @param statementId
   *          the statement id
   * @return namespace or null when id does not contain period.
   */
  protected String extractNamespace(String statementId) {
    int lastPeriod = statementId.lastIndexOf('.');
    return lastPeriod > 0 ? statementId.substring(0, lastPeriod) : null;
  }

  // Slow but a one time cost. A better solution is welcome.
  protected void checkGloballyForDiscriminatedNestedResultMaps(ResultMap rm) {
    if (rm.hasNestedResultMaps()) {
      for (Map.Entry<String, ResultMap> entry : resultMaps.entrySet()) {
        Object value = entry.getValue();
        if (value instanceof ResultMap) {
          ResultMap entryResultMap = (ResultMap) value;
          if (!entryResultMap.hasNestedResultMaps() && entryResultMap.getDiscriminator() != null) {
            Collection<String> discriminatedResultMapNames = entryResultMap.getDiscriminator().getDiscriminatorMap().values();
            if (discriminatedResultMapNames.contains(rm.getId())) {
              entryResultMap.forceNestedResultMaps();
            }
          }
        }
      }
    }
  }

  // Slow but a one time cost. A better solution is welcome.
  protected void checkLocallyForDiscriminatedNestedResultMaps(ResultMap rm) {
    if (!rm.hasNestedResultMaps() && rm.getDiscriminator() != null) {
      for (Map.Entry<String, String> entry : rm.getDiscriminator().getDiscriminatorMap().entrySet()) {
        String discriminatedResultMapName = entry.getValue();
        if (hasResultMap(discriminatedResultMapName)) {
          ResultMap discriminatedResultMap = resultMaps.get(discriminatedResultMapName);
          if (discriminatedResultMap.hasNestedResultMaps()) {
            rm.forceNestedResultMaps();
            break;
          }
        }
      }
    }
  }

  /**
   * 严格的Map.
   *
   * 如果存在重复的key时将抛出异常,获取数据时不存在也会抛出异常
   *
   * @param <V>
   */
  protected static class StrictMap<V> extends HashMap<String, V> {

    private static final long serialVersionUID = -4950446264854982944L;
    private final String name;
    private BiFunction<V, V, String> conflictMessageProducer;

    public StrictMap(String name, int initialCapacity, float loadFactor) {
      super(initialCapacity, loadFactor);
      this.name = name;
    }

    public StrictMap(String name, int initialCapacity) {
      super(initialCapacity);
      this.name = name;
    }

    public StrictMap(String name) {
      super();
      this.name = name;
    }

    public StrictMap(String name, Map<String, ? extends V> m) {
      super(m);
      this.name = name;
    }

    /**
     * Assign a function for producing a conflict error message when contains value with the same key.
     * <p>
     * function arguments are 1st is saved value and 2nd is target value.
     * @param conflictMessageProducer A function for producing a conflict error message
     * @return a conflict error message
     * @since 3.5.0
     */
    public StrictMap<V> conflictMessageProducer(BiFunction<V, V, String> conflictMessageProducer) {
      this.conflictMessageProducer = conflictMessageProducer;
      return this;
    }

    /**
     * 当 key 存在 '.' 时会额外存一份
     *
     * @param key
     * @param value
     * @return
     */
    @Override
    @SuppressWarnings("unchecked")
    public V put(String key, V value) {
      // 包含 key 则抛出异常
      if (containsKey(key)) {
        throw new IllegalArgumentException(name + " already contains value for " + key
            + (conflictMessageProducer == null ? "" : conflictMessageProducer.apply(super.get(key), value)));
      }
      // key 存在 '.'
      if (key.contains(".")) {
        // key 按照 '.' 拆分并获取最后一项
        final String shortKey = getShortName(key);
        // 根据短的 key 再次获取, 不存在则放入.存在则放入 Ambiguity 对象
        if (super.get(shortKey) == null) {
          super.put(shortKey, value);
        } else {
          super.put(shortKey, (V) new Ambiguity(shortKey));
        }
      }
      // 正常放入
      return super.put(key, value);
    }

    @Override
    public V get(Object key) {
      V value = super.get(key);
      // 获取数据为空抛出异常
      if (value == null) {
        throw new IllegalArgumentException(name + " does not contain value for " + key);
      }
      // 如果数据类型为 Ambiguity 也将抛出异常
      if (value instanceof Ambiguity) {
        throw new IllegalArgumentException(((Ambiguity) value).getSubject() + " is ambiguous in " + name
            + " (try using the full name including the namespace, or rename one of the entries)");
      }
      return value;
    }

    protected static class Ambiguity {
      private final String subject;

      public Ambiguity(String subject) {
        this.subject = subject;
      }

      public String getSubject() {
        return subject;
      }
    }

    /**
     * 获取短的 key
     *
     * @param key
     * @return
     */
    private String getShortName(String key) {
      // 按照 '.' 拆分
      final String[] keyParts = key.split("\\.");
      // 获取最后一项
      return keyParts[keyParts.length - 1];
    }
  }

}
