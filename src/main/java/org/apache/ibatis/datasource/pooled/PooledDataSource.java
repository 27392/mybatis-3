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

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * 一个简单、同步、线程安全的数据库连接池
 *
 * This is a simple, synchronous, thread-safe database connection pool.
 *
 * @author Clinton Begin
 */
public class PooledDataSource implements DataSource {

  private static final Log log = LogFactory.getLog(PooledDataSource.class);

  private final PoolState state = new PoolState(this);

  // 非池化数据源（包含数据库的连接等信息）
  private final UnpooledDataSource dataSource;

  // OPTIONAL CONFIGURATION FIELDS
  // 最大活跃连接数
  protected int poolMaximumActiveConnections = 10;
  // 最大空闲连接数
  protected int poolMaximumIdleConnections = 5;
  // 最大链接时长
  protected int poolMaximumCheckoutTime = 20000;
  // 无法获取连接时，线程需要等待的时长
  protected int poolTimeToWait = 20000;
  // 单次获取连接允许获取无效的连接数量
  protected int poolMaximumLocalBadConnectionTolerance = 3;
  // 测试 SQL (检测一个连接是否可用时，会给数据类发送一个测试 SQL)
  protected String poolPingQuery = "NO PING QUERY SET";
  // 是否允许发送测试 SQL
  protected boolean poolPingEnabled;
  // 多长时间未使用过连接(超时时间发送测试SQL)
  protected int poolPingConnectionsNotUsedFor;
  // 由数据库 URL、用户名和密码计算出来的 hash 值,可用于标识该连接所在的连接池
  private int expectedConnectionTypeCode;

  public PooledDataSource() {
    dataSource = new UnpooledDataSource();
  }

  public PooledDataSource(UnpooledDataSource dataSource) {
    this.dataSource = dataSource;
  }

