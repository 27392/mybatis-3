package cn.haohaoli.reflection;

import org.apache.ibatis.reflection.TypeParameterResolver;
import org.junit.jupiter.api.Test;
import sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Date;

/**
 * @author lwh
 */
public class TypeParameterResolverTest {

  interface Level0<L, M, N> {
    N select(N param);
  }

  interface Level1<E, F> extends Level0<E, F, String> {

  }

  interface Level2 extends Level1<Date, Integer> {

  }

  @Test
  public void s() throws NoSuchMethodException {
    Method select = Level2.class.getMethod("select", Object.class);
    Type   type   = TypeParameterResolver.resolveReturnType(select, Level2.class);
  }

  public static void main(String[] args) throws NoSuchFieldException {

    /*Field f = ClassA.class.getDeclaredField("map");
    System.out.println(f.getGenericType()); // java.util.Map<K, V>
    System.out.println(f.getGenericType() instanceof ParameterizedType);  // true

    System.out.println("============");
    TypeParameterResolver.resolveFieldType(f, ClassA.class);

    // ParameterizedTypeImpl.make(原始类型,参数化列表,所属者类型)
    ParameterizedTypeImpl p1 = (ParameterizedTypeImpl) TypeParameterResolverTest.class.getDeclaredField("sa").getGenericType();
    ParameterizedTypeImpl p2 = ParameterizedTypeImpl.make(SubClassA.class, new Type[]{Long.class}, TypeParameterResolverTest.class);

    ParameterizedType type1 = (ParameterizedType) TypeParameterResolver.resolveFieldType(f, p1);
    ParameterizedType type2 = (ParameterizedType) TypeParameterResolver.resolveFieldType(f, p2);

    System.out.println(type1 + " - " + type2);
    // 原始类
    System.out.println("getRawType() " + type1.getRawType() + " - " + type2.getRawType());

    // 所有者类
    System.out.println("getOwnerType() " + type1.getOwnerType() + " - " + type2.getOwnerType());

    // 实际的类型参数
    System.out.println("getActualTypeArguments() " + Arrays.toString(type1.getActualTypeArguments()) + " - " + Arrays.toString(type2.getActualTypeArguments()));*/
  }
}
