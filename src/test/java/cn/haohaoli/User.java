package cn.haohaoli;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.type.Alias;

import java.util.List;

/**
 * @author lwh
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class User {

  private Integer id;
  private String  name;
  private Integer age;

  private List<Order> orderList;

  public User(@Param("id") Integer id, @Param("name") String name) {
    this.id = id;
    this.name = name;
  }
}
