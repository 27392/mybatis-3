package cn.haohaoli.reflection;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

/**
 * @author lwh
 */
public class MetaClassTest {

  static final User user;

  static {
    List<Item>  itemA  = Arrays.asList(new Item("item1"), new Item("item2"));
    List<Item>  itemB  = Arrays.asList(new Item("item3"), new Item("item4"));
    List<Order> orders = Arrays.asList(new Order(itemA, new Item("item")), new Order(itemB, new Item("item")));
    user = new User(null, new Order(itemA, new Item("item")));
  }

  @Test
  public void test(){
    MetaClass metaClass = MetaClass.forClass(User.class, new DefaultReflectorFactory());
    System.out.println(metaClass.hasGetter("order.item.name"));
    System.out.println(metaClass.hasSetter("order.item.name"));
    System.out.println(metaClass.getGetterType("order.item"));
    System.out.println(metaClass.getSetterType("order.item"));
    System.out.println(metaClass.findProperty("order.item"));
    System.out.println(metaClass.findProperty("order.item.name"));
    System.out.println(metaClass.findProperty("oreds"));
    System.out.println(metaClass.findProperty("order"));
  }

  @Getter
  @Setter
  @AllArgsConstructor
  static class User {
    private List<List<Order>> orders;
    private Order       order;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  static class Order {
    private List<Item> items;
    private Item       item;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  static class Item {
    private String name;
  }
}
