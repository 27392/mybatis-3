package cn.haohaoli.reflection;

import javassist.tools.reflect.Metaobject;
import lombok.*;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.wrapper.DefaultObjectWrapperFactory;
import org.apache.ibatis.submitted.result_handler_type.ObjectFactory;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

/**
 * @author lwh
 */
public class MetaObjectTest {

  static final User user;

  static {
    List<Item>  itemA  = Arrays.asList(new Item("item1"), new Item("item2"));
    List<Item>  itemB  = Arrays.asList(new Item("item3"), new Item("item4"));
    List<Order> orders = Arrays.asList(new Order(itemA, new Item("item")), new Order(itemB, new Item("item")));
    user = new User(orders, new Order(itemA, new Item("item")));
  }

  @Test
  public void test(){
    MetaObject metaObject = MetaObject.forObject(user, new ObjectFactory(), new DefaultObjectWrapperFactory(), new DefaultReflectorFactory());
//    System.out.println(metaObject.getValue("order"));
//    System.out.println(metaObject.getValue("orders"));
//    System.out.println(metaObject.getValue("orders[0]"));
//    System.out.println(metaObject.getValue("orders[0].item"));
//    System.out.println(metaObject.getValue("orders[0].items[0]"));
//    System.out.println(metaObject.getValue("orders[0].items[0].name"));

    MetaObject metaObject2 = MetaObject.forObject(new User(null, null), new ObjectFactory(), new DefaultObjectWrapperFactory(), new DefaultReflectorFactory());
    metaObject2.setValue("order.item", new Item("haha"));
    System.out.println(metaObject2.getValue("order.item"));
  }

  @Getter
  @Setter
  @AllArgsConstructor
  static class User {
    private List<Order> orders;
    private Order       order;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  @NoArgsConstructor
  static class Order {
    private List<Item> items;
    private Item       item;
  }

  @Getter
  @Setter
  @ToString
  @AllArgsConstructor
  @NoArgsConstructor
  static class Item {
    private String name;
  }
}
