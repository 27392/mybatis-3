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
package org.apache.ibatis.scripting.defaults;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeException;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * 默认的参数处理器
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class DefaultParameterHandler implements ParameterHandler {

  // TypeHandlerRegistry 对象
  private final TypeHandlerRegistry typeHandlerRegistry;

  // MappedStatement 对象
  private final MappedStatement mappedStatement;
  // 输入的参数
  private final Object parameterObject;
  // BoundSql 对象
  private final BoundSql boundSql;
  // Configuration 对象
  private final Configuration configuration;

  public DefaultParameterHandler(MappedStatement mappedStatement, Object parameterObject, BoundSql boundSql) {
    this.mappedStatement = mappedStatement;
    this.configuration = mappedStatement.getConfiguration();
    this.typeHandlerRegistry = mappedStatement.getConfiguration().getTypeHandlerRegistry();
    this.parameterObject = parameterObject;
    this.boundSql = boundSql;
  }

  @Override
  public Object getParameterObject() {
    return parameterObject;
  }

  @Override
  public void setParameters(PreparedStatement ps) {
    ErrorContext.instance().activity("setting parameters").object(mappedStatement.getParameterMap().getId());
    // 从 BoundSql 中取出参数映射集合
    List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();

    // 判断是否为空
    if (parameterMappings != null) {
      for (int i = 0; i < parameterMappings.size(); i++) {
        ParameterMapping parameterMapping = parameterMappings.get(i);

        // 过滤掉存储过程中的输出参数
        if (parameterMapping.getMode() != ParameterMode.OUT) {
          Object value;
          // 获取属性名
          String propertyName = parameterMapping.getProperty();

          // 是否存在额外的参数
          if (boundSql.hasAdditionalParameter(propertyName)) { // issue #448 ask first for additional params
            // 从额外参数中获取值
            value = boundSql.getAdditionalParameter(propertyName);
          } else if (parameterObject == null) {
            // 输入的参数
            value = null;
          } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
            // 输入的参数存在 TypeHandler. 值等于输入的参数 (交由 TypeHandler 处理)
            value = parameterObject;
          } else {
            // 输入的参数不为空,没有额外参数,没有 TypeHandler 时从对象中获取对应的值
            MetaObject metaObject = configuration.newMetaObject(parameterObject);
            value = metaObject.getValue(propertyName);
          }
          // 从 ParameterMapping 获取设置的 TypeHandler
          TypeHandler typeHandler = parameterMapping.getTypeHandler();
          JdbcType jdbcType = parameterMapping.getJdbcType();
          if (value == null && jdbcType == null) {
            jdbcType = configuration.getJdbcTypeForNull();
          }

          // 为 SQL 语句绑定参数
          // TypeHandler.setParameter() 方法会调用对应的 PreparedStatement.set*()方法
          try {
            typeHandler.setParameter(ps, i + 1, value, jdbcType);
          } catch (TypeException | SQLException e) {
            throw new TypeException("Could not set parameters for mapping: " + parameterMapping + ". Cause: " + e, e);
          }
        }
      }
    }
  }

}
