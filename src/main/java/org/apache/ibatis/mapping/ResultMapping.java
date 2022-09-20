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

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * ResultMapping 对象
 *
 * 记录了结果集中的一列与 JavaBean 中一个属性之间的映射关系
 * <resultMap> 节点下除了 <discriminator>子节点的其它子节点,都会被解析成对应的 ResultMapping 对象
 *
 * @see ResultMap
 * @author Clinton Begin
 */
public class ResultMapping {

  // Configuration 对象
  private Configuration configuration;

  // 属性名; 对应 property 属性的值, 表示与改列进行映射的属性
  private String property;
  // 字段名; 对应 column 属性的值, 表示的是从数据库中得到的列名或是别名
  private String column;
  // 属性的 Class; 对应 javaType 属性的值, 一般是 property 属性对应的 Class 类型
  private Class<?> javaType;
  // jdbc类型; 对应 jdbcType 属性的值, 表示的是进行映射的列的 JDBC 类型
  private JdbcType jdbcType;
  // 类型处理器; 对应 typeHandler 属性的值, 如果指定了会覆盖默认的类型处理器
  private TypeHandler<?> typeHandler;

  // resultMap 属性; 表示引用了另一个 <resultMap> 节点. (一般只在 <association> 与 <collection> 节点中存在)
  // <association property="author" column="blog_author_id" javaType="Author" resultMap="authorResult"/>
  private String nestedResultMapId;
  // select 属性; (只在 <association> 与 <collection> 节点中存在)
  // <association property="author" column="author_id" javaType="Author" select="selectAuthor"/>
  private String nestedQueryId;

  // notNullColumn 属性使用逗号拆分后的结果
  private Set<String> notNullColumns;

  // columnPrefix 属性
  private String columnPrefix;

  // 构造参数标识(ID, CONSTRUCTOR)
  private List<ResultFlag> flags;

  // column 属性拆分后的结果; (使用嵌套查询时传递多个参数. column="{prop1=col1,prop2=col2}")
  private List<ResultMapping> composites;

  // resultSet 属性; (处理使用存储过程时存在多结果集)
  private String resultSet;
  // foreignColumn 属性; (处理使用存储过程时存在多结果集)
  private String foreignColumn;
  // 是否是延迟加载; 对应 fetchType 属性的值
  private boolean lazy;

  ResultMapping() {
  }

  /**
   * 建造者
   */
  public static class Builder {
    private ResultMapping resultMapping = new ResultMapping();

    public Builder(Configuration configuration, String property, String column, TypeHandler<?> typeHandler) {
      this(configuration, property);
      resultMapping.column = column;
      resultMapping.typeHandler = typeHandler;
    }

    public Builder(Configuration configuration, String property, String column, Class<?> javaType) {
      this(configuration, property);
      resultMapping.column = column;
      resultMapping.javaType = javaType;
    }

    public Builder(Configuration configuration, String property) {
      resultMapping.configuration = configuration;
      resultMapping.property = property;
      resultMapping.flags = new ArrayList<>();
      resultMapping.composites = new ArrayList<>();
      resultMapping.lazy = configuration.isLazyLoadingEnabled();
    }

    public Builder javaType(Class<?> javaType) {
      resultMapping.javaType = javaType;
      return this;
    }

    public Builder jdbcType(JdbcType jdbcType) {
      resultMapping.jdbcType = jdbcType;
      return this;
    }

    public Builder nestedResultMapId(String nestedResultMapId) {
      resultMapping.nestedResultMapId = nestedResultMapId;
      return this;
    }

    public Builder nestedQueryId(String nestedQueryId) {
      resultMapping.nestedQueryId = nestedQueryId;
      return this;
    }

    public Builder resultSet(String resultSet) {
      resultMapping.resultSet = resultSet;
      return this;
    }

    public Builder foreignColumn(String foreignColumn) {
      resultMapping.foreignColumn = foreignColumn;
      return this;
    }

    public Builder notNullColumns(Set<String> notNullColumns) {
      resultMapping.notNullColumns = notNullColumns;
      return this;
    }

    public Builder columnPrefix(String columnPrefix) {
      resultMapping.columnPrefix = columnPrefix;
      return this;
    }

    public Builder flags(List<ResultFlag> flags) {
      resultMapping.flags = flags;
      return this;
    }

    public Builder typeHandler(TypeHandler<?> typeHandler) {
      resultMapping.typeHandler = typeHandler;
      return this;
    }

    public Builder composites(List<ResultMapping> composites) {
      resultMapping.composites = composites;
      return this;
    }

    public Builder lazy(boolean lazy) {
      resultMapping.lazy = lazy;
      return this;
    }

    /**
     * 创建 ResultMapping
     *
     * @return
     */
    public ResultMapping build() {
      // lock down collections
      // 将 flags 与 composites 两个集合修改为不可编辑集合
      resultMapping.flags = Collections.unmodifiableList(resultMapping.flags);
      resultMapping.composites = Collections.unmodifiableList(resultMapping.composites);
      // 解析类型处理器
      resolveTypeHandler();
      // 校验属性信息
      validate();
      return resultMapping;
    }

