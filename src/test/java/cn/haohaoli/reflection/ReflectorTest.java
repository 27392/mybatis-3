package cn.haohaoli.reflection;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.reflection.Reflector;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;

/**
 * @author lwh
 */
public class ReflectorTest {

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  static class A {
    private List<String> names;
  }

  static class B extends A {

    private String id;

    @Override
    public List<String> getNames() {
      return super.getNames();
    }
  }

  public static void main(String[] args) throws InvocationTargetException, IllegalAccessException {
    B         o         = new B();
    Reflector reflector = new Reflector(B.class);
    System.out.println("getType[获取类型]: " + reflector.getType());
    System.out.println("hasDefaultConstructor[是否有无参构造方法]: " + reflector.hasDefaultConstructor());
    System.out.println("getDefaultConstructor[获取无参构造方法]: " + reflector.getDefaultConstructor());
    System.out.println("findPropertyName[获取属性名]: " + reflector.findPropertyName("nameS"));
    System.out.println("getGetterType[获取get方法返回类型]: " + reflector.getGetterType("names"));
    System.out.println("getSetterType[获取set方法参数类型]: " + reflector.getSetterType("names"));
    System.out.println("hasGetter[是否存在get方法]: " + reflector.hasGetter("names"));
    System.out.println("hasSetter[是否存在set方法]: " + reflector.hasSetter("names"));
    System.out.println("getGetablePropertyNames[获取所有可读属性的名称]: " + Arrays.toString(reflector.getGetablePropertyNames()));
    System.out.println("getSetablePropertyNames[获取所有可写属性的名称]: " + Arrays.toString(reflector.getSetablePropertyNames()));

    System.out.println(reflector.getSetInvoker("id"));
    System.out.println(reflector.getGetInvoker("names"));

    reflector.getGetInvoker("id").invoke(o, null);
    reflector.getSetInvoker("names").invoke(o, new Object[]{Arrays.asList("1", "2")});
    System.out.println(o);
  }
}
