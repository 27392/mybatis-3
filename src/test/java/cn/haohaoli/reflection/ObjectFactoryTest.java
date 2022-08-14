package cn.haohaoli.reflection;

import lombok.*;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.submitted.result_handler_type.ObjectFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author lwh
 */
public class ObjectFactoryTest {

  @Getter
  @Setter
  @ToString
  @NoArgsConstructor
  @AllArgsConstructor
  static class User {
    private String     name;
    private List<Role> roles;
  }

  @Getter
  @Setter
  @ToString
  static class Role {
    String name;
  }

  @Test
  public void create_object() {
    ObjectFactory objectFactory = new ObjectFactory();
    System.out.println(objectFactory.create(User.class));
    System.out.println(objectFactory.create(User.class, Arrays.asList(String.class, List.class), Arrays.asList("lisi", Collections.emptyList())));
    System.out.println(objectFactory.isCollection(User.class));
  }

  @Test
  public void create_object_and_metaClass() throws InvocationTargetException, IllegalAccessException {
    ObjectFactory objectFactory = new ObjectFactory();
    User          user          = objectFactory.create(User.class, Arrays.asList(String.class, List.class), Arrays.asList("lisi", Collections.emptyList()));
    MetaClass     metaClass     = MetaClass.forClass(User.class, new DefaultReflectorFactory());

    boolean has = metaClass.hasGetter("name");
    if (has) {
      Invoker name   = metaClass.getGetInvoker("name");
      System.out.println(name.invoke(user, new Object[0]));
    }
  }
}
