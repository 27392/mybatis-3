package cn.haohaoli.reflection;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.util.List;

/**
 * @author lwh
 */
public class GenericArrayTypeTest {

  static class P<T> {

    T[] data;

    List<Integer>[] integerList;
    List<T>[]       list;
  }

  public static void main(String[] args) {
    Field[] declaredFields = P.class.getDeclaredFields();
    for (Field declaredField : declaredFields) {
      GenericArrayType genericType = (GenericArrayType) declaredField.getGenericType();
      System.out.println(genericType.getGenericComponentType());
    }
  }
}
