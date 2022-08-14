package cn.haohaoli.reflection;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.reflection.wrapper.ObjectWrapper;

import java.util.Arrays;
import java.util.List;

/**
 * @author lwh
 */
public class ObjectWrapperTest {

  static final User user;

  static {
    List<Item>  itemA  = Arrays.asList(new Item("item1"), new Item("item2"));
    List<Item>  itemB  = Arrays.asList(new Item("item3"), new Item("item4"));
    List<Order> orders = Arrays.asList(new Order(itemA, new Item("item")), new Order(itemB, new Item("item")));
    user = new User(orders, null);
  }

  public static void main(String[] args) {
    MetaObject metaObject = SystemMetaObject.forObject(user);
    ObjectWrapper objectWrapper = metaObject.getObjectWrapper();
    Object o = objectWrapper.get(new PropertyTokenizer("orders[0].item"));
    boolean b = objectWrapper.hasGetter("order.item");
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
  static class Order {
    private List<Item> items;
    private Item       item;
    private static  int s;
  }

  @Getter
  @Setter
  @AllArgsConstructor
  static class Item {
    private String name;
  }
}
