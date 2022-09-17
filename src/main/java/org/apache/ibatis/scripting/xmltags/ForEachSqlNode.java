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

import java.util.Map;
import java.util.Optional;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.session.Configuration;

/**
 * 对应 <foreach> 节点
 *
 * <select id="selectPostIn" resultType="domain.blog.Post">
 *   SELECT * FROM POST P
 *   <where>
 *     <foreach item="item" index="index" collection="list" open="ID in (" separator="," close=")" nullable="true">
 *         #{item}
 *     </foreach>
 *   </where>
 * </select>
 *
 * SELECT * FROM POST P WHERE ID in (#{__frch_item_0},#{__frch_item_1},#{__frch_item_2})
 *
 * @see PrefixedContext         处理前缀也是就是 separator 属性
 * @see FilteredDynamicContext  将`#{}`替换成 `#{__frch_item_集合索引}` 或 `#{__frch_itemIndex_集合索引}` {@link #itemizeItem(String, int)}
 * @link {https://mybatis.org/mybatis-3/zh/dynamic-sql.html#foreach}
 * @author Clinton Begin
 */
public class ForEachSqlNode implements SqlNode {
  public static final String ITEM_PREFIX = "__frch_";

  // 表达式解析对象
  private final ExpressionEvaluator evaluator;

  // collection 属性，表示分隔符
  private final String collectionExpression;
  // nullable 属性，表示分隔符
  private final Boolean nullable;
  // 子节点
  private final SqlNode contents;
  // open 属性，表示循环开始前需要添加的字符
  private final String open;
  // close 属性，表示循环结束后需要添加的字符
  private final String close;
  // separator 属性，表示分隔符
  private final String separator;
  // item 属性，表示本次迭代的元素。当使用 Map 对象（或者 Map.Entry 对象的集合）时，index 是键，item 是值
  private final String item;
  // index 属性，表示本次迭代的次数。当使用 Map 对象（或者 Map.Entry 对象的集合）时，index 是键，item 是值
  private final String index;
  // Configuration 对象
  private final Configuration configuration;

  /**
   * @deprecated Since 3.5.9, use the {@link #ForEachSqlNode(Configuration, SqlNode, String, Boolean, String, String, String, String, String)}.
   */
  @Deprecated
  public ForEachSqlNode(Configuration configuration, SqlNode contents, String collectionExpression, String index, String item, String open, String close, String separator) {
    this(configuration, contents, collectionExpression, null, index, item, open, close, separator);
  }

  /**
   * @since 3.5.9
   */
  public ForEachSqlNode(Configuration configuration, SqlNode contents, String collectionExpression, Boolean nullable, String index, String item, String open, String close, String separator) {
    this.evaluator = new ExpressionEvaluator();
    this.collectionExpression = collectionExpression;
    this.nullable = nullable;
    this.contents = contents;
    this.open = open;
    this.close = close;
    this.separator = separator;
    this.index = index;
    this.item = item;
    this.configuration = configuration;
  }

