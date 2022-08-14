package cn.haohaoli.reflection.property;

import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * @author lwh
 */
public class PropertyNamerTest {

  public static void main(String[] args) {
    System.out.println(PropertyNamer.methodToProperty("isa"));
    System.out.println(PropertyNamer.methodToProperty("isA"));
    System.out.println(PropertyNamer.methodToProperty("geta"));
    System.out.println(PropertyNamer.methodToProperty("getA"));
    System.out.println(PropertyNamer.methodToProperty("seta"));
    System.out.println(PropertyNamer.methodToProperty("setA"));
  }
}
