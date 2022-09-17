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

import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

import ognl.OgnlContext;
import ognl.OgnlRuntime;
import ognl.PropertyAccessor;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;

/**
 * SQL语句上下文
 *
 * 负责记录动态SQL解析后的SQL语句片段
 *
 * @see SqlNode
 * @author Clinton Begin
 */
public class DynamicContext {

  // 参数
  public static final String PARAMETER_OBJECT_KEY = "_parameter";
  // 数据库id
  public static final String DATABASE_ID_KEY = "_databaseId";

  static {
    OgnlRuntime.setPropertyAccessor(ContextMap.class, new ContextAccessor());
  }

  // 内容上下文 (继承 HashMap<String, Object>)
  private final ContextMap bindings;
  // 保存 sql 片段
  private final StringJoiner sqlBuilder = new StringJoiner(" ");
  // 计数
  private int uniqueNumber = 0;

  /**
   * 构造函数
   *
   * @param configuration     Configuration 对象
   * @param parameterObject   运行时用户指定的参数
   */
  public DynamicContext(Configuration configuration, Object parameterObject) {
    // 对不是 Map 的类型参数会创建对应的 MetaObject 对象，并创建 ContextMap
    if (parameterObject != null && !(parameterObject instanceof Map)) {
      // 创建参数对应的 MetaObject
      MetaObject metaObject = configuration.newMetaObject(parameterObject);
      // 参数是否存在类型处理器
      boolean existsTypeHandler = configuration.getTypeHandlerRegistry().hasTypeHandler(parameterObject.getClass());
      // 创建上下文对象
      bindings = new ContextMap(metaObject, existsTypeHandler);
    } else {
      // 创建上下文对象
      bindings = new ContextMap(null, false);
    }
    // 参数绑定到上下文
    bindings.put(PARAMETER_OBJECT_KEY, parameterObject);
    // 数据库id 绑定到上下文中
    bindings.put(DATABASE_ID_KEY, configuration.getDatabaseId());
  }

  /**
   * 获取上下文
   *
   * @return
   */
  public Map<String, Object> getBindings() {
    return bindings;
  }

  /**
   * 添加参数
   *
   * @param name
   * @param value
   */
  public void bind(String name, Object value) {
    bindings.put(name, value);
  }

  /**
   * 追加sql片段
   *
   * @param sql
   */
  public void appendSql(String sql) {
    sqlBuilder.add(sql);
  }

  /**
   * 获取解析后的sql语句
   *
   * @return
   */
  public String getSql() {
    return sqlBuilder.toString().trim();
  }

  /**
   * 获取计数
   *
   * @return
   */
  public int getUniqueNumber() {
    return uniqueNumber++;
  }

  /**
   * 上下文
   */
  static class ContextMap extends HashMap<String, Object> {
    private static final long serialVersionUID = 2977601501966151582L;

    // 参数对应的 MetaObject
    private final MetaObject parameterMetaObject;
    // 参数是否存在类型处理器
    private final boolean fallbackParameterObject;

    public ContextMap(MetaObject parameterMetaObject, boolean fallbackParameterObject) {
      this.parameterMetaObject = parameterMetaObject;
      this.fallbackParameterObject = fallbackParameterObject;
    }

    @Override
    public Object get(Object key) {
      // 存在则直接返回
      String strKey = (String) key;
      if (super.containsKey(strKey)) {
        return super.get(strKey);
      }

      // 处理不存在的情况 ~

      // 参数对应的 MetaObject 对象不存在直接返回
      if (parameterMetaObject == null) {
        return null;
      }

      // 参数存在类型处理器并且参数中不存在 getter 方法, 返回原始对象
      if (fallbackParameterObject && !parameterMetaObject.hasGetter(strKey)) {
        return parameterMetaObject.getOriginalObject();
      } else {
        // 从参数中获取
        // issue #61 do not modify the context when reading
        return parameterMetaObject.getValue(strKey);
      }
    }
  }

  static class ContextAccessor implements PropertyAccessor {

    @Override
    public Object getProperty(Map context, Object target, Object name) {
      Map map = (Map) target;

      Object result = map.get(name);
      if (map.containsKey(name) || result != null) {
        return result;
      }

      Object parameterObject = map.get(PARAMETER_OBJECT_KEY);
      if (parameterObject instanceof Map) {
        return ((Map)parameterObject).get(name);
      }

      return null;
    }

    @Override
    public void setProperty(Map context, Object target, Object name, Object value) {
      Map<Object, Object> map = (Map<Object, Object>) target;
      map.put(name, value);
    }

    @Override
    public String getSourceAccessor(OgnlContext arg0, Object arg1, Object arg2) {
      return null;
    }

    @Override
    public String getSourceSetter(OgnlContext arg0, Object arg1, Object arg2) {
      return null;
    }
  }
}
