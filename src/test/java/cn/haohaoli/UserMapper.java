package cn.haohaoli;

import java.util.List;

/**
 * @author lwh
 */
public interface UserMapper {

  User getById(Integer id);

  List<User> selectList();

  int updateById(Integer age, Integer id);
}
