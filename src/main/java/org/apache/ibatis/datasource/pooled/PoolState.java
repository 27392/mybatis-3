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
package org.apache.ibatis.datasource.pooled;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Clinton Begin
 */
public class PoolState {

  // 数据源
  protected PooledDataSource dataSource;

  // 空闲的 PooledConnection 集合
  protected final List<PooledConnection> idleConnections = new ArrayList<>();

  // 活跃的 PooledConnection 集合
  protected final List<PooledConnection> activeConnections = new ArrayList<>();

  // 请求数据库连接的次数
  protected long requestCount = 0;
  // 记录获取连接的请求时间(累加)
  protected long accumulatedRequestTime = 0;
  // 记录连接的使用时长(累加)
  protected long accumulatedCheckoutTime = 0;
  // 记录了超时的连接个数（当连接长时间未归还给连接池时，会被认为该连接超时）
  protected long claimedOverdueConnectionCount = 0;
  // 记录了连接超时的时间(累加)
  protected long accumulatedCheckoutTimeOfOverdueConnections = 0;
  // 记录获取连接的等待时间(累加)
  protected long accumulatedWaitTime = 0;
  // 等待次数
  protected long hadToWaitCount = 0;
  // 无效的连接数
  protected long badConnectionCount = 0;

  public PoolState(PooledDataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * 获取请求数
   *
   * @return
   */
  public synchronized long getRequestCount() {
    return requestCount;
  }

  /**
   * 获取平均请求时间
   *
   * @return
   */
  public synchronized long getAverageRequestTime() {
    return requestCount == 0 ? 0 : accumulatedRequestTime / requestCount;
  }

  /**
   * 获取平均等待时长
   *
   * @return
   */
  public synchronized long getAverageWaitTime() {
    return hadToWaitCount == 0 ? 0 : accumulatedWaitTime / hadToWaitCount;

  }

  /**
   * 获取等待数量
   *
   * @return
   */
  public synchronized long getHadToWaitCount() {
    return hadToWaitCount;
  }

  /**
   * 获取无效连接数
   *
   * @return
   */
  public synchronized long getBadConnectionCount() {
    return badConnectionCount;
  }

  /**
   * 获取超时连接数
   *
   * @return
   */
  public synchronized long getClaimedOverdueConnectionCount() {
    return claimedOverdueConnectionCount;
  }

  /**
   * 获取连接平均超时时长
   *
   * @return
   */
  public synchronized long getAverageOverdueCheckoutTime() {
    return claimedOverdueConnectionCount == 0 ? 0 : accumulatedCheckoutTimeOfOverdueConnections / claimedOverdueConnectionCount;
  }

  /**
   * 获取连接平均处理时长
   *
   * @return
   */
  public synchronized long getAverageCheckoutTime() {
    return requestCount == 0 ? 0 : accumulatedCheckoutTime / requestCount;
  }

  /**
   * 获取空闲连接数
   * @return
   */
  public synchronized int getIdleConnectionCount() {
    return idleConnections.size();
  }

  /**
   * 获取活跃连接数
   *
   * @return
   */
  public synchronized int getActiveConnectionCount() {
    return activeConnections.size();
  }

  @Override
  public synchronized String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("\n===CONFIGURATION==============================================");
    builder.append("\n jdbcDriver                     ").append(dataSource.getDriver());
    builder.append("\n jdbcUrl                        ").append(dataSource.getUrl());
    builder.append("\n jdbcUsername                   ").append(dataSource.getUsername());
    builder.append("\n jdbcPassword                   ").append(dataSource.getPassword() == null ? "NULL" : "************");
    builder.append("\n poolMaxActiveConnections       ").append(dataSource.poolMaximumActiveConnections);
    builder.append("\n poolMaxIdleConnections         ").append(dataSource.poolMaximumIdleConnections);
    builder.append("\n poolMaxCheckoutTime            ").append(dataSource.poolMaximumCheckoutTime);
    builder.append("\n poolTimeToWait                 ").append(dataSource.poolTimeToWait);
    builder.append("\n poolPingEnabled                ").append(dataSource.poolPingEnabled);
    builder.append("\n poolPingQuery                  ").append(dataSource.poolPingQuery);
    builder.append("\n poolPingConnectionsNotUsedFor  ").append(dataSource.poolPingConnectionsNotUsedFor);
    builder.append("\n ---STATUS-----------------------------------------------------");
    builder.append("\n activeConnections              ").append(getActiveConnectionCount());
    builder.append("\n idleConnections                ").append(getIdleConnectionCount());
    builder.append("\n requestCount                   ").append(getRequestCount());
    builder.append("\n averageRequestTime             ").append(getAverageRequestTime());
    builder.append("\n averageCheckoutTime            ").append(getAverageCheckoutTime());
    builder.append("\n claimedOverdue                 ").append(getClaimedOverdueConnectionCount());
    builder.append("\n averageOverdueCheckoutTime     ").append(getAverageOverdueCheckoutTime());
    builder.append("\n hadToWait                      ").append(getHadToWaitCount());
    builder.append("\n averageWaitTime                ").append(getAverageWaitTime());
    builder.append("\n badConnectionCount             ").append(getBadConnectionCount());
    builder.append("\n===============================================================");
    return builder.toString();
  }

}
