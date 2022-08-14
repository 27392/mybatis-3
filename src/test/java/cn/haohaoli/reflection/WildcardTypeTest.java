package cn.haohaoli.reflection;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author lwh
 */
public class WildcardTypeTest {

  static class P {

    Map<? extends String, ? super Integer> map = new HashMap<>();
  }

  public static void main(String[] args) {
    Field[] declaredFields = P.class.getDeclaredFields();
    for (Field declaredField : declaredFields) {
      ParameterizedType parameterizedType = (ParameterizedType) declaredField.getGenericType();
      Type[]            actualTypeArguments = parameterizedType.getActualTypeArguments();
      for (Type actualTypeArgument : actualTypeArguments) {
        if (actualTypeArgument instanceof WildcardType){
          System.out.println(actualTypeArgument.getTypeName());
          System.out.println("getUpperBounds() : "+Arrays.toString(((WildcardType) actualTypeArgument).getUpperBounds()));
          System.out.println("getLowerBounds() : "+Arrays.toString(((WildcardType) actualTypeArgument).getLowerBounds()));
          System.out.println();
        }
      }
    }
  }
}
