package cn.haohaoli;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.Before;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * @author lwh
 */
public class LazyTest {

  private static SqlSessionFactory sqlSessionFactory;

  @BeforeAll
  static void setUp() throws IOException {
    String      resource    = "cn/haohaoli/mybatis-config.xml";
    InputStream inputStream = Resources.getResourceAsStream(resource);
    sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
  }

  @Test
  public void test() {
    SqlSession sqlSession = sqlSessionFactory.openSession();
    UserMapper mapper     = sqlSession.getMapper(UserMapper.class);
    User       user       = mapper.getById(1406840835);

    List<Order> orderList  = user.getOrderList();
    List<Order> orderList1 = user.getOrderList();
    System.out.println(orderList);
  }
}
