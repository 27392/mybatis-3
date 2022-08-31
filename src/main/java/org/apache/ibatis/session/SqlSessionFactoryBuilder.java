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
package org.apache.ibatis.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

import org.apache.ibatis.builder.xml.XMLConfigBuilder;
import org.apache.ibatis.exceptions.ExceptionFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;

/**
 * Builds {@link SqlSession} instances.
 *
 * @author Clinton Begin
 */
public class SqlSessionFactoryBuilder {

  /**
   * 创建 SqlSessionFactory
   *
   * @param reader
   * @return
   */
  public SqlSessionFactory build(Reader reader) {
    // 调用重载方法
    return build(reader, null, null);
  }

  /**
   * 创建 SqlSessionFactory
   *
   * @param reader
   * @param environment
   * @return
   */
  public SqlSessionFactory build(Reader reader, String environment) {
    return build(reader, environment, null);
  }

  /**
   * 创建 SqlSessionFactory
   *
   * @param reader
   * @param properties
   * @return
   */
  public SqlSessionFactory build(Reader reader, Properties properties) {
    return build(reader, null, properties);
  }

  /**
   * 创建 SqlSessionFactory
   * <p>
   * 所有以 Reader 为参数的 build() 重载方法都会调用该方法
   *
   * @param reader
   * @param environment
   * @param properties
   * @return
   */
  public SqlSessionFactory build(Reader reader, String environment, Properties properties) {
    try {
      // 创建 XMLConfigBuilder 对象
      XMLConfigBuilder parser = new XMLConfigBuilder(reader, environment, properties);
      // 解析配置文件, 得到 Configuration 对象
      // 使用 Configuration 对象创建 SqlSessionFactory 对象并返回
      return build(parser.parse());
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error building SqlSession.", e);
    } finally {
      ErrorContext.instance().reset();
      try {
        reader.close();
      } catch (IOException e) {
        // Intentionally ignore. Prefer previous error.
      }
    }
  }

  // 以下是使用 InputStream 方式

  /**
   * 创建 SqlSessionFactory
   *
   * @param inputStream
   * @return
   */
  public SqlSessionFactory build(InputStream inputStream) {
    return build(inputStream, null, null);
  }

  /**
   * 创建 SqlSessionFactory
   *
   * @param inputStream
   * @param environment
   * @return
   */
  public SqlSessionFactory build(InputStream inputStream, String environment) {
    return build(inputStream, environment, null);
  }

  /**
   * 创建 SqlSessionFactory
   *
   * @param inputStream
   * @param properties
   * @return
   */
  public SqlSessionFactory build(InputStream inputStream, Properties properties) {
    return build(inputStream, null, properties);
  }

  /**
   * 创建 SqlSessionFactory
   * <p>
   * 所有以 InputStream 为参数的 build() 重载方法都会调用该方法
   *
   * @param inputStream
   * @param environment
   * @param properties
   * @return
   */
  public SqlSessionFactory build(InputStream inputStream, String environment, Properties properties) {
    try {
      // 创建 XMLConfigBuilder 对象
      XMLConfigBuilder parser = new XMLConfigBuilder(inputStream, environment, properties);
      // 解析配置文件, 得到 Configuration 对象
      // 使用 Configuration 对象创建 SqlSessionFactory 对象并返回
      return build(parser.parse());
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error building SqlSession.", e);
    } finally {
      ErrorContext.instance().reset();
      try {
        inputStream.close();
      } catch (IOException e) {
        // Intentionally ignore. Prefer previous error.
      }
    }
  }

  /**
   * 创建 SqlSessionFactory
   *
   * @param config
   * @return
   */
  public SqlSessionFactory build(Configuration config) {
    return new DefaultSqlSessionFactory(config);
  }

}