  public PooledDataSource(String driver, String url, String username, String password) {
    dataSource = new UnpooledDataSource(driver, url, username, password);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(String driver, String url, Properties driverProperties) {
    dataSource = new UnpooledDataSource(driver, url, driverProperties);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(ClassLoader driverClassLoader, String driver, String url, String username, String password) {
    dataSource = new UnpooledDataSource(driverClassLoader, driver, url, username, password);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(ClassLoader driverClassLoader, String driver, String url, Properties driverProperties) {
    dataSource = new UnpooledDataSource(driverClassLoader, driver, url, driverProperties);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  @Override
  public Connection getConnection() throws SQLException {
    return popConnection(dataSource.getUsername(), dataSource.getPassword()).getProxyConnection();
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return popConnection(username, password).getProxyConnection();
  }

  @Override
  public void setLoginTimeout(int loginTimeout) {
    DriverManager.setLoginTimeout(loginTimeout);
  }

  @Override
  public int getLoginTimeout() {
    return DriverManager.getLoginTimeout();
  }

  @Override
  public void setLogWriter(PrintWriter logWriter) {
    DriverManager.setLogWriter(logWriter);
  }

  @Override
  public PrintWriter getLogWriter() {
    return DriverManager.getLogWriter();
  }

  public void setDriver(String driver) {
    dataSource.setDriver(driver);
    forceCloseAll();
  }

  public void setUrl(String url) {
    dataSource.setUrl(url);
    forceCloseAll();
  }

  public void setUsername(String username) {
    dataSource.setUsername(username);
    forceCloseAll();
  }

  public void setPassword(String password) {
    dataSource.setPassword(password);
    forceCloseAll();
  }

  public void setDefaultAutoCommit(boolean defaultAutoCommit) {
    dataSource.setAutoCommit(defaultAutoCommit);
    forceCloseAll();
  }

  public void setDefaultTransactionIsolationLevel(Integer defaultTransactionIsolationLevel) {
    dataSource.setDefaultTransactionIsolationLevel(defaultTransactionIsolationLevel);
    forceCloseAll();
  }

  public void setDriverProperties(Properties driverProps) {
    dataSource.setDriverProperties(driverProps);
    forceCloseAll();
  }

  /**
   * Sets the default network timeout value to wait for the database operation to complete. See {@link Connection#setNetworkTimeout(java.util.concurrent.Executor, int)}
   *
   * @param milliseconds
   *          The time in milliseconds to wait for the database operation to complete.
   * @since 3.5.2
   */
  public void setDefaultNetworkTimeout(Integer milliseconds) {
    dataSource.setDefaultNetworkTimeout(milliseconds);
    forceCloseAll();
  }

  /**
   * The maximum number of active connections.
   *
   * @param poolMaximumActiveConnections
   *          The maximum number of active connections
   */
  public void setPoolMaximumActiveConnections(int poolMaximumActiveConnections) {
    this.poolMaximumActiveConnections = poolMaximumActiveConnections;
    forceCloseAll();
  }

  /**
   * The maximum number of idle connections.
   *
   * @param poolMaximumIdleConnections
   *          The maximum number of idle connections
   */
  public void setPoolMaximumIdleConnections(int poolMaximumIdleConnections) {
    this.poolMaximumIdleConnections = poolMaximumIdleConnections;
    forceCloseAll();
  }

  /**
   * The maximum number of tolerance for bad connection happens in one thread
   * which are applying for new {@link PooledConnection}.
   *
   * @param poolMaximumLocalBadConnectionTolerance
   *          max tolerance for bad connection happens in one thread
   *
   * @since 3.4.5
   */
  public void setPoolMaximumLocalBadConnectionTolerance(
      int poolMaximumLocalBadConnectionTolerance) {
    this.poolMaximumLocalBadConnectionTolerance = poolMaximumLocalBadConnectionTolerance;
  }

  /**
   * The maximum time a connection can be used before it *may* be
   * given away again.
   *
   * @param poolMaximumCheckoutTime
   *          The maximum time
   */
  public void setPoolMaximumCheckoutTime(int poolMaximumCheckoutTime) {
    this.poolMaximumCheckoutTime = poolMaximumCheckoutTime;
    forceCloseAll();
  }

  /**
   * The time to wait before retrying to get a connection.
   *
   * @param poolTimeToWait
   *          The time to wait
   */
  public void setPoolTimeToWait(int poolTimeToWait) {
    this.poolTimeToWait = poolTimeToWait;
    forceCloseAll();
  }

  /**
   * The query to be used to check a connection.
   *
   * @param poolPingQuery
   *          The query
   */
  public void setPoolPingQuery(String poolPingQuery) {
    this.poolPingQuery = poolPingQuery;
    forceCloseAll();
  }

  /**
   * Determines if the ping query should be used.
   *
   * @param poolPingEnabled
   *          True if we need to check a connection before using it
   */
  public void setPoolPingEnabled(boolean poolPingEnabled) {
    this.poolPingEnabled = poolPingEnabled;
    forceCloseAll();
  }

  /**
   * If a connection has not been used in this many milliseconds, ping the
   * database to make sure the connection is still good.
   *
   * @param milliseconds
   *          the number of milliseconds of inactivity that will trigger a ping
   */
  public void setPoolPingConnectionsNotUsedFor(int milliseconds) {
    this.poolPingConnectionsNotUsedFor = milliseconds;
    forceCloseAll();
  }

  public String getDriver() {
    return dataSource.getDriver();
  }

  public String getUrl() {
    return dataSource.getUrl();
  }

  public String getUsername() {
    return dataSource.getUsername();
  }

  public String getPassword() {
    return dataSource.getPassword();
  }

  public boolean isAutoCommit() {
    return dataSource.isAutoCommit();
  }

  public Integer getDefaultTransactionIsolationLevel() {
    return dataSource.getDefaultTransactionIsolationLevel();
  }

  public Properties getDriverProperties() {
    return dataSource.getDriverProperties();
  }

  /**
   * Gets the default network timeout.
   *
   * @return the default network timeout
   * @since 3.5.2
   */
  public Integer getDefaultNetworkTimeout() {
    return dataSource.getDefaultNetworkTimeout();
  }

  public int getPoolMaximumActiveConnections() {
    return poolMaximumActiveConnections;
  }

  public int getPoolMaximumIdleConnections() {
    return poolMaximumIdleConnections;
  }

  public int getPoolMaximumLocalBadConnectionTolerance() {
    return poolMaximumLocalBadConnectionTolerance;
  }

  public int getPoolMaximumCheckoutTime() {
    return poolMaximumCheckoutTime;
  }

  public int getPoolTimeToWait() {
    return poolTimeToWait;
  }

  public String getPoolPingQuery() {
    return poolPingQuery;
  }

  public boolean isPoolPingEnabled() {
    return poolPingEnabled;
  }

  public int getPoolPingConnectionsNotUsedFor() {
    return poolPingConnectionsNotUsedFor;
  }

  /**
   * 关闭所有的连接
   *
   * Closes all active and idle connections in the pool.
   */
  public void forceCloseAll() {
    // 同步
    synchronized (state) {
      expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
      // 处理活跃连接
      for (int i = state.activeConnections.size(); i > 0; i--) {
        try {
          // 删除活跃连接
          PooledConnection conn = state.activeConnections.remove(i - 1);
          // 标记为无效
          conn.invalidate();
          // 获取真实连接
          Connection realConn = conn.getRealConnection();
          // 是否是自动提交
          if (!realConn.getAutoCommit()) {
            // 回滚
            realConn.rollback();
          }
          // 关闭真实连接
          realConn.close();
        } catch (Exception e) {
          // ignore
        }
      }
      // 处理空闲连接
      for (int i = state.idleConnections.size(); i > 0; i--) {
        try {
          // 删除空闲连接
          PooledConnection conn = state.idleConnections.remove(i - 1);
          // 标记为无效
          conn.invalidate();
          // 获取真实连接
          Connection realConn = conn.getRealConnection();
          // 是否是自动提交
          if (!realConn.getAutoCommit()) {
            // 回滚
            realConn.rollback();
          }
          // 关闭真实连接
          realConn.close();
        } catch (Exception e) {
          // ignore
        }
      }
    }
    if (log.isDebugEnabled()) {
      log.debug("PooledDataSource forcefully closed/removed all connections.");
    }
  }

  public PoolState getPoolState() {
    return state;
  }

  private int assembleConnectionTypeCode(String url, String username, String password) {
    return ("" + url + username + password).hashCode();
  }

  /**
   * 归还连接
   *
   * @param conn
   * @throws SQLException
   */
  protected void pushConnection(PooledConnection conn) throws SQLException {

    synchronized (state) {
      // 将当前连接从活跃获取连接集合中删除
      state.activeConnections.remove(conn);
      // 判断连接是否有效
      if (conn.isValid()) {
        // 当前池空闲数小于配置的最大空闲数
        if (state.idleConnections.size() < poolMaximumIdleConnections && conn.getConnectionTypeCode() == expectedConnectionTypeCode) {
          state.accumulatedCheckoutTime += conn.getCheckoutTime();
          if (!conn.getRealConnection().getAutoCommit()) {
            conn.getRealConnection().rollback();
          }
          // 创建新的 PooledConnection 对象（将真实连接取出）
          PooledConnection newConn = new PooledConnection(conn.getRealConnection(), this);
          // 新的连接对象添加到空闲连接集合中
          state.idleConnections.add(newConn);
          newConn.setCreatedTimestamp(conn.getCreatedTimestamp());
          newConn.setLastUsedTimestamp(conn.getLastUsedTimestamp());
          // 当前 PooledConnection 无效
          conn.invalidate();
          if (log.isDebugEnabled()) {
            log.debug("Returned connection " + newConn.getRealHashCode() + " to pool.");
          }
          // 通知所有的线程
          state.notifyAll();
        } else {
          // 空闲数量达到上限
          state.accumulatedCheckoutTime += conn.getCheckoutTime();
          // 回滚数据
          if (!conn.getRealConnection().getAutoCommit()) {
            conn.getRealConnection().rollback();
          }
          // 关闭连接
          conn.getRealConnection().close();
          if (log.isDebugEnabled()) {
            log.debug("Closed connection " + conn.getRealHashCode() + ".");
          }
          // 无效连接
          conn.invalidate();
        }
      } else {
        if (log.isDebugEnabled()) {
          log.debug("A bad connection (" + conn.getRealHashCode() + ") attempted to return to the pool, discarding connection.");
        }
        state.badConnectionCount++;
      }
    }
  }

  /**
   * 获取连接
   *
   * @param username
   * @param password
   * @return
   * @throws SQLException
   */
  private PooledConnection popConnection(String username, String password) throws SQLException {
    boolean countedWait = false;
    PooledConnection conn = null;
    long t = System.currentTimeMillis();
    int localBadConnectionCount = 0;

    while (conn == null) {
      // 同步
      synchronized (state) {
        // 判断有空闲的连接
        if (!state.idleConnections.isEmpty()) {
          // 获取空闲连接
          // Pool has available connection
          conn = state.idleConnections.remove(0);
          if (log.isDebugEnabled()) {
            log.debug("Checked out connection " + conn.getRealHashCode() + " from pool.");
          }
        } else {
          // 没有空闲连接
          // Pool does not have available connection
          // 活跃连接数小于最大连接数
          if (state.activeConnections.size() < poolMaximumActiveConnections) {
            // Can create new connection
            // 创建新的连接
            conn = new PooledConnection(dataSource.getConnection(), this);
            if (log.isDebugEnabled()) {
              log.debug("Created connection " + conn.getRealHashCode() + ".");
            }
          } else {
            // 活跃连接数达到最大连接数
            // Cannot create new connection
            // 获取最先创建的活跃连接
            PooledConnection oldestActiveConnection = state.activeConnections.get(0);
            // 获取该连接的使用时长
            long longestCheckoutTime = oldestActiveConnection.getCheckoutTime();
            // 判断处理时长是否超时
            if (longestCheckoutTime > poolMaximumCheckoutTime) {
              // Can claim overdue connection
              // 记录超时数量
              state.claimedOverdueConnectionCount++;
              // 记录超时时长
              state.accumulatedCheckoutTimeOfOverdueConnections += longestCheckoutTime;
              // 记录处理时长
              state.accumulatedCheckoutTime += longestCheckoutTime;
              // 从活跃连接中删除该连接
              state.activeConnections.remove(oldestActiveConnection);
              // 判断需要移除的连接是否设置自动提交
              if (!oldestActiveConnection.getRealConnection().getAutoCommit()) {
                try {
                  // 将其回滚
                  oldestActiveConnection.getRealConnection().rollback();
                } catch (SQLException e) {
                  /*
                     Just log a message for debug and continue to execute the following
                     statement like nothing happened.
                     Wrap the bad connection with a new PooledConnection, this will help
                     to not interrupt current executing thread and give current thread a
                     chance to join the next competition for another valid/good database
                     connection. At the end of this loop, bad {@link @conn} will be set as null.
                   */
                  log.debug("Bad connection. Could not roll back");
                }
              }
              // 创建新的连接
              conn = new PooledConnection(oldestActiveConnection.getRealConnection(), this);
              conn.setCreatedTimestamp(oldestActiveConnection.getCreatedTimestamp());
              conn.setLastUsedTimestamp(oldestActiveConnection.getLastUsedTimestamp());
              // 将超时的线程设置为无效
              oldestActiveConnection.invalidate();
              if (log.isDebugEnabled()) {
                log.debug("Claimed overdue connection " + conn.getRealHashCode() + ".");
              }
            } else {
              // 没有空闲连接,并且达到最大连接数, 也没有连接超时。只能等待
              // Must wait
              try {
                if (!countedWait) {
                  // 记录等待次数
                  state.hadToWaitCount++;
                  countedWait = true;
                }
                if (log.isDebugEnabled()) {
                  log.debug("Waiting as long as " + poolTimeToWait + " milliseconds for connection.");
                }
                long wt = System.currentTimeMillis();
                // 当前线程等待指定时间
                state.wait(poolTimeToWait);
                // 累加等待时间
                state.accumulatedWaitTime += System.currentTimeMillis() - wt;
              } catch (InterruptedException e) {
                // 如果线程中断则直接退出
                break;
              }
            }
          }
        }
        // 判断是否获取到连接, 如果没有则继续循环
        if (conn != null) {
          // ping to server and check the connection is valid or not
          // 判断链接是否有效
          if (conn.isValid()) {
            // 如果无效并且设置了自动提交，将其回滚
            if (!conn.getRealConnection().getAutoCommit()) {
              conn.getRealConnection().rollback();
            }
            //
            conn.setConnectionTypeCode(assembleConnectionTypeCode(dataSource.getUrl(), username, password));
            // 记录从池中取出的时间
            conn.setCheckoutTimestamp(System.currentTimeMillis());
            // 记录最后一次使用的时间
            conn.setLastUsedTimestamp(System.currentTimeMillis());
            // 将其加入到活跃连接池中
            state.activeConnections.add(conn);
            // 记录请求数量
            state.requestCount++;
            // 记录请求时间(累加)
            state.accumulatedRequestTime += System.currentTimeMillis() - t;
          } else {
            // 连接无效
            if (log.isDebugEnabled()) {
              log.debug("A bad connection (" + conn.getRealHashCode() + ") was returned from the pool, getting another connection.");
            }
            // 记录无效的连接数量
            state.badConnectionCount++;
            // 记录无效的连接数量（本次操作）
            localBadConnectionCount++;
            conn = null;
            // 无效连接数超过 （最大空闲数+单次获取无效连接的次数）
            if (localBadConnectionCount > (poolMaximumIdleConnections + poolMaximumLocalBadConnectionTolerance)) {
              if (log.isDebugEnabled()) {
                log.debug("PooledDataSource: Could not get a good connection to the database.");
              }
              throw new SQLException("PooledDataSource: Could not get a good connection to the database.");
            }
          }
        }
      }

    }

    if (conn == null) {
      if (log.isDebugEnabled()) {
        log.debug("PooledDataSource: Unknown severe error condition.  The connection pool returned a null connection.");
      }
      throw new SQLException("PooledDataSource: Unknown severe error condition.  The connection pool returned a null connection.");
    }

    return conn;
  }

  /**
   * 测试连接时可用
   * Method to check to see if a connection is still usable
   *
   * @param conn
   *          - the connection to check
   * @return True if the connection is still usable
   */
  protected boolean pingConnection(PooledConnection conn) {
    boolean result = true;

    try {
      // 真正的连接是否关闭
      result = !conn.getRealConnection().isClosed();
    } catch (SQLException e) {
      if (log.isDebugEnabled()) {
        log.debug("Connection " + conn.getRealHashCode() + " is BAD: " + e.getMessage());
      }
      result = false;
    }

    // 连接未关闭 并且 允许发送测试SQL 并且判断连接未使用的时长(当前时间减去上一次使用时间)是否超过设置的时间(poolPingConnectionsNotUsedFor)
    if (result && poolPingEnabled && poolPingConnectionsNotUsedFor >= 0
        && conn.getTimeElapsedSinceLastUse() > poolPingConnectionsNotUsedFor) {
      try {
        if (log.isDebugEnabled()) {
          log.debug("Testing connection " + conn.getRealHashCode() + " ...");
        }
        // 获取真正的连接
        Connection realConn = conn.getRealConnection();
        // 执行测试sql
        try (Statement statement = realConn.createStatement()) {
          statement.executeQuery(poolPingQuery).close();
        }
        // 如果设置自动提交则回滚
        if (!realConn.getAutoCommit()) {
          realConn.rollback();
        }
        // 标记连接有效
        result = true;
        if (log.isDebugEnabled()) {
          log.debug("Connection " + conn.getRealHashCode() + " is GOOD!");
        }
      } catch (Exception e) {
        // 发生异常表示该连接无效
        log.warn("Execution of ping query '" + poolPingQuery + "' failed: " + e.getMessage());
        try {
          // 关闭连接
          conn.getRealConnection().close();
        } catch (Exception e2) {
          // ignore
        }
        result = false;
        if (log.isDebugEnabled()) {
          log.debug("Connection " + conn.getRealHashCode() + " is BAD: " + e.getMessage());
        }
      }
    }
    return result;
  }

  /**
   * Unwraps a pooled connection to get to the 'real' connection
   *
   * @param conn
   *          - the pooled connection to unwrap
   * @return The 'real' connection
   */
  public static Connection unwrapConnection(Connection conn) {
    if (Proxy.isProxyClass(conn.getClass())) {
      InvocationHandler handler = Proxy.getInvocationHandler(conn);
      if (handler instanceof PooledConnection) {
        return ((PooledConnection) handler).getRealConnection();
      }
    }
    return conn;
  }

  /**
   * 对象回收
   *
   * @throws Throwable
   */
  @Override
  protected void finalize() throws Throwable {
    // 关闭所有连接
    forceCloseAll();
    super.finalize();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    throw new SQLException(getClass().getName() + " is not a wrapper.");
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return false;
  }

  @Override
  public Logger getParentLogger() {
    return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
  }

}
