package cn.haohaoli;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

/**
 * @author lwh
 */
@Getter
@Setter
@ToString
public class User {

  private Integer id;
  private String  name;
  private Integer age;

  private List<Order> orderList;

}