    /**
     * 校验属性信息
     */
    private void validate() {
      // Issue #697: cannot define both nestedQueryId and nestedResultMapId
      if (resultMapping.nestedQueryId != null && resultMapping.nestedResultMapId != null) {
        throw new IllegalStateException("Cannot define both nestedQueryId and nestedResultMapId in property " + resultMapping.property);
      }
      // Issue #5: there should be no mappings without typehandler
      if (resultMapping.nestedQueryId == null && resultMapping.nestedResultMapId == null && resultMapping.typeHandler == null) {
        throw new IllegalStateException("No typehandler found for property " + resultMapping.property);
      }
      // Issue #4 and GH #39: column is optional only in nested resultmaps but not in the rest
      if (resultMapping.nestedResultMapId == null && resultMapping.column == null && resultMapping.composites.isEmpty()) {
        throw new IllegalStateException("Mapping is missing column attribute for property " + resultMapping.property);
      }
      if (resultMapping.getResultSet() != null) {
        int numColumns = 0;
        if (resultMapping.column != null) {
          numColumns = resultMapping.column.split(",").length;
        }
        int numForeignColumns = 0;
        if (resultMapping.foreignColumn != null) {
          numForeignColumns = resultMapping.foreignColumn.split(",").length;
        }
        if (numColumns != numForeignColumns) {
          throw new IllegalStateException("There should be the same number of columns and foreignColumns in property " + resultMapping.property);
        }
      }
    }

    /**
     * 解析 TypeHandler
     *
     * 处理 存在具体的类型,但是没有指定 TypeHandler 的情况下
     */
    private void resolveTypeHandler() {
      // 存在具体类型, 但是没有指定 TypeHandler
      if (resultMapping.typeHandler == null && resultMapping.javaType != null) {
        Configuration configuration = resultMapping.configuration;
        TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        // 获取 TypeHandler
        resultMapping.typeHandler = typeHandlerRegistry.getTypeHandler(resultMapping.javaType, resultMapping.jdbcType);
      }
    }

    public Builder column(String column) {
      resultMapping.column = column;
      return this;
    }
  }

  public String getProperty() {
    return property;
  }

  public String getColumn() {
    return column;
  }

  public Class<?> getJavaType() {
    return javaType;
  }

  public JdbcType getJdbcType() {
    return jdbcType;
  }

  public TypeHandler<?> getTypeHandler() {
    return typeHandler;
  }

  public String getNestedResultMapId() {
    return nestedResultMapId;
  }

  public String getNestedQueryId() {
    return nestedQueryId;
  }

  public Set<String> getNotNullColumns() {
    return notNullColumns;
  }

  public String getColumnPrefix() {
    return columnPrefix;
  }

  public List<ResultFlag> getFlags() {
    return flags;
  }

  public List<ResultMapping> getComposites() {
    return composites;
  }

  public boolean isCompositeResult() {
    return this.composites != null && !this.composites.isEmpty();
  }

  public String getResultSet() {
    return this.resultSet;
  }

  public String getForeignColumn() {
    return foreignColumn;
  }

  public void setForeignColumn(String foreignColumn) {
    this.foreignColumn = foreignColumn;
  }

  public boolean isLazy() {
    return lazy;
  }

  public void setLazy(boolean lazy) {
    this.lazy = lazy;
  }

  public boolean isSimple() {
    return this.nestedResultMapId == null && this.nestedQueryId == null && this.resultSet == null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    ResultMapping that = (ResultMapping) o;

    return property != null && property.equals(that.property);
  }

  @Override
  public int hashCode() {
    if (property != null) {
      return property.hashCode();
    } else if (column != null) {
      return column.hashCode();
    } else {
      return 0;
    }
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("ResultMapping{");
    //sb.append("configuration=").append(configuration); // configuration doesn't have a useful .toString()
    sb.append("property='").append(property).append('\'');
    sb.append(", column='").append(column).append('\'');
    sb.append(", javaType=").append(javaType);
    sb.append(", jdbcType=").append(jdbcType);
    //sb.append(", typeHandler=").append(typeHandler); // typeHandler also doesn't have a useful .toString()
    sb.append(", nestedResultMapId='").append(nestedResultMapId).append('\'');
    sb.append(", nestedQueryId='").append(nestedQueryId).append('\'');
    sb.append(", notNullColumns=").append(notNullColumns);
    sb.append(", columnPrefix='").append(columnPrefix).append('\'');
    sb.append(", flags=").append(flags);
    sb.append(", composites=").append(composites);
    sb.append(", resultSet='").append(resultSet).append('\'');
    sb.append(", foreignColumn='").append(foreignColumn).append('\'');
    sb.append(", lazy=").append(lazy);
    sb.append('}');
    return sb.toString();
  }

}
