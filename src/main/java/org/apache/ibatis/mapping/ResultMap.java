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
package org.apache.ibatis.mapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.reflection.ParamNameUtil;
import org.apache.ibatis.session.Configuration;

/**
 *
 * @see ResultMapping
 * @author Clinton Begin
 */
public class ResultMap {

  // Configuration 对像
  private Configuration configuration;

  // id 属性
  private String id;
  // type 属性
  private Class<?> type;
  // 记录了除 <discriminator> 节点之外的映射关系
  private List<ResultMapping> resultMappings;
  // 记录了映射关系中带有 ID 标识的映射关系, 例如<id>和<idArg>节点
  private List<ResultMapping> idResultMappings;
  // 记录了 <constructor> 节点下的映射关系
  private List<ResultMapping> constructorResultMappings;
  // 记录了非 <constructor> 节点下的映射关系
  private List<ResultMapping> propertyResultMappings;

  // 所有的映射的 column 属性. 大写
  private Set<String> mappedColumns;
  // 所有的映射的 property 属性
  private Set<String> mappedProperties;

  // 鉴别器, 对应 <discriminator> 节点
  private Discriminator discriminator;
  // 是否存在嵌套结果集(引用其它的 ResultMap), 如果某个映射关系存在 resultMap 属性,且不存在 resultSet 属性则为 true
  private boolean hasNestedResultMaps;
  // 是否存在嵌套查询, 如果某个映射关系存在 select 属性, 则为 true
  private boolean hasNestedQueries;
  // 是否开启自动映射
  private Boolean autoMapping;

  private ResultMap() {
  }

  /**
   * 建造者
   */
  public static class Builder {
    private static final Log log = LogFactory.getLog(Builder.class);

    private ResultMap resultMap = new ResultMap();

    public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings) {
      this(configuration, id, type, resultMappings, null);
    }

