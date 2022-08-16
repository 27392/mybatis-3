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
package org.apache.ibatis.logging;

/**
 * @author Clinton Begin
 */
public interface Log {

  /**
   * 是否启用 debug 级别
   *
   * @return
   */
  boolean isDebugEnabled();

  /**
   * 是否启用 trace 级别
   *
   * @return
   */
  boolean isTraceEnabled();

  /**
   * 打印 error 级别日志
   *
   * @param s
   * @param e
   */
  void error(String s, Throwable e);

  /**
   * 打印 error 级别日志
   *
   * @param s
   */
  void error(String s);

  /**
   * 打印 debug 级别日志
   *
   * @param s
   */
  void debug(String s);

  /**
   * 打印 trace 级别日志
   *
   * @param s
   */
  void trace(String s);

  /**
   * 打印 warn 级别日志
   *
   * @param s
   */
  void warn(String s);

}
