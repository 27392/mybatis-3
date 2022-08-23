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
package org.apache.ibatis.binding;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

/**
 * Mapper 方法
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 * @author Kazuki Shimizu
 */
public class MapperMethod {

  // Sql语句对象 (包含Sql语句的id与Sql语句的类型)
  private final SqlCommand command;

  // 方法签名对象
  private final MethodSignature method;

  /**
   * 构造函数初始化 command 与 method 字段
   *
   * @param mapperInterface
   * @param method
   * @param config
   */
  public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
    this.command = new SqlCommand(config, mapperInterface, method);
    this.method = new MethodSignature(config, mapperInterface, method);
  }

  /**
   * 执行SQL
   *
   * @param sqlSession
   * @param args
   * @return
   */
  public Object execute(SqlSession sqlSession, Object[] args) {
    Object result;
    // 根据SQL语句的类型判断处理逻辑
    switch (command.getType()) {
      // 插入
      case INSERT: {
        Object param = method.convertArgsToSqlCommandParam(args);
        // 处理影响行数
        result = rowCountResult(sqlSession.insert(command.getName(), param));
        break;
      }
      // 更新
      case UPDATE: {
        Object param = method.convertArgsToSqlCommandParam(args);
        // 处理影响行数
        result = rowCountResult(sqlSession.update(command.getName(), param));
        break;
      }
      // 删除
      case DELETE: {
        Object param = method.convertArgsToSqlCommandParam(args);
        // 处理影响行数
        result = rowCountResult(sqlSession.delete(command.getName(), param));
        break;
      }
      // 查询
      case SELECT:
        // 如果返回 void 并且存在结果处理器
        if (method.returnsVoid() && method.hasResultHandler()) {
          // 执行查询,使用结果处理器自定义处理
          executeWithResultHandler(sqlSession, args);
          result = null;
        } else if (method.returnsMany()) {
          // 执行查询,结果为多个
          result = executeForMany(sqlSession, args);
        } else if (method.returnsMap()) {
          // 执行查询,结果为 Map
          result = executeForMap(sqlSession, args);
        } else if (method.returnsCursor()) {
          // 执行查询,结果为游标
          result = executeForCursor(sqlSession, args);
        } else {
          // 执行查询,结果为单个
          Object param = method.convertArgsToSqlCommandParam(args);
          result = sqlSession.selectOne(command.getName(), param);
          // 方法返回值为 Optional 的处理
          if (method.returnsOptional()
              && (result == null || !method.getReturnType().equals(result.getClass()))) {
            result = Optional.ofNullable(result);
          }
        }
        break;
        // 刷新批处理缓存
      case FLUSH:
        // 调用 sqlSession.flushStatements 方法
        result = sqlSession.flushStatements();
        break;
      default:
        throw new BindingException("Unknown execution method for: " + command.getName());
    }
    if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
      throw new BindingException("Mapper method '" + command.getName()
          + " attempted to return null from a method with a primitive return type (" + method.getReturnType() + ").");
    }
    return result;
  }

  /**
   * 返回影响行数
   *
   * @param rowCount
   * @return
   */
  private Object rowCountResult(int rowCount) {
    final Object result;
    // 如果返回值空
    if (method.returnsVoid()) {
      result = null;
    } else if (Integer.class.equals(method.getReturnType()) || Integer.TYPE.equals(method.getReturnType())) {
      // 类型转换
      result = rowCount;
    } else if (Long.class.equals(method.getReturnType()) || Long.TYPE.equals(method.getReturnType())) {
      // 类型转换
      result = (long) rowCount;
    } else if (Boolean.class.equals(method.getReturnType()) || Boolean.TYPE.equals(method.getReturnType())) {
      // 返回 boolean , 只要大于 0 即返回 true
      result = rowCount > 0;
    } else {
      // 其它类型抛异常
      throw new BindingException("Mapper method '" + command.getName() + "' has an unsupported return type: " + method.getReturnType());
    }
    return result;
  }

  /**
   * 执行查询使用自定义 ResultHandler 处理器
   *
   * @param sqlSession
   * @param args
   */
  private void executeWithResultHandler(SqlSession sqlSession, Object[] args) {
    // 获取 MappedStatement 对象
    MappedStatement ms = sqlSession.getConfiguration().getMappedStatement(command.getName());
    if (!StatementType.CALLABLE.equals(ms.getStatementType())
        && void.class.equals(ms.getResultMaps().get(0).getType())) {
      throw new BindingException("method " + command.getName()
          + " needs either a @ResultMap annotation, a @ResultType annotation,"
          + " or a resultType attribute in XML so a ResultHandler can be used as a parameter.");
    }
    // 将参数转换成 Sql命令参数. #{}
    Object param = method.convertArgsToSqlCommandParam(args);
    // 执行查询
    if (method.hasRowBounds()) {
      // 带分页参数
      RowBounds rowBounds = method.extractRowBounds(args);
      sqlSession.select(command.getName(), param, rowBounds, method.extractResultHandler(args));
    } else {
      // 不带分页参数
      sqlSession.select(command.getName(), param, method.extractResultHandler(args));
    }
  }

  /**
   * 执行查询,结果为多个
   * @param sqlSession
   * @param args
   * @param <E>
   * @return
   */
  private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
    List<E> result;
    // 将参数转换成 Sql命令参数. #{}
    Object param = method.convertArgsToSqlCommandParam(args);
    // 执行查询
    if (method.hasRowBounds()) {
      // 带分页参数
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectList(command.getName(), param, rowBounds);
    } else {
      // 不带分页参数
      result = sqlSession.selectList(command.getName(), param);
    }
    // issue #510 Collections & arrays support
    // 方法返回值类型不是 List 的子类
    if (!method.getReturnType().isAssignableFrom(result.getClass())) {
      // 方法类型是数组类型, 将List中的数据组装到数组中
      if (method.getReturnType().isArray()) {
        // list 转 array
        return convertToArray(result);
      } else {
        // 如果是 Set 等类型, 将其转换成 Set
        return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
      }
    }
    return result;
  }

  /**
   * 执行查询,结果为游标
   *
   * @param sqlSession
   * @param args
   * @param <T>
   * @return
   */
  private <T> Cursor<T> executeForCursor(SqlSession sqlSession, Object[] args) {
    Cursor<T> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectCursor(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.selectCursor(command.getName(), param);
    }
    return result;
  }

  /**
   * 将 List 转换成 Set
   *
   * @param config
   * @param list
   * @param <E>
   * @return
   */
  private <E> Object convertToDeclaredCollection(Configuration config, List<E> list) {
    Object collection = config.getObjectFactory().create(method.getReturnType());
    MetaObject metaObject = config.newMetaObject(collection);
    metaObject.addAll(list);
    return collection;
  }

  /**
   * 将 List 转换成 数组
   * @param list
   * @param <E>
   * @return
   */
  @SuppressWarnings("unchecked")
  private <E> Object convertToArray(List<E> list) {
    Class<?> arrayComponentType = method.getReturnType().getComponentType();
    Object array = Array.newInstance(arrayComponentType, list.size());
    if (arrayComponentType.isPrimitive()) {
      for (int i = 0; i < list.size(); i++) {
        Array.set(array, i, list.get(i));
      }
      return array;
    } else {
      return list.toArray((E[]) array);
    }
  }

  /**
   * 执行查询,结果为 Map
   *
   * @param sqlSession
   * @param args
   * @param <K>
   * @param <V>
   * @return
   */
  private <K, V> Map<K, V> executeForMap(SqlSession sqlSession, Object[] args) {
    Map<K, V> result;
    // 将参数转换成 Sql命令参数. #{}
    Object param = method.convertArgsToSqlCommandParam(args);
    // 执行查询
    if (method.hasRowBounds()) {
      // 包含分页参数
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectMap(command.getName(), param, method.getMapKey(), rowBounds);
    } else {
      // 不包含分页参数
      result = sqlSession.selectMap(command.getName(), param, method.getMapKey());
    }
    return result;
  }

  public static class ParamMap<V> extends HashMap<String, V> {

    private static final long serialVersionUID = -2212268410512043556L;

    @Override
    public V get(Object key) {
      if (!super.containsKey(key)) {
        throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + keySet());
      }
      return super.get(key);
    }

  }

  public static class SqlCommand {

    // Sql语句的id
    private final String name;
    // Sql语句执行类型
    private final SqlCommandType type;

    public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {
      // 获取方法名
      final String methodName = method.getName();
      // 获取方法定义的类
      final Class<?> declaringClass = method.getDeclaringClass();
      // 获取 MappedStatement 对象, 根据(接口,方法名,方法定义的类)
      MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass,
          configuration);
      // MappedStatement 对象为空
      if (ms == null) {
        // 查询该方法是否是存在 @Flush 注解. (在批操作时使用这个方法清除（执行）缓存语句)
        if (method.getAnnotation(Flush.class) != null) {
          name = null;
          type = SqlCommandType.FLUSH;
        } else {
          throw new BindingException("Invalid bound statement (not found): "
              + mapperInterface.getName() + "." + methodName);
        }
      } else {
        // 获取id 与类型
        name = ms.getId();
        type = ms.getSqlCommandType();
        if (type == SqlCommandType.UNKNOWN) {
          throw new BindingException("Unknown execution method for: " + name);
        }
      }
    }

    public String getName() {
      return name;
    }

    public SqlCommandType getType() {
      return type;
    }

    /**
     * 解析 MappedStatement
     *
     * @param mapperInterface
     * @param methodName
     * @param declaringClass
     * @param configuration
     * @return
     */
    private MappedStatement resolveMappedStatement(Class<?> mapperInterface, String methodName,
        Class<?> declaringClass, Configuration configuration) {
      // 使用接口名称 + "." + "方法名称"拼接出 statementId
      String statementId = mapperInterface.getName() + "." + methodName;
      // 根据 statementId 判断是否包含 MappedStatement 对象
      if (configuration.hasStatement(statementId)) {
        return configuration.getMappedStatement(statementId);
      } else if (mapperInterface.equals(declaringClass)) {
        // 不存在 MappedStatement 对象, 且接口是方法的声明类. 返回null
        return null;
      }
      // 遍历Mapper接口的所有接口
      for (Class<?> superInterface : mapperInterface.getInterfaces()) {
        if (declaringClass.isAssignableFrom(superInterface)) {
          // 递归获取
          MappedStatement ms = resolveMappedStatement(superInterface, methodName,
              declaringClass, configuration);
          if (ms != null) {
            return ms;
          }
        }
      }
      return null;
    }
  }

  public static class MethodSignature {

    // 返回值类型是否为 Collection类型或是数组类型
    private final boolean returnsMany;
    // 返回值类型是否为 Map 类型
    private final boolean returnsMap;
    // 返回值类型是否为 void
    private final boolean returnsVoid;
    // 返回值类型是否为 Cursor
    private final boolean returnsCursor;
    // 返回值类型是否为 Optional
    private final boolean returnsOptional;
    // 返回值类型
    private final Class<?> returnType;
    // @MapKey注解中的 value
    private final String mapKey;
    // ResultHandler 类型在方法参数中的下标
    private final Integer resultHandlerIndex;
    // RowBounds 类型在方法参数中的下标
    private final Integer rowBoundsIndex;
    // 参数名称解析器对象
    private final ParamNameResolver paramNameResolver;

    /**
     * 构造函数
     * @param configuration
     * @param mapperInterface
     * @param method
     */
    public MethodSignature(Configuration configuration, Class<?> mapperInterface, Method method) {
      // 解析返回值类型
      Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, mapperInterface);
      // 如果是 class 则直接使用, 如果是参数类型则获取原始值,否则使用 Method.returnType
      if (resolvedReturnType instanceof Class<?>) {
        this.returnType = (Class<?>) resolvedReturnType;
      } else if (resolvedReturnType instanceof ParameterizedType) {
        this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();
      } else {
        this.returnType = method.getReturnType();
      }
      this.returnsVoid = void.class.equals(this.returnType);
      this.returnsMany = configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray();
      this.returnsCursor = Cursor.class.equals(this.returnType);
      this.returnsOptional = Optional.class.equals(this.returnType);
      // 获取 @MapKey 注解的值
      this.mapKey = getMapKey(method);
      this.returnsMap = this.mapKey != null;
      // 获取 RowBounds 分页对象的在方法参数中的下标
      this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);
      // 获取 ResultHandler 对象的在方法参数中的下标
      this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);
      // 参数名称解析器
      this.paramNameResolver = new ParamNameResolver(configuration, method);
    }

    /**
     * 将参数(用户请求的参数)转换成SQL语句对应的参数列表
     *
     * @param args
     * @return
     */
    public Object convertArgsToSqlCommandParam(Object[] args) {
      return paramNameResolver.getNamedParams(args);
    }

    public boolean hasRowBounds() {
      return rowBoundsIndex != null;
    }

    public RowBounds extractRowBounds(Object[] args) {
      return hasRowBounds() ? (RowBounds) args[rowBoundsIndex] : null;
    }

    public boolean hasResultHandler() {
      return resultHandlerIndex != null;
    }

    public ResultHandler extractResultHandler(Object[] args) {
      return hasResultHandler() ? (ResultHandler) args[resultHandlerIndex] : null;
    }

    public Class<?> getReturnType() {
      return returnType;
    }

    public boolean returnsMany() {
      return returnsMany;
    }

    public boolean returnsMap() {
      return returnsMap;
    }

    public boolean returnsVoid() {
      return returnsVoid;
    }

    public boolean returnsCursor() {
      return returnsCursor;
    }

    /**
     * return whether return type is {@code java.util.Optional}.
     *
     * @return return {@code true}, if return type is {@code java.util.Optional}
     * @since 3.5.0
     */
    public boolean returnsOptional() {
      return returnsOptional;
    }

    private Integer getUniqueParamIndex(Method method, Class<?> paramType) {
      Integer index = null;
      final Class<?>[] argTypes = method.getParameterTypes();
      for (int i = 0; i < argTypes.length; i++) {
        if (paramType.isAssignableFrom(argTypes[i])) {
          if (index == null) {
            index = i;
          } else {
            throw new BindingException(method.getName() + " cannot have multiple " + paramType.getSimpleName() + " parameters");
          }
        }
      }
      return index;
    }

    public String getMapKey() {
      return mapKey;
    }

    private String getMapKey(Method method) {
      String mapKey = null;
      if (Map.class.isAssignableFrom(method.getReturnType())) {
        final MapKey mapKeyAnnotation = method.getAnnotation(MapKey.class);
        if (mapKeyAnnotation != null) {
          mapKey = mapKeyAnnotation.value();
        }
      }
      return mapKey;
    }
  }

}
