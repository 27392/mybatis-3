package cn.haohaoli;

import org.apache.ibatis.reflection.Reflector;

import java.util.ArrayList;
import java.util.List;

/**
 * @author lwh
 */
public class ReflectorTest {

  static class A{
    private List<String> names;

    public List<String> getNames(){

      return null;
    }
  }

  static class B extends A{
    @Override
    public ArrayList<String> getNames() {
      return null;
    }
  }

  public static void main(String[] args) {
    new Reflector(B.class);

  }
}
