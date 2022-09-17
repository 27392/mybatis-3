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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.ibatis.session.Configuration;

/**
 * 对应 <trim> 节点
 *
 * {@link FilteredDynamicContext} 内部类负责处理子节点的sql语句片段
 *
 * <trim prefix="SET" suffixOverrides=",">
 *   ...
 * </trim>
 *
 * @link {https://mybatis.org/mybatis-3/zh/dynamic-sql.html#trim%E3%80%81where%E3%80%81set}
 * @author Clinton Begin
 */
public class TrimSqlNode implements SqlNode {

  // 子节点
  private final SqlNode contents;
  // 前缀
  private final String prefix;
  // 后缀
  private final String suffix;

  // prefixesOverrides 属性 (去除sql语句前面的关键字或者字符)
  private final List<String> prefixesToOverride;
  // suffixesOverrides 属性 (去除sql语句后面的关键字或者字符)
  private final List<String> suffixesToOverride;
  // Configuration 对象
  private final Configuration configuration;

  public TrimSqlNode(Configuration configuration, SqlNode contents, String prefix, String prefixesToOverride, String suffix, String suffixesToOverride) {
    this(configuration, contents, prefix, parseOverrides(prefixesToOverride), suffix, parseOverrides(suffixesToOverride));
  }

  protected TrimSqlNode(Configuration configuration, SqlNode contents, String prefix, List<String> prefixesToOverride, String suffix, List<String> suffixesToOverride) {
    this.contents = contents;
    this.prefix = prefix;
    this.prefixesToOverride = prefixesToOverride;
    this.suffix = suffix;
    this.suffixesToOverride = suffixesToOverride;
    this.configuration = configuration;
  }

  @Override
  public boolean apply(DynamicContext context) {
    // 创建 FilteredDynamicContext 对象，代理 DynamicContext 对象
    FilteredDynamicContext filteredDynamicContext = new FilteredDynamicContext(context);
    // 调用子节点的 apply 方法进行解析
    boolean result = contents.apply(filteredDynamicContext);
    // 处理前缀和后缀
    filteredDynamicContext.applyAll();
    return result;
  }

  /**
   * 处理 (prefixesOverrides，suffixesOverrides) 属性。 按照 "|" 分隔
   *
   * @param overrides
   * @return
   */
  private static List<String> parseOverrides(String overrides) {
    if (overrides != null) {
      final StringTokenizer parser = new StringTokenizer(overrides, "|", false);
      final List<String> list = new ArrayList<>(parser.countTokens());
      while (parser.hasMoreTokens()) {
        list.add(parser.nextToken().toUpperCase(Locale.ENGLISH));
      }
      return list;
    }
    return Collections.emptyList();
  }

  /**
   * DynamicContext 的静态代理类
   */
  private class FilteredDynamicContext extends DynamicContext {
    // 目标类（DynamicContext）
    private DynamicContext delegate;
    // 是否处理前缀
    private boolean prefixApplied;
    // 是否处理后缀
    private boolean suffixApplied;
    // 子节点的 SQL 片段
    private StringBuilder sqlBuffer;

    public FilteredDynamicContext(DynamicContext delegate) {
      super(configuration, null);
      this.delegate = delegate;
      this.prefixApplied = false;
      this.suffixApplied = false;
      this.sqlBuffer = new StringBuilder();
    }

    /**
     * 处理前后缀并将处理完成的SQL判断添加到 DynamicContext 中
     */
    public void applyAll() {
      // 去除子节点 SQL 片段的空格
      sqlBuffer = new StringBuilder(sqlBuffer.toString().trim());
      // 将 SQL 片段转为大写
      String trimmedUppercaseSql = sqlBuffer.toString().toUpperCase(Locale.ENGLISH);
      // SQL判断存在的情况下，处理前缀和后缀
      if (trimmedUppercaseSql.length() > 0) {
        applyPrefix(sqlBuffer, trimmedUppercaseSql);
        applySuffix(sqlBuffer, trimmedUppercaseSql);
      }
      // 调用目标类方法追加 SQL
      delegate.appendSql(sqlBuffer.toString());
    }

    @Override
    public Map<String, Object> getBindings() {
      return delegate.getBindings();
    }

    @Override
    public void bind(String name, Object value) {
      delegate.bind(name, value);
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }

    @Override
    public void appendSql(String sql) {
      // 不调用目标方法
      sqlBuffer.append(sql);
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    /**
     * 处理前缀
     *
     * @param sql
     * @param trimmedUppercaseSql
     */
    private void applyPrefix(StringBuilder sql, String trimmedUppercaseSql) {
      // 是否已经处理前缀
      if (!prefixApplied) {
        prefixApplied = true;
        if (prefixesToOverride != null) {
          // 遍历 prefixesToOverride 集合，如果以 prefixesToOverride 中某项开头，则将该项从SQL语句开头删除掉
          for (String toRemove : prefixesToOverride) {
            if (trimmedUppercaseSql.startsWith(toRemove)) {
              sql.delete(0, toRemove.trim().length());
              break;
            }
          }
        }
        // 添加前缀
        if (prefix != null) {
          sql.insert(0, " ");
          sql.insert(0, prefix);
        }
      }
    }

    /**
     * 处理后缀
     *
     * @param sql
     * @param trimmedUppercaseSql
     */
    private void applySuffix(StringBuilder sql, String trimmedUppercaseSql) {
      if (!suffixApplied) {
        suffixApplied = true;
        if (suffixesToOverride != null) {
          // 遍历 suffixesToOverride 集合，如果以 suffixesToOverride 中某项开头，则将该项从SQL语句结尾删除掉
          for (String toRemove : suffixesToOverride) {
            if (trimmedUppercaseSql.endsWith(toRemove) || trimmedUppercaseSql.endsWith(toRemove.trim())) {
              int start = sql.length() - toRemove.trim().length();
              int end = sql.length();
              sql.delete(start, end);
              break;
            }
          }
        }
        // 添加后缀
        if (suffix != null) {
          sql.append(" ");
          sql.append(suffix);
        }
      }
    }

  }

}
