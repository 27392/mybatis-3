package cn.haohaoli.reflection.property;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.apache.ibatis.reflection.property.PropertyCopier;

/**
 * @author lwh
 */
public class PropertyCopierTest {

  @ToString
  @AllArgsConstructor
  @NoArgsConstructor
  static class A {
    private Integer id;
    private String  name;
  }

  public static void main(String[] args) {

    A a = new A(1, "小明");

    A copy = new A();
    PropertyCopier.copyBeanProperties(A.class, a, copy);

    System.out.println(a);
    System.out.println(copy);
    System.out.println(copy == a);
  }
}
