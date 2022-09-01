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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * XMLIncludeTransformer 负责解析 <include> 并将解析后的属性设置回SQL语句节点中
 *
 * @author Frank D. Martinez [mnesarco]
 */
public class XMLIncludeTransformer {

  // Configuration
  private final Configuration configuration;
  // MapperBuilderAssistant
  private final MapperBuilderAssistant builderAssistant;

  public XMLIncludeTransformer(Configuration configuration, MapperBuilderAssistant builderAssistant) {
    this.configuration = configuration;
    this.builderAssistant = builderAssistant;
  }

  public void applyIncludes(Node source) {
    // 将 Configuration 中的环境变量信息添加到 variablesContext 属性中
    Properties variablesContext = new Properties();
    Properties configurationVariables = configuration.getVariables();
    Optional.ofNullable(configurationVariables).ifPresent(variablesContext::putAll);

    // 处理 <include> 节点
    applyIncludes(source, variablesContext, false);
  }

  /**
   * 递归处理 <include> 节点
   * Recursively apply includes through all SQL fragments.
   *
   * @param source  SQL 语句节点
   *          Include node in DOM tree
   * @param variablesContext  环境变量
   *          Current context for static variables with values
   */
  private void applyIncludes(Node source, final Properties variablesContext, boolean included) {
    // 判断节点是否是 <include>
    if ("include".equals(source.getNodeName())) {
      // 获取 refid 属性, 在根据属性值获取 sql 片段
      Node toInclude = findSqlFragment(getStringAttribute(source, "refid"), variablesContext);

      // 解析 <include> 下的属性, 将获取到的配置与 variablesContext 进行合并. (<include> 节点中可以指定配置,然后在 <sql> 节点中使用)
      Properties toIncludeContext = getVariablesContext(source, variablesContext);

      // 递归调用. 标识 include = true; (主要是替换 <sql> 节点中的变量)
      applyIncludes(toInclude, toIncludeContext, true);

      // 如果 <include> 节点引用的 <sql> 节点不在同一个文档中
      // 将其它文档中 <sql> 节点引入到 <include> 所在的文档中
      if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
        toInclude = source.getOwnerDocument().importNode(toInclude, true);
      }
      // 将 <include> 替换为 <sql> 节点
      source.getParentNode().replaceChild(toInclude, source);

      // 将 <sql> 节点中的内容插入到 <sql> 节点前
      while (toInclude.hasChildNodes()) {
        toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);
      }
      // 删除 <sql> 节点. 已经将内容插入到 dom 中了, 所以不在需要它了
      toInclude.getParentNode().removeChild(toInclude);
    } else if (source.getNodeType() == Node.ELEMENT_NODE) {
      // 判断节点类型是否元素节点. 只有两种情况可以进入该分支 (<sql>节点和<sql语句>节点)

      // 替换 <sql> 节点属性变量. included 表示当前是<sql> 节点
      if (included && !variablesContext.isEmpty()) {
        // replace variables in attribute values
        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
          Node attr = attributes.item(i);
          attr.setNodeValue(PropertyParser.parse(attr.getNodeValue(), variablesContext));
        }
      }
      // 获取所有的子节点
      NodeList children = source.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        // 递归调用
        applyIncludes(children.item(i), variablesContext, included);
      }
    } else if (included && (source.getNodeType() == Node.TEXT_NODE || source.getNodeType() == Node.CDATA_SECTION_NODE)
        && !variablesContext.isEmpty()) {
      // replace variables in text node
      // 解析并替换 <sql> 节点中的内容
      source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
    }
  }

  /**
   * 查询 sql 片段
   *
   * @param refid
   * @param variables
   * @return
   */
  private Node findSqlFragment(String refid, Properties variables) {
    // 替换变量
    refid = PropertyParser.parse(refid, variables);
    // 使用 namespace.refid
    refid = builderAssistant.applyCurrentNamespace(refid, true);
    try {
      // 从 Configuration 中获取对应的 SQL 片段
      XNode nodeToInclude = configuration.getSqlFragments().get(refid);
      // 可以正常获取则克隆一份
      return nodeToInclude.getNode().cloneNode(true);
    } catch (IllegalArgumentException e) {
      // 没有获取到则抛出异常
      throw new IncompleteElementException("Could not find SQL statement to include with refid '" + refid + "'", e);
    }
  }

  /**
   * 或者节点属性值
   *
   * @param node  节点
   * @param name  属性
   * @return
   */
  private String getStringAttribute(Node node, String name) {
    return node.getAttributes().getNamedItem(name).getNodeValue();
  }

  /**
   * 解析 <include> 节点的配置
   *
   * 将解析到的属性与 inheritedVariablesContext 对象合并
   *
   * <sql id="sometable"> ${prefix}Table </sql>
   *
   * <include refid="someinclude">
   *   <property name="prefix" value="Some"/>
   * </include>
   *
   * Read placeholders and their values from include node definition.
   *
   * @param node
   *          Include node instance
   * @param inheritedVariablesContext
   *          Current context used for replace variables in new variables values
   * @return variables context from include instance (no inherited values)
   */
  private Properties getVariablesContext(Node node, Properties inheritedVariablesContext) {
    Map<String, String> declaredProperties = null;
    // 获取所有子节点
    NodeList children = node.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node n = children.item(i);
      // 处理 <property> 节点
      if (n.getNodeType() == Node.ELEMENT_NODE) {
        // 获取 name 属性值
        String name = getStringAttribute(n, "name");
        // Replace variables inside
        // 获取 value 属性值, 如果是变量则进行替换
        String value = PropertyParser.parse(getStringAttribute(n, "value"), inheritedVariablesContext);

        // 初始化 declaredProperties
        if (declaredProperties == null) {
          declaredProperties = new HashMap<>();
        }
        // 校验属性是否重复
        if (declaredProperties.put(name, value) != null) {
          throw new BuilderException("Variable " + name + " defined twice in the same include definition");
        }
      }
    }
    // 如果没有配置属性
    if (declaredProperties == null) {
      return inheritedVariablesContext;
    } else {
      // 合并变量
      Properties newProperties = new Properties();
      newProperties.putAll(inheritedVariablesContext);
      newProperties.putAll(declaredProperties);
      return newProperties;
    }
  }
}
