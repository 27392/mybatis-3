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
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 * XMLConfigBuilder 是 BaseBuilder 的子类.
 *
 * 主要负责解析 mybatis-config.xml 配置文件
 *
 * @link {https://mybatis.org/mybatis-3/zh/configuration.html}
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

  // 是否解析过配置文件
  private boolean parsed;
  // xpath 对象
  private final XPathParser parser;
  // 环境的名称
  private String environment;
  // 反射器工厂
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  /**
   * 核心的构造函数 (其他的构造函数最终都会调用这个)
   *
   * @param parser
   * @param environment
   * @param props
   */
  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    // 创建 Configuration 对象并调用父类构造方法
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    // 将环境变量放到 Configuration 中
    this.configuration.setVariables(props);
    // 初始化解析标识
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  /**
   * 解析 mybatis-config.xml 配置文件
   *
   * @return
   */
  public Configuration parse() {
    // 判断是否已经对 mybatis-config.xml 完成了解析
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    // 将解析标识设置为 true
    parsed = true;
    // 在 mybatis-config.xml 中查找 <configuration> 节点, 并开始解析
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }

  /**
   * 解析 <configuration> 节点
   *
   * @param root
   */
  private void parseConfiguration(XNode root) {
    try {
      // issue #117 read properties first
      // 解析 <properties> 节点
      propertiesElement(root.evalNode("properties"));
      // 解析 <settings> 节点
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      loadCustomVfs(settings);      // 设置自定义的 VFS
      loadCustomLogImpl(settings);  // 设置自定义的 日志实现类
      // 解析 <typeAliases> 节点
      typeAliasesElement(root.evalNode("typeAliases"));
      // 解析 <plugins> 节点
      pluginElement(root.evalNode("plugins"));
      // 解析 <objectFactory> 节点
      objectFactoryElement(root.evalNode("objectFactory"));
      // 解析 <objectWrapperFactory> 节点
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      // 解析 <reflectorFactory> 节点
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      // 将从 <settings> 节点获取的配置, 设置到 Configuration 中
      settingsElement(settings);

      // read it after objectFactory and objectWrapperFactory issue #631
      // 解析 <environments> 节点
      environmentsElement(root.evalNode("environments"));
      // 解析 <databaseIdProvider> 节点
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      // 解析 <typeHandlers> 节点
      typeHandlerElement(root.evalNode("typeHandlers"));
      // 解析 <mappers> 节点
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  /**
   * 解析 <settings> 节点
   *
   * <settings>
   *   <setting name="logImpl" value="SLF4J"/>
   *   <setting name="logPrefix" value="mybatis_"/>
   * </settings>
   *
   * @link {https://mybatis.org/mybatis-3/zh/configuration.html#settings}
   * @param context
   * @return
   */
  private Properties settingsAsProperties(XNode context) {
    if (context == null) {
      return new Properties();
    }
    // 解析 <settings> 的子节点, (<setting>) 的 name 和 value 属性, 并记录到 Properties 中
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class
    // 检测 Configuration 中是否定义了对应配置的 setter 方法
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    for (Object key : props.keySet()) {
      // 使用 MetaClass 检测是否包含 setter 方法
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    // 返回解析完成的 Properties 对象
    return props;
  }

  /**
   * 加载自定义 vfs 实现类
   *
   * @param props
   * @throws ClassNotFoundException
   */
  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    // 获取 vfsImpl 属性对应的值
    String value = props.getProperty("vfsImpl");
    // 尝试加载实现类
    if (value != null) {
      String[] clazzes = value.split(",");
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          @SuppressWarnings("unchecked")
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          // 设置 vfs 实现类
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  /**
   * 加载自定义日志实现类
   *
   * @param props
   */
  private void loadCustomLogImpl(Properties props) {
    // 获取 logImpl 属性对应的值,
    Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
    // 设置日志实现类
    configuration.setLogImpl(logImpl);
  }

  /**
   * 解析 <typeAliases> 节点
   *
   * <typeAliases>
   *   <package name="domain.blog"/>
   *
   *   <typeAlias alias="Author" type="domain.blog.Author"/>
   *   <typeAlias alias="Blog" type="domain.blog.Blog"/>
   *   <typeAlias alias="Comment" type="domain.blog.Comment"/>
   *   <typeAlias alias="Post" type="domain.blog.Post"/>
   *   <typeAlias alias="Section" type="domain.blog.Section"/>
   *   <typeAlias alias="Tag" type="domain.blog.Tag"/>
   * </typeAliases>
   *
   * @link {https://mybatis.org/mybatis-3/zh/configuration.html#typeAliases}
   * @see #typeHandlerElement(XNode)
   * @param parent
   */
  private void typeAliasesElement(XNode parent) {
    if (parent != null) {
      // 获取 <typeAliases> 下的所有子节点. (<typeAlias> 与 <package>)
      for (XNode child : parent.getChildren()) {
        // 处理 <package> 节点
        if ("package".equals(child.getName())) {
          // 获取 name 属性, 也就是指定的包名
          String typeAliasPackage = child.getStringAttribute("name");
          // 使用 TypeAliasRegistry 对象扫码指定包中所有的类,并解析 @Alias 注解, 完成别名注册
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        } else {
          // 处理<typeAlias> 节点

          // 获取 alias 与 type 属性
          String alias = child.getStringAttribute("alias");
          String type = child.getStringAttribute("type");

          // 注册别名
          try {
            Class<?> clazz = Resources.classForName(type);
            if (alias == null) {
              typeAliasRegistry.registerAlias(clazz);
            } else {
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  /**
   * 解析 <plugins> 节点
   *
   * <plugins>
   *   <plugin interceptor="org.mybatis.example.ExamplePlugin">
   *     <property name="someProperty" value="100"/>
   *   </plugin>
   * </plugins>
   *
   * @link {https://mybatis.org/mybatis-3/zh/configuration.html#plugins}
   * @param parent
   * @throws Exception
   */
  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      // 获取 <plugins> 节点所有的子节点. 即(<plugin>)
      for (XNode child : parent.getChildren()) {
        // 获取 <plugin> 节点的 interceptor 属性值
        String interceptor = child.getStringAttribute("interceptor");
        // 获取 <plugin> 节点的 property 信息
        Properties properties = child.getChildrenAsProperties();
        // 通过别名获取对应的 class 对象, 并实例化对象
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).getDeclaredConstructor().newInstance();
        // 设置插件的配置信息. 调用 Interceptor 对象的 setProperties 方法
        interceptorInstance.setProperties(properties);
        // 将插件信息添加到 Configuration 对象中
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  /**
   * 解析 <objectFactory> 节点
   *
   * <objectFactory type="org.apache.ibatis.builder.ExampleObjectFactory">
   *   <property name="objectFactoryProperty" value="100"/>
   * </objectFactory>
   *
   * @see #objectWrapperFactoryElement(XNode)
   * @see #reflectorFactoryElement(XNode)
   * @param context
   * @throws Exception
   */
  private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 获取 type 属性
      String type = context.getStringAttribute("type");
      // 解析所有的 <property> 节点, 并将其转换成 Properties 对象
      Properties properties = context.getChildrenAsProperties();
      // 实例化 ObjectFactory 对象. 根据 type 属性解析对应的 Class 类型, 通过无参构造函数实例化
      ObjectFactory factory = (ObjectFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      // 设置属性. 调用 ObjectFactory 实例的 setProperties 方法
      factory.setProperties(properties);
      // 将 ObjectFactory 对象保存到 Configuration 中
      configuration.setObjectFactory(factory);
    }
  }

  /**
   * 解析 <objectWrapperFactory> 节点
   *
   * <objectWrapperFactory type="org.apache.ibatis.builder.CustomObjectWrapperFactory" />
   *
   * @see #objectFactoryElement(XNode)
   * @see #reflectorFactoryElement(XNode)
   * @param context
   * @throws Exception
   */
  private void objectWrapperFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 获取 type 属性
      String type = context.getStringAttribute("type");
      // 实例化 ObjectWrapperFactory 对象. 根据 type 属性解析对应的 Class 类型, 通过无参构造函数实例化
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      // 将 ObjectWrapperFactory 对象保存到 Configuration 中
      configuration.setObjectWrapperFactory(factory);
    }
  }

  /**
   * 解析 <reflectorFactory> 节点
   *
   * <reflectorFactory type="org.apache.ibatis.builder.CustomReflectorFactory"/>
   *
   * @see #objectWrapperFactoryElement(XNode)
   * @see #objectFactoryElement(XNode)
   * @param context
   * @throws Exception
   */
  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 获取 type 属性
      String type = context.getStringAttribute("type");
      // 实例化 ReflectorFactory 对象. 根据 type 属性解析对应的 Class 类型, 通过无参构造函数实例化
      ReflectorFactory factory = (ReflectorFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      // 将 ReflectorFactory 对象保存到 Configuration 中
      configuration.setReflectorFactory(factory);
    }
  }

  /**
   * 解析 <properties> 节点
   *
   * <properties resource="org/mybatis/example/config.properties", url="http://localhost/config.properties">
   *   <property name="username" value="dev_user"/>
   *   <property name="password" value="F2Fa3!33TYyg"/>
   * </properties>
   *
   * @link {https://mybatis.org/mybatis-3/zh/configuration.html#properties}
   * @param context
   * @throws Exception
   */
  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {
      // 解析 <properties> 的子节点, (<property>) 的 name 和 value 属性, 并记录到 Properties 中
      Properties defaults = context.getChildrenAsProperties();
      // 解析 resource 属性, 该属性可以设置类路径下的 properties 文件
      String resource = context.getStringAttribute("resource");
      // 解析 url 属性, 该属性可以设置远程网络中的 properties 文件
      String url = context.getStringAttribute("url");

      // resource 属性与 url 属性不能同时存在
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      // 加载 resource 或 url 中 properties 文件
      if (resource != null) {
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        defaults.putAll(Resources.getUrlAsProperties(url));
      }

      // 与 Configuration 对象中的环境变量进行合并
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars);
      }
      parser.setVariables(defaults);
      configuration.setVariables(defaults);
    }
  }

  /**
   * 将从 <settings> 节点获取的配置, 设置到 Configuration 中
   *
   * @link {https://mybatis.org/mybatis-3/zh/configuration.html#settings}
   * @param props
   */
  private void settingsElement(Properties props) {
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    configuration.setDefaultResultSetType(resolveResultSetType(props.getProperty("defaultResultSetType")));
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    configuration.setShrinkWhitespacesInSql(booleanValueOf(props.getProperty("shrinkWhitespacesInSql"), false));
    configuration.setDefaultSqlProviderType(resolveClass(props.getProperty("defaultSqlProviderType")));
    configuration.setNullableOnForEach(booleanValueOf(props.getProperty("nullableOnForEach"), false));
  }

  /**
   * 解析 <environments> 节点
   *  包含 <transactionManager> 节点, <dataSource> 节点
   *
   * <environments default="development">
   *   <environment id="development">
   *     <transactionManager type="JDBC">
   *       <property name="..." value="..."/>
   *     </transactionManager>
   *     <dataSource type="POOLED">
   *       <property name="driver" value="${driver}"/>
   *       <property name="url" value="${url}"/>
   *       <property name="username" value="${username}"/>
   *       <property name="password" value="${password}"/>
   *     </dataSource>
   *   </environment>
   * </environments>
   *
   * @link {https://mybatis.org/mybatis-3/zh/configuration.html#environments}
   * @param context
   * @throws Exception
   */
  private void environmentsElement(XNode context) throws Exception {
    if (context != null) {
      // 初始化时未指定 environment 属性, 则将值设置为 default
      if (environment == null) {
        environment = context.getStringAttribute("default");
      }

      // 获取所有的子节点 (<environment>)
      for (XNode child : context.getChildren()) {
        // 获取 <environment> 节点中的 id 属性
        String id = child.getStringAttribute("id");
        // 是否是 environment 字段中指定的环境
        if (isSpecifiedEnvironment(id)) {
          // 解析 <transactionManager> 节点, 并创建 TransactionFactory 对象
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          // 解析 <dataSource> 节点, 并创建 DataSourceFactory 对象
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          // 使用 DataSourceFactory 对象获取数据源对象
          DataSource dataSource = dsFactory.getDataSource();

          // 创建环境对象
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);
          // 将 Environment 对像保存到 Configuration 中
          configuration.setEnvironment(environmentBuilder.build());
          break;
        }
      }
    }
  }

  /**
   * 解析 <databaseIdProvider> 节点
   *
   * <databaseIdProvider type="DB_VENDOR">
   *   <property name="Apache Derby" value="derby"/>
   * </databaseIdProvider>
   *
   * @link {https://mybatis.org/mybatis-3/zh/configuration.html#databaseIdProvider}
   * @param context
   * @throws Exception
   */
  private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      // 获取 type 属性
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      // 保证兼容性问题
      if ("VENDOR".equals(type)) {
        type = "DB_VENDOR";
      }
      // 解析所有的 <property> 节点, 并将其转换成 Properties 对象
      Properties properties = context.getChildrenAsProperties();
      // 实例化 DatabaseIdProvider 对象. 根据 type 属性解析对应的 Class 类型, 通过无参构造函数实例化
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).getDeclaredConstructor().newInstance();
      // 设置属性. 调用 DatabaseIdProvider 实例的 setProperties 方法
      databaseIdProvider.setProperties(properties);
    }
    // 获取环境信息 Environment
    Environment environment = configuration.getEnvironment();
    if (environment != null && databaseIdProvider != null) {
      // 通过数据源对象获取到数据库名称, 通过数据库名称在属性中获取对应的值
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      // 将数据库id 保存到 Configuration 中
      configuration.setDatabaseId(databaseId);
    }
  }

  /**
   * 解析 <transactionManager> 节点
   *
   * <transactionManager type="JDBC">
   *   <property name="" value=""/>
   * </transactionManager>
   *
   * @see #dataSourceElement(XNode)
   * @param context
   * @return
   * @throws Exception
   */
  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      // 获取 type 属性
      String type = context.getStringAttribute("type");
      // 解析所有的 <property> 节点, 并将其转换成 Properties 对象
      Properties props = context.getChildrenAsProperties();
      // 实例化 TransactionFactory 对象. 根据 type 属性解析对应的 Class 类型, 通过无参构造函数实例化
      TransactionFactory factory = (TransactionFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      // 设置属性. 调用 DataSourceFactory 实例的 setProperties 方法
      factory.setProperties(props);

      // 返回 TransactionFactory 对象
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  /**
   * 解析 <dataSource> 节点
   *
   * <dataSource type="UNPOOLED">
   *   <property name="driver" value="${driver}"/>
   *   <property name="url" value="${url}"/>
   *   <property name="username" value="${username}"/>
   *   <property name="password" value="${password}"/>
   * </dataSource>
   *
   * @see #transactionManagerElement(XNode)
   * @param context
   * @return
   * @throws Exception
   */
  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      // 获取 type 属性
      String type = context.getStringAttribute("type");
      // 解析所有的 <property> 节点, 并将其转换成 Properties 对象
      Properties props = context.getChildrenAsProperties();
      // 实例化 DataSourceFactory 对象. 根据 type 属性解析对应的 Class 类型, 通过无参构造函数实例化
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      // 设置属性. 调用 DataSourceFactory 实例的 setProperties 方法
      factory.setProperties(props);

      // 返回 DataSourceFactory 对象
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  /**
   * 解析 <typeHandlers> 节点
   *
   * <typeHandlers>
   *   <package name="org.mybatis.example"/>
   *
   *   <typeHandler handler="org.mybatis.example.ExampleTypeHandler"/>
   * </typeHandlers>
   *
   * @link {https://mybatis.org/mybatis-3/zh/configuration.html#typeHandlers}
   * @see #typeAliasesElement(XNode)
   * @param parent
   */
  private void typeHandlerElement(XNode parent) {
    if (parent != null) {
      // 获取 <typeHandlers> 下的所有子节点. (主要包含 <typeAlias> 与 <package>)
      for (XNode child : parent.getChildren()) {
        // 处理 <package> 节点
        if ("package".equals(child.getName())) {
          // 获取 name 属性, 也就是指定的包名
          String typeHandlerPackage = child.getStringAttribute("name");
          // 使用 TypeAliasRegistry 对象扫码指定包中所有的类,并解析 @Alias 注解, 完成别名注册
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          // 处理<typeHandler> 节点

          // 获取 javaType, jdbcType, handler 属性. 并将其转换成对应的类
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          String handlerTypeName = child.getStringAttribute("handler");
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);

          // 注册类型处理器
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  /**
   * 解析 <mappers> 节点
   *
   * <mappers>
   *   <mapper resource="org/mybatis/builder/AuthorMapper.xml"/>
   *   <mapper url="file:///var/mappers/AuthorMapper.xml"/>
   *   <mapper class="org.mybatis.builder.BlogMapper"/>
   *   <package name="org.mybatis.builder"/>
   * </mappers>
   *
   * @link {https://mybatis.org/mybatis-3/zh/configuration.html#mappers}
   * @param parent
   * @throws Exception
   */
  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      // 获取所有的子节点 (<mapper> 与 <package>),并遍历
      for (XNode child : parent.getChildren()) {
        // 处理 <package> 节点
        if ("package".equals(child.getName())) {
          // 获取 name 属性. 包名
          String mapperPackage = child.getStringAttribute("name");
          // 使用 MapperRegistry 扫描包并注册 Mapper 接口
          configuration.addMappers(mapperPackage);
        } else {
          // 处理 <mapper> 节点

          // 获取 resource, url, class 属性
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");

          // 处理 resource
          if (resource != null && url == null && mapperClass == null) {
            ErrorContext.instance().resource(resource);
            // 读取文件流, 使用 resource 属性的值
            try(InputStream inputStream = Resources.getResourceAsStream(resource)) {
              // 创建 XMLMapperBuilder 对象, 使用文件流信息
              XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
              // 解析 Mapper.xml 文件
              mapperParser.parse();
            }
          } else if (resource == null && url != null && mapperClass == null) {
            // 处理 url

            ErrorContext.instance().resource(url);
            // 读取文件流, 使用 url 属性的值
            try(InputStream inputStream = Resources.getUrlAsStream(url)){
              // 创建 XMLMapperBuilder 对象, 使用文件流信息
              XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
              // 解析 Mapper.xml 文件
              mapperParser.parse();
            }
          } else if (resource == null && url == null && mapperClass != null) {
            // 处理 class

            // 解析 Class 对象, 使用 class 属性的值
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            // 注册 Mapper 接口. 使用 MapperRegistry 对象
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  /**
   * 环境是否与指定的环境一致
   *
   * @param id
   * @return
   */
  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    }
    if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    }
    return environment.equals(id);
  }

}
