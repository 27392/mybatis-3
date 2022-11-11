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
package org.apache.ibatis.executor.keygen;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.ArrayUtil;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.defaults.DefaultSqlSession.StrictMap;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.apache.ibatis.util.MapUtil;

/**
 * Jdbc3KeyGenerator 用于获取插入数据后的自增长列数据
 *
 *  - 将获取到自增长列数据设置到 keyProperty 中
 *
 * <insert id="insertUndefineKeyProperty" useGeneratedKeys="true" keyProperty="country_id">
 *   insert into country (countryname,countrycode) values (#{countryname},#{countrycode})
 * </insert>
 *
 * @see Configuration#isUseGeneratedKeys()
 *
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class Jdbc3KeyGenerator implements KeyGenerator {

  private static final String SECOND_GENERIC_PARAM_NAME = ParamNameResolver.GENERIC_NAME_PREFIX + "2";

  /**
   * A shared instance.
   *
   * @since 3.4.3
   */
  public static final Jdbc3KeyGenerator INSTANCE = new Jdbc3KeyGenerator();

  private static final String MSG_TOO_MANY_KEYS = "Too many keys are generated. There are only %d target objects. "
      + "You either specified a wrong 'keyProperty' or encountered a driver bug like #1523.";

  @Override
  public void processBefore(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
    // do nothing
  }

  @Override
  public void processAfter(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
    processBatch(ms, stmt, parameter);
  }

  public void processBatch(MappedStatement ms, Statement stmt, Object parameter) {
    // 获取主键字段 (keyProperty 属性)
    final String[] keyProperties = ms.getKeyProperties();
    if (keyProperties == null || keyProperties.length == 0) {
      return;
    }
    // 获取自动生成键的列
    try (ResultSet rs = stmt.getGeneratedKeys()) {
      final ResultSetMetaData rsmd = rs.getMetaData();
      final Configuration configuration = ms.getConfiguration();

      // 当自动生成键的列数少于我们指定的属性数时则不作任何处理
      if (rsmd.getColumnCount() < keyProperties.length) {
        // Error?
      } else {
        // 处理自动生成键的列
        assignKeys(configuration, rs, rsmd, keyProperties, parameter);
      }
    } catch (Exception e) {
      throw new ExecutorException("Error getting generated key or setting result to parameter object. Cause: " + e, e);
    }
  }

  /**
   * 处理自动生成键的列
   *
   * @param configuration
   * @param rs
   * @param rsmd
   * @param keyProperties
   * @param parameter
   * @throws SQLException
   */
  @SuppressWarnings("unchecked")
  private void assignKeys(Configuration configuration, ResultSet rs, ResultSetMetaData rsmd, String[] keyProperties,
      Object parameter) throws SQLException {
    if (parameter instanceof ParamMap || parameter instanceof StrictMap) {
      // 用户参数为 ParamMap 的情况
      // Multi-param or single param with @Param
      assignKeysToParamMap(configuration, rs, rsmd, keyProperties, (Map<String, ?>) parameter);
    } else if (parameter instanceof ArrayList && !((ArrayList<?>) parameter).isEmpty()
        && ((ArrayList<?>) parameter).get(0) instanceof ParamMap) {
      // 用户参数为 ArrayList<ParamMap> 的情况
      // Multi-param or single param with @Param in batch operation
      assignKeysToParamMapList(configuration, rs, rsmd, keyProperties, (ArrayList<ParamMap<?>>) parameter);
    } else {
      // 用户参数是单个对象的情况
      // Single param without @Param
      assignKeysToParam(configuration, rs, rsmd, keyProperties, parameter);
    }
  }

  /**
   * 用户参数是单个对象的情况
   *
   * @param configuration
   * @param rs
   * @param rsmd
   * @param keyProperties
   * @param parameter
   * @throws SQLException
   */
  private void assignKeysToParam(Configuration configuration, ResultSet rs, ResultSetMetaData rsmd,
      String[] keyProperties, Object parameter) throws SQLException {
    // 将参数转换成集合
    Collection<?> params = collectionize(parameter);
    if (params.isEmpty()) {
      return;
    }
    // 遍历属性列表并创建 KeyAssigner 集合
    List<KeyAssigner> assignerList = new ArrayList<>();
    for (int i = 0; i < keyProperties.length; i++) {
      assignerList.add(new KeyAssigner(configuration, rsmd, i + 1, null, keyProperties[i]));
    }
    // 遍历参数
    Iterator<?> iterator = params.iterator();
    while (rs.next()) {
      if (!iterator.hasNext()) {
        throw new ExecutorException(String.format(MSG_TOO_MANY_KEYS, params.size()));
      }
      Object param = iterator.next();
      assignerList.forEach(x -> x.assign(rs, param));
    }
  }

  /**
   * 用户参数为 ArrayList<ParamMap> 的情况
   *
   * @param configuration
   * @param rs
   * @param rsmd
   * @param keyProperties
   * @param paramMapList
   * @throws SQLException
   */
  private void assignKeysToParamMapList(Configuration configuration, ResultSet rs, ResultSetMetaData rsmd,
      String[] keyProperties, ArrayList<ParamMap<?>> paramMapList) throws SQLException {
    Iterator<ParamMap<?>> iterator = paramMapList.iterator();
    List<KeyAssigner> assignerList = new ArrayList<>();
    long counter = 0;
    while (rs.next()) {
      if (!iterator.hasNext()) {
        throw new ExecutorException(String.format(MSG_TOO_MANY_KEYS, counter));
      }
      ParamMap<?> paramMap = iterator.next();
      if (assignerList.isEmpty()) {
        for (int i = 0; i < keyProperties.length; i++) {
          assignerList
              .add(getAssignerForParamMap(configuration, rsmd, i + 1, paramMap, keyProperties[i], keyProperties, false)
                  .getValue());
        }
      }
      assignerList.forEach(x -> x.assign(rs, paramMap));
      counter++;
    }
  }

  /**
   * 用户参数为 ParamMap 的情况
   *
   * @param configuration
   * @param rs
   * @param rsmd
   * @param keyProperties
   * @param paramMap
   * @throws SQLException
   */
  private void assignKeysToParamMap(Configuration configuration, ResultSet rs, ResultSetMetaData rsmd,
      String[] keyProperties, Map<String, ?> paramMap) throws SQLException {
    if (paramMap.isEmpty()) {
      return;
    }
    // key: 属性, value: (key: 参数, value: 属性赋值器集合)
    Map<String, Entry<Iterator<?>, List<KeyAssigner>>> assignerMap = new HashMap<>();

    // 遍历属性
    for (int i = 0; i < keyProperties.length; i++) {
      // 创建属性映射器(key: 属性名, value: 属性映射器)
      Entry<String, KeyAssigner> entry = getAssignerForParamMap(configuration, rsmd, i + 1, paramMap, keyProperties[i],
          keyProperties, true);
      // 属性不存在创建初始化属性赋值器集合
      Entry<Iterator<?>, List<KeyAssigner>> iteratorPair = MapUtil.computeIfAbsent(assignerMap, entry.getKey(),
          k -> MapUtil.entry(collectionize(paramMap.get(k)).iterator(), new ArrayList<>()));
      // 将属性赋值器添加到集合中
      iteratorPair.getValue().add(entry.getValue());
    }
    long counter = 0;
    // 遍历
    while (rs.next()) {
      for (Entry<Iterator<?>, List<KeyAssigner>> pair : assignerMap.values()) {
        if (!pair.getKey().hasNext()) {
          throw new ExecutorException(String.format(MSG_TOO_MANY_KEYS, counter));
        }
        Object param = pair.getKey().next();
        pair.getValue().forEach(x -> x.assign(rs, param));
      }
      counter++;
    }
  }

  /**
   * 创建属性赋值器
   *
   * 1. 是一个参数并且是属性不包含"."
   * 2. 属性包含".",且"."前面的属性在参数中存在
   * 3. 是单属性
   *
   * @param config
   * @param rsmd
   * @param columnPosition
   * @param paramMap
   * @param keyProperty
   * @param keyProperties
   * @param omitParamName
   * @return
   */
  private Entry<String, KeyAssigner> getAssignerForParamMap(Configuration config, ResultSetMetaData rsmd,
      int columnPosition, Map<String, ?> paramMap, String keyProperty, String[] keyProperties, boolean omitParamName) {
    // 获取属性名称
    Set<String> keySet = paramMap.keySet();

    // 是否是一个参数
    // A caveat : if the only parameter has {@code @Param("param2")} on it,
    // it must be referenced with param name e.g. 'param2.x'.
    boolean singleParam = !keySet.contains(SECOND_GENERIC_PARAM_NAME);
    int firstDot = keyProperty.indexOf('.');

    // 场景1: 单属性并且是一个参数
    if (firstDot == -1) {
      if (singleParam) {
        return getAssignerForSingleParam(config, rsmd, columnPosition, paramMap, keyProperty, omitParamName);
      }
      throw new ExecutorException("Could not determine which parameter to assign generated keys to. "
          + "Note that when there are multiple parameters, 'keyProperty' must include the parameter name (e.g. 'param.id'). "
          + "Specified key properties are " + ArrayUtil.toString(keyProperties) + " and available parameters are "
          + keySet);
    }
    // 获取"."前面的属性名
    String paramName = keyProperty.substring(0, firstDot);

    // 场景2: 第一个属性在参数中存在
    if (keySet.contains(paramName)) {
      // 参数名
      String argParamName = omitParamName ? null : paramName;
      // 获取属性"."后面的属性
      String argKeyProperty = keyProperty.substring(firstDot + 1);
      // 创建属性赋值器
      return MapUtil.entry(paramName, new KeyAssigner(config, rsmd, columnPosition, argParamName, argKeyProperty));
    } else if (singleParam) {
      // 场景3: 是单属性
      return getAssignerForSingleParam(config, rsmd, columnPosition, paramMap, keyProperty, omitParamName);
    } else {
      throw new ExecutorException("Could not find parameter '" + paramName + "'. "
          + "Note that when there are multiple parameters, 'keyProperty' must include the parameter name (e.g. 'param.id'). "
          + "Specified key properties are " + ArrayUtil.toString(keyProperties) + " and available parameters are "
          + keySet);
    }
  }

  /**
   * 获取单个参数的赋值器
   *
   * @param config
   * @param rsmd
   * @param columnPosition
   * @param paramMap
   * @param keyProperty
   * @param omitParamName
   * @return
   */
  private Entry<String, KeyAssigner> getAssignerForSingleParam(Configuration config, ResultSetMetaData rsmd,
      int columnPosition, Map<String, ?> paramMap, String keyProperty, boolean omitParamName) {
    // Assume 'keyProperty' to be a property of the single param.
    // 首个属性的名称
    String singleParamName = nameOfSingleParam(paramMap);
    String argParamName = omitParamName ? null : singleParamName;
    return MapUtil.entry(singleParamName, new KeyAssigner(config, rsmd, columnPosition, argParamName, keyProperty));
  }

  /**
   * 获取第一个属性的名称
   *
   * @param paramMap
   * @return
   */
  private static String nameOfSingleParam(Map<String, ?> paramMap) {
    // There is virtually one parameter, so any key works.
    return paramMap.keySet().iterator().next();
  }

  /**
   * 将用户参数转换成集合
   *
   * @param param
   * @return
   */
  private static Collection<?> collectionize(Object param) {
    if (param instanceof Collection) {
      return (Collection<?>) param;
    } else if (param instanceof Object[]) {
      return Arrays.asList((Object[]) param);
    } else {
      return Arrays.asList(param);
    }
  }

  /**
   * 属性赋值器
   */
  private class KeyAssigner {
    // 配置对象
    private final Configuration configuration;
    // ResultSetMetaData 对象
    private final ResultSetMetaData rsmd;
    // TypeHandlerRegistry 对象; 用于获取 TypeHandler
    private final TypeHandlerRegistry typeHandlerRegistry;
    // 列位置
    private final int columnPosition;
    // 参数名
    private final String paramName;
    // 用户参数的属性名
    private final String propertyName;
    // TypeHandler 对象; 用于获取结果集中的值
    private TypeHandler<?> typeHandler;

    protected KeyAssigner(Configuration configuration, ResultSetMetaData rsmd, int columnPosition, String paramName,
        String propertyName) {
      super();
      this.configuration = configuration;
      this.rsmd = rsmd;
      this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
      this.columnPosition = columnPosition;
      this.paramName = paramName;
      this.propertyName = propertyName;
    }

    /**
     * 赋值
     *
     * @param rs
     * @param param
     */
    protected void assign(ResultSet rs, Object param) {
      if (paramName != null) {
        // If paramName is set, param is ParamMap
        param = ((ParamMap<?>) param).get(paramName);
      }
      // 创建参数对应的 MetaObject 对象
      MetaObject metaParam = configuration.newMetaObject(param);
      try {
        if (typeHandler == null) {
          // 根据属性对应的类型查找对应的 TypeHandler 对象
          if (metaParam.hasSetter(propertyName)) {
            Class<?> propertyType = metaParam.getSetterType(propertyName);
            typeHandler = typeHandlerRegistry.getTypeHandler(propertyType,
                JdbcType.forCode(rsmd.getColumnType(columnPosition)));
          } else {
            throw new ExecutorException("No setter found for the keyProperty '" + propertyName + "' in '"
                + metaParam.getOriginalObject().getClass().getName() + "'.");
          }
        }
        if (typeHandler == null) {
          // Error?
        } else {
          // 使用 TypeHandler 获取属性值
          Object value = typeHandler.getResult(rs, columnPosition);
          // 将值设置回对象中
          metaParam.setValue(propertyName, value);
        }
      } catch (SQLException e) {
        throw new ExecutorException("Error getting generated key or setting result to parameter object. Cause: " + e,
            e);
      }
    }
  }
}
