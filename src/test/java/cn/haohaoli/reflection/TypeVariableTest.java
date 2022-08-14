package cn.haohaoli.reflection;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author lwh
 */
public class TypeVariableTest {

  static class P<T extends Comparable<T>> {
    private T data;
  }

  public static void main(String[] args) {
    Field[] fields = P.class.getDeclaredFields();
    for (Field field : fields) {
      TypeVariable typeVariable = (TypeVariable) field.getGenericType();
      System.out.println(field.getName());
      System.out.println("getName() : " + typeVariable.getName());
      System.out.println("getAnnotatedBounds() : " + Arrays.toString(typeVariable.getAnnotatedBounds()));
      System.out.println("getGenericDeclaration() : " + typeVariable.getGenericDeclaration());
      System.out.println("getBounds() : " + Arrays.toString(typeVariable.getBounds()));
      System.out.println();
    }
  }
}
