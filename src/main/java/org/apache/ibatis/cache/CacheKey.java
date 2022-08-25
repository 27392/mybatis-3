/*
 *    Copyright 2009-2021 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.cache;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import org.apache.ibatis.reflection.ArrayUtil;

/**
 * 缓存key
 *
 * 可以使用多个对象生成缓存 key
 *
 * CacheKey 最终要作为键存入 HashMap(也就是 PerpetualCache 类中), 所以重写了 hashcode(), equals() 方法
 *
 * @author Clinton Begin
 */
public class CacheKey implements Cloneable, Serializable {

  private static final long serialVersionUID = 1146682552656046210L;

  public static final CacheKey NULL_CACHE_KEY = new CacheKey() {

    @Override
    public void update(Object object) {
      throw new CacheException("Not allowed to update a null cache key instance.");
    }

    @Override
    public void updateAll(Object[] objects) {
      throw new CacheException("Not allowed to update a null cache key instance.");
    }
  };

  // 参与计算 hashcode,默认值是37
  private static final int DEFAULT_MULTIPLIER = 37;
  // 默认的 hashcode 默认值是17
  private static final int DEFAULT_HASHCODE = 17;

  // 乘数,默认值37
  private final int multiplier;
  // hashcode,默认值17
  private int hashcode;
  // 所有参数 hashcode 相加的和
  private long checksum;
  // 参数数量
  private int count;
  // 8/21/2017 - Sonarlint flags this as needing to be marked transient. While true if content is not serializable, this
  // is not always true and thus should not be marked transient.
  // 参数集合
  private List<Object> updateList;

  public CacheKey() {
    this.hashcode = DEFAULT_HASHCODE;
    this.multiplier = DEFAULT_MULTIPLIER;
    this.count = 0;
    this.updateList = new ArrayList<>();
  }

  public CacheKey(Object[] objects) {
    this();
    // 使用多个对象更新
    updateAll(objects);
  }

  /**
   * 获取生成缓存 key 的参数数量
   *
   * @return
   */
  public int getUpdateCount() {
    return updateList.size();
  }

  /**
   * 使用单个对象更新
   *
   * @param object
   */
  public void update(Object object) {
    // 如果对象等于空返回1,否则获取对象的hashcode
    int baseHashCode = object == null ? 1 : ArrayUtil.hashCode(object);
    // 参数计数递增
    count++;
    // 累加参数的hashcode
    checksum += baseHashCode;
    // 当前对象的hashcode * 数量
    baseHashCode *= count;

    // 默认的 hashcode 乘积(37) * 17 + 对象的hashcode
    hashcode = multiplier * hashcode + baseHashCode;

    // 将参数保存到 updateList 集合中
    updateList.add(object);
  }

  /**
   * 使用多个对象更新
   *
   * @param objects
   */
  public void updateAll(Object[] objects) {
    // 遍历参数
    for (Object o : objects) {
      // 调用单个更新方法
      update(o);
    }
  }

  @Override
  public boolean equals(Object object) {
    // 是否同一个对象
    if (this == object) {
      return true;
    }
    // 是否类型相同
    if (!(object instanceof CacheKey)) {
      return false;
    }

    final CacheKey cacheKey = (CacheKey) object;

    // 比较 hashcode 属性
    if (hashcode != cacheKey.hashcode) {
      return false;
    }
    // 比较 checksum 属性
    if (checksum != cacheKey.checksum) {
      return false;
    }
    // 比较 count 属性
    if (count != cacheKey.count) {
      return false;
    }

    // 比较参数中的每一项
    for (int i = 0; i < updateList.size(); i++) {
      Object thisObject = updateList.get(i);
      Object thatObject = cacheKey.updateList.get(i);
      if (!ArrayUtil.equals(thisObject, thatObject)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    return hashcode;
  }

  @Override
  public String toString() {
    // 格式为 -> hashcode:checksum:每个参数值
    StringJoiner returnValue = new StringJoiner(":");
    returnValue.add(String.valueOf(hashcode));
    returnValue.add(String.valueOf(checksum));
    updateList.stream().map(ArrayUtil::toString).forEach(returnValue::add);
    return returnValue.toString();
  }

  @Override
  public CacheKey clone() throws CloneNotSupportedException {
    // 克隆 CacheKey
    CacheKey clonedCacheKey = (CacheKey) super.clone();
    clonedCacheKey.updateList = new ArrayList<>(updateList);
    return clonedCacheKey;
  }

}
