package cn.haohaoli.reflection;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author lwh
 */
public class ParameterizedTypeTest {

  private P<String> p;

  static class P<T> {
    private T data;
  }

  private Map<Integer, List<String>> map = new HashMap<>();

  public static void main(String[] args) {
    Field[] fields = ParameterizedTypeTest.class.getDeclaredFields();
    for (Field field : fields) {
      ParameterizedType parameterizedType = (ParameterizedType) field.getGenericType();
      System.out.println(field.getName());
      System.out.println("getOwnerType() : " + parameterizedType.getOwnerType());
      System.out.println("getRawType() : " + parameterizedType.getRawType());
      System.out.println("getActualTypeArguments() : " + Arrays.toString(parameterizedType.getActualTypeArguments()));
      System.out.println();
    }
  }
}
