package cn.haohaoli.reflection.factory;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.apache.ibatis.reflection.factory.DefaultObjectFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * @author lwh
 */
public class ObjectFactoryTest {

  public static void main(String[] args) {

    ObjectFactory objectFactory = new DefaultObjectFactory();

    // 创建对象,根据无参构造
    System.out.println(objectFactory.create(User.class));

    // 创建对象,指定构造参数
    System.out.println(objectFactory.create(User.class, Arrays.asList(String.class), Arrays.asList("gazi")));

    // 判断是否是集合
    System.out.println(objectFactory.isCollection(User.class));
    System.out.println(objectFactory.isCollection(ArrayList.class));
  }

  @NoArgsConstructor
  @AllArgsConstructor
  @ToString
  static class User {

    private String name;
  }
}