    public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings, Boolean autoMapping) {
      resultMap.configuration = configuration;
      resultMap.id = id;
      resultMap.type = type;
      resultMap.resultMappings = resultMappings;
      resultMap.autoMapping = autoMapping;
    }

    public Builder discriminator(Discriminator discriminator) {
      resultMap.discriminator = discriminator;
      return this;
    }

    public Class<?> type() {
      return resultMap.type;
    }

    /**
     * 创建 ResultMap
     *
     * @return
     */
    public ResultMap build() {
      // id 不存在抛出异常
      if (resultMap.id == null) {
        throw new IllegalArgumentException("ResultMaps must have an id");
      }
      // 初始化集合属性
      resultMap.mappedColumns = new HashSet<>();
      resultMap.mappedProperties = new HashSet<>();
      resultMap.idResultMappings = new ArrayList<>();
      resultMap.constructorResultMappings = new ArrayList<>();
      resultMap.propertyResultMappings = new ArrayList<>();
      final List<String> constructorArgNames = new ArrayList<>();

      // 遍历 ResultMapping 项
      for (ResultMapping resultMapping : resultMap.resultMappings) {
        // 标记是否存在嵌套查询 (存在 select 属性)
        resultMap.hasNestedQueries = resultMap.hasNestedQueries || resultMapping.getNestedQueryId() != null;
        // 标记是否存在嵌套结果集 (存在 select 属性)
        resultMap.hasNestedResultMaps = resultMap.hasNestedResultMaps || (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null);

        // 处理列名. 将列名转成大写后添加到 mappedColumns 集合中
        final String column = resultMapping.getColumn();
        if (column != null) {
          resultMap.mappedColumns.add(column.toUpperCase(Locale.ENGLISH));
        } else if (resultMapping.isCompositeResult()) {
          for (ResultMapping compositeResultMapping : resultMapping.getComposites()) {
            final String compositeColumn = compositeResultMapping.getColumn();
            if (compositeColumn != null) {
              resultMap.mappedColumns.add(compositeColumn.toUpperCase(Locale.ENGLISH));
            }
          }
        }

        // 处理属性. 将属性添加到 mappedProperties 集合中
        final String property = resultMapping.getProperty();
        if (property != null) {
          resultMap.mappedProperties.add(property);
        }
        // 处理构造参数. 将属性添加到 constructorResultMappings 集合中
        if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
          resultMap.constructorResultMappings.add(resultMapping);
          if (resultMapping.getProperty() != null) {
            constructorArgNames.add(resultMapping.getProperty());
          }
        } else {
          // 不是构造方法则添加到 propertyResultMappings 集合中
          resultMap.propertyResultMappings.add(resultMapping);
        }

        // 不是构造参数中的 id 属性, 添加到 idResultMappings 集合中
        if (resultMapping.getFlags().contains(ResultFlag.ID)) {
          resultMap.idResultMappings.add(resultMapping);
        }
      }
      // 如果 idResultMappings 集合为空, 则往其中填充 resultMap resultMappings
      if (resultMap.idResultMappings.isEmpty()) {
        resultMap.idResultMappings.addAll(resultMap.resultMappings);
      }

      // 处理构造函数名称
      if (!constructorArgNames.isEmpty()) {
        // 获取匹配后的构造参数名称
        final List<String> actualArgNames = argNamesOfMatchingConstructor(constructorArgNames);
        if (actualArgNames == null) {
          throw new BuilderException("Error in result map '" + resultMap.id
              + "'. Failed to find a constructor in '"
              + resultMap.getType().getName() + "' by arg names " + constructorArgNames
              + ". There might be more info in debug log.");
        }
        // 将配置的顺序根据获取到的参数名称进行排序.
        resultMap.constructorResultMappings.sort((o1, o2) -> {
          int paramIdx1 = actualArgNames.indexOf(o1.getProperty());
          int paramIdx2 = actualArgNames.indexOf(o2.getProperty());
          return paramIdx1 - paramIdx2;
        });
      }

      // lock down collections
      // 将集合修改为不可修改类型
      resultMap.resultMappings = Collections.unmodifiableList(resultMap.resultMappings);
      resultMap.idResultMappings = Collections.unmodifiableList(resultMap.idResultMappings);
      resultMap.constructorResultMappings = Collections.unmodifiableList(resultMap.constructorResultMappings);
      resultMap.propertyResultMappings = Collections.unmodifiableList(resultMap.propertyResultMappings);
      resultMap.mappedColumns = Collections.unmodifiableSet(resultMap.mappedColumns);
      return resultMap;
    }

    /**
     * 匹配构造函数参数名称
     *
     * @param constructorArgNames
     * @return
     */
    private List<String> argNamesOfMatchingConstructor(List<String> constructorArgNames) {
      // 获取构造函数列表
      Constructor<?>[] constructors = resultMap.type.getDeclaredConstructors();
      for (Constructor<?> constructor : constructors) {
        Class<?>[] paramTypes = constructor.getParameterTypes();
        if (constructorArgNames.size() == paramTypes.length) {
          // 获取构造函数的参数名称
          List<String> paramNames = getArgNames(constructor);
          // 与传入的参数名完全匹配, 并且类型完全匹配
          if (constructorArgNames.containsAll(paramNames)
              && argTypesMatch(constructorArgNames, paramTypes, paramNames)) {
            return paramNames;
          }
        }
      }
      return null;
    }

    /**
     * 构造函数类型匹配
     *
     * @param constructorArgNames
     * @param paramTypes
     * @param paramNames
     * @return
     */
    private boolean argTypesMatch(final List<String> constructorArgNames,
        Class<?>[] paramTypes, List<String> paramNames) {
      for (int i = 0; i < constructorArgNames.size(); i++) {
        // 获取构造函数中参数类型
        Class<?> actualType = paramTypes[paramNames.indexOf(constructorArgNames.get(i))];
        // 获取配置的类型
        Class<?> specifiedType = resultMap.constructorResultMappings.get(i).getJavaType();
        // 类型不相同则抛出异常
        if (!actualType.equals(specifiedType)) {
          if (log.isDebugEnabled()) {
            log.debug("While building result map '" + resultMap.id
                + "', found a constructor with arg names " + constructorArgNames
                + ", but the type of '" + constructorArgNames.get(i)
                + "' did not match. Specified: [" + specifiedType.getName() + "] Declared: ["
                + actualType.getName() + "]");
          }
          return false;
        }
      }
      return true;
    }

    /**
     * 获取构造函数的参数名称
     *
     * @param constructor
     * @return
     */
    private List<String> getArgNames(Constructor<?> constructor) {
      List<String> paramNames = new ArrayList<>();
      List<String> actualParamNames = null;
      // 获取构造函数参数中的注解信息
      final Annotation[][] paramAnnotations = constructor.getParameterAnnotations();
      int paramCount = paramAnnotations.length;

      // 遍历参数
      for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
        String name = null;
        // 查找 @Param 注解,获取对应的参数名称
        for (Annotation annotation : paramAnnotations[paramIndex]) {
          if (annotation instanceof Param) {
            name = ((Param) annotation).value();
            break;
          }
        }

        // 不存在注解信息
        if (name == null && resultMap.configuration.isUseActualParamName()) {
          // 获取构造器参数的名称
          if (actualParamNames == null) {
            actualParamNames = ParamNameUtil.getParamNames(constructor);
          }
          // 获取参数名称
          if (actualParamNames.size() > paramIndex) {
            name = actualParamNames.get(paramIndex);
          }
        }
        // 保存解析后的参数
        paramNames.add(name != null ? name : "arg" + paramIndex);
      }
      return paramNames;
    }
  }

  public String getId() {
    return id;
  }

  public boolean hasNestedResultMaps() {
    return hasNestedResultMaps;
  }

  public boolean hasNestedQueries() {
    return hasNestedQueries;
  }

  public Class<?> getType() {
    return type;
  }

  public List<ResultMapping> getResultMappings() {
    return resultMappings;
  }

  public List<ResultMapping> getConstructorResultMappings() {
    return constructorResultMappings;
  }

  public List<ResultMapping> getPropertyResultMappings() {
    return propertyResultMappings;
  }

  public List<ResultMapping> getIdResultMappings() {
    return idResultMappings;
  }

  public Set<String> getMappedColumns() {
    return mappedColumns;
  }

  public Set<String> getMappedProperties() {
    return mappedProperties;
  }

  public Discriminator getDiscriminator() {
    return discriminator;
  }

  public void forceNestedResultMaps() {
    hasNestedResultMaps = true;
  }

  public Boolean getAutoMapping() {
    return autoMapping;
  }

}
