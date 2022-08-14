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
package org.apache.ibatis.reflection.property;

import java.util.Iterator;

/**
 * @author Clinton Begin
 */
public class PropertyTokenizer implements Iterator<PropertyTokenizer> {

  /**
   * 当前表达式名称
   */
  private String name;

  /**
   * 当前索引名
   */
  private final String indexedName;

  /**
   * 索引下标
   */
  private String index;

  /**
   * 子表达式
   */
  private final String children;

  public PropertyTokenizer(String fullname) {
    // 查询第一个'.'的位置
    int delim = fullname.indexOf('.');

    // 如果存在'.'则表示存在子表达式
    if (delim > -1) {
      // 初始化名称, 截取第一个'.'前面的字符
      name = fullname.substring(0, delim);
      // 初始化子表达式, 截取第一个'.'后面的字符
      children = fullname.substring(delim + 1);
    } else {
      // 如果不存在则表示是单一属性
      name = fullname;
      // 子表达式为空
      children = null;
    }
    // 第一个'.'前面的字符(可能是包含下标的名称)
    indexedName = name;
    // 判断名称是否存在下标. 例如 names[0]
    delim = name.indexOf('[');
    // 存在下标
    if (delim > -1) {
      // 取出下标. 例如 names[0] = 0; map[a] = a
      index = name.substring(delim + 1, name.length() - 1);
      // 重新计算名称. 例如 names[0] = names
      name = name.substring(0, delim);
    }
  }

  public String getName() {
    return name;
  }

  public String getIndex() {
    return index;
  }

  public String getIndexedName() {
    return indexedName;
  }

  public String getChildren() {
    return children;
  }

  @Override
  public boolean hasNext() {
    return children != null;
  }

  @Override
  public PropertyTokenizer next() {
    // 使用子表达式创建新的 PropertyTokenizer 对象返回
    return new PropertyTokenizer(children);
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException("Remove is not supported, as it has no meaning in the context of properties.");
  }
}