  @Override
  public boolean apply(DynamicContext context) {
    Map<String, Object> bindings = context.getBindings();
    final Iterable<?> iterable = evaluator.evaluateIterable(collectionExpression, bindings,
      Optional.ofNullable(nullable).orElseGet(configuration::isNullableOnForEach));
    if (iterable == null || !iterable.iterator().hasNext()) {
      return true;
    }
    // 是否为首个节点（首个节点不需要增加分隔符）
    boolean first = true;
    // 追加 open 属性
    applyOpen(context);

    int i = 0;
    for (Object o : iterable) {
      DynamicContext oldContext = context;
      // 首个节点或分隔符为空的情况下不需要增加分隔符前缀
      if (first || separator == null) {
        context = new PrefixedContext(context, "");
      } else {
        context = new PrefixedContext(context, separator);
      }
      // 获取下标
      int uniqueNumber = context.getUniqueNumber();

      // Issue #709
      // 如果是 Map 类型，将 key 与 value 添加到 DynamicContext.bindings 中
      if (o instanceof Map.Entry) {
        @SuppressWarnings("unchecked")
        Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>) o;
        applyIndex(context, mapEntry.getKey(), uniqueNumber);
        applyItem(context, mapEntry.getValue(), uniqueNumber);
      } else {
        // 将集合中的索引和元素添加到 DynamicContext.bindings 中
        applyIndex(context, i, uniqueNumber);
        applyItem(context, o, uniqueNumber);
      }
      // 处理子节点
      contents.apply(new FilteredDynamicContext(configuration, context, index, item, uniqueNumber));
      if (first) {
        first = !((PrefixedContext) context).isPrefixApplied();
      }
      context = oldContext;
      i++;
    }
    // 追加 close 属性
    applyClose(context);
    // 删除
    context.getBindings().remove(item);
    context.getBindings().remove(index);
    return true;
  }

  /**
   * 将 index 属性与对应的值添加到 DynamicContext.bindings 中
   *
   * @param context
   * @param o
   * @param i
   */
  private void applyIndex(DynamicContext context, Object o, int i) {
    if (index != null) {
      context.bind(index, o);
      context.bind(itemizeItem(index, i), o);
    }
  }

  /**
   * 将 item 属性与对应的值添加到 DynamicContext.bindings 中
   *
   * @param context
   * @param o
   * @param i
   */
  private void applyItem(DynamicContext context, Object o, int i) {
    if (item != null) {
      context.bind(item, o);
      context.bind(itemizeItem(item, i), o);
    }
  }

  /**
   * 追加 open 属性
   *
   * @param context
   */
  private void applyOpen(DynamicContext context) {
    if (open != null) {
      context.appendSql(open);
    }
  }

  /**
   * 追加 close 属性
   *
   * @param context
   */
  private void applyClose(DynamicContext context) {
    if (close != null) {
      context.appendSql(close);
    }
  }

  /**
   * 格式化值
   *
   * @param item
   * @param i
   * @return
   */
  private static String itemizeItem(String item, int i) {
    return ITEM_PREFIX + item + "_" + i;
  }

  /**
   * PrefixedContext 的静态代理类
   *
   * 负责将`#{}`替换成 `#{__frch_item_index}` 或 `#{__frch_itemIndex_index}`
   */
  private static class FilteredDynamicContext extends DynamicContext {
    // 目标类 （PrefixedContext 对象）
    private final DynamicContext delegate;
    // 下标
    private final int index;
    // index 属性
    private final String itemIndex;
    // item 属性
    private final String item;

    public FilteredDynamicContext(Configuration configuration,DynamicContext delegate, String itemIndex, String item, int i) {
      super(configuration, null);
      this.delegate = delegate;
      this.index = i;
      this.itemIndex = itemIndex;
      this.item = item;
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
    public String getSql() {
      return delegate.getSql();
    }

    @Override
    public void appendSql(String sql) {
      GenericTokenParser parser = new GenericTokenParser("#{", "}", content -> {
        // 处理 item
        String newContent = content.replaceFirst("^\\s*" + item + "(?![^.,:\\s])", itemizeItem(item, index));
        // 处理 index
        if (itemIndex != null && newContent.equals(content)) {
          newContent = content.replaceFirst("^\\s*" + itemIndex + "(?![^.,:\\s])", itemizeItem(itemIndex, index));
        }
        return "#{" + newContent + "}";
      });

      // 追加 SQL 片段
      delegate.appendSql(parser.parse(sql));
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }

  }

  /**
   * DynamicContext 的静态代理类（处理分隔符）
   */
  private class PrefixedContext extends DynamicContext {
    // 目标类（DynamicContext）
    private final DynamicContext delegate;
    // 前缀
    private final String prefix;
    // 是否处理过前缀
    private boolean prefixApplied;

    public PrefixedContext(DynamicContext delegate, String prefix) {
      super(configuration, null);
      this.delegate = delegate;
      this.prefix = prefix;
      this.prefixApplied = false;
    }

    public boolean isPrefixApplied() {
      return prefixApplied;
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
    public void appendSql(String sql) {
      // 判断是否需要增加前缀
      if (!prefixApplied && sql != null && sql.trim().length() > 0) {
        // 追加前缀
        delegate.appendSql(prefix);
        // 修改标识
        prefixApplied = true;
      }
      // 追加SQL片段
      delegate.appendSql(sql);
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }
  }

}
