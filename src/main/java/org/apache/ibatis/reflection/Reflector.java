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
package org.apache.ibatis.reflection;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.AmbiguousMethodInvoker;
import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;
import org.apache.ibatis.util.MapUtil;


/**
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {

  // 对应的class类型
  private final Class<?> type;

  // 可读属性的名称集合,可读属性就是存在相应的getter方法的属性
  private final String[] readablePropertyNames;

  // 可写属性的名称集合,可读属性就是存在相应的setter方法的属性
  private final String[] writablePropertyNames;

  // 记录了属性对应的setter方法,key是属性名,value是Invoker对象
  private final Map<String, Invoker> setMethods = new HashMap<>();

  // 记录了属性对应的getter方法,key是属性名,value是Invoker对象
  private final Map<String, Invoker> getMethods = new HashMap<>();

  // 记录了属性对应的setter方法的参数值类型,key是属性名称,value是setter方法的参数类型
  private final Map<String, Class<?>> setTypes = new HashMap<>();

  // 记录了属性对应的getter方法的参数值类型,key是属性名称,value是getter方法的返回类型
  private final Map<String, Class<?>> getTypes = new HashMap<>();

  // 默认构造方法
  private Constructor<?> defaultConstructor;

  // 记录了所有属性名称的集合, key是大写属性名称,value是属性名称
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

  public Reflector(Class<?> clazz) {
    // 初始化
    type = clazz;

    // 查找默认的构造方法. defaultConstructor
    addDefaultConstructor(clazz);

    // 获取所有的方法(包含父类)
    Method[] classMethods = getClassMethods(clazz);

    // 处理所有getter方法,将方法添加到getMethods集合将方法参数值添加到getTypes集合
    addGetMethods(classMethods);

    // 处理所有setter方法,将方法添加到setMethods集合将方法返回值添加到setTypes集合
    addSetMethods(classMethods);

    // 处理没有getter/setter方法的字段
    addFields(clazz);

    // 读属性名数组赋值
    readablePropertyNames = getMethods.keySet().toArray(new String[0]);

    // 写属性名数组赋值
    writablePropertyNames = setMethods.keySet().toArray(new String[0]);

    // 将读属性名放入caseInsensitivePropertyMap集合
    for (String propName : readablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    // 将写属性名放入caseInsensitivePropertyMap集合
    for (String propName : writablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  private void addDefaultConstructor(Class<?> clazz) {
    // 获取clazz类定义的全部构造方法
    Constructor<?>[] constructors = clazz.getDeclaredConstructors();
    // 过滤出无参构造
    Arrays.stream(constructors).filter(constructor -> constructor.getParameterTypes().length == 0)
      .findAny().ifPresent(constructor -> this.defaultConstructor = constructor);
  }

  /**
   * 父类 -> List<String> getNames();
   * 子类 -> ArrayList<String> getNames();
   * 这种情况是合法的
   */
  private void addGetMethods(Method[] methods) {
    // 记录冲突的方法(key是属性名(getNames就是names),value是方法列表如果有冲突则会存在多个)
    Map<String, List<Method>> conflictingGetters = new HashMap<>();

    // 1. filter() 判断方法名是否是get或者is开头
    // 2. forEach() 将过滤后的方法保存到conflictingGetters中
    Arrays.stream(methods)
      .filter(m -> m.getParameterTypes().length == 0 && PropertyNamer.isGetter(m.getName()))
      .forEach(m -> addMethodConflict(conflictingGetters, PropertyNamer.methodToProperty(m.getName()), m));

    // 处理getter方法冲突
    resolveGetterConflicts(conflictingGetters);
  }

  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
    for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
      // 获胜者
      Method winner = null;
      // 属性名
      String propName = entry.getKey();
      // 是否出现歧义
      boolean isAmbiguous = false;
      // 遍历冲突方法
      for (Method candidate : entry.getValue()) {
        // 当获胜者为空时,将当前方法赋值给它
        if (winner == null) {
          winner = candidate;
          continue;
        }
        // 获胜者的返回值
        Class<?> winnerType = winner.getReturnType();
        // 候选者的返回值
        Class<?> candidateType = candidate.getReturnType();

        // 如果候选者与胜利者的返回值一致
        if (candidateType.equals(winnerType)) {
          // 不是boolean类型(方法名与参数都一致),出现歧义
          if (!boolean.class.equals(candidateType)) {
            isAmbiguous = true;
            break;
            // 如果是is开头,候选者则胜出
          } else if (candidate.getName().startsWith("is")) {
            winner = candidate;
          }
          // 胜利者的返回值是候选者的子类,出现歧义
        } else if (candidateType.isAssignableFrom(winnerType)) {
          // OK getter type is descendant
          // 候选者的返回值是获胜者的子类,候选者则胜出
        } else if (winnerType.isAssignableFrom(candidateType)) {
          winner = candidate;
          // 候选者与胜利者的返回值无任何关系,出现歧义
        } else {
          isAmbiguous = true;
          break;
        }
      }
      // 保存getter方法
      addGetMethod(propName, winner, isAmbiguous);
    }
  }

  private void addGetMethod(String name, Method method, boolean isAmbiguous) {
    // 将方法包装成MethodInvoker对象,如果出现歧义则在调用时会出现异常
    MethodInvoker invoker = isAmbiguous
        ? new AmbiguousMethodInvoker(method, MessageFormat.format(
            "Illegal overloaded getter method with ambiguous type for property ''{0}'' in class ''{1}''. This breaks the JavaBeans specification and can cause unpredictable results.",
            name, method.getDeclaringClass().getName()))
        : new MethodInvoker(method);
    // 将方法保存(属性名,MethodInvoker)
    getMethods.put(name, invoker);
    // 获取返回值类型
    Type returnType = TypeParameterResolver.resolveReturnType(method, type);
    // 保存返回值(属性名,返回值)
    getTypes.put(name, typeToClass(returnType));
  }

  /**
   * 逻辑与添加getter方法类似
   * @see #addGetMethods
   */
  private void addSetMethods(Method[] methods) {
    Map<String, List<Method>> conflictingSetters = new HashMap<>();
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 1 && PropertyNamer.isSetter(m.getName()))
      .forEach(m -> addMethodConflict(conflictingSetters, PropertyNamer.methodToProperty(m.getName()), m));

    // 处理setter方法冲突
    resolveSetterConflicts(conflictingSetters);
  }

  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    if (isValidPropertyName(name)) {
      List<Method> list = MapUtil.computeIfAbsent(conflictingMethods, name, k -> new ArrayList<>());
      list.add(method);
    }
  }

  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    for (Entry<String, List<Method>> entry : conflictingSetters.entrySet()) {
      // 属性名
      String propName = entry.getKey();
      // setter方法列表
      List<Method> setters = entry.getValue();
      // getter方法的返回值
      Class<?> getterType = getTypes.get(propName);

      // getter方法是否存在歧义
      boolean isGetterAmbiguous = getMethods.get(propName) instanceof AmbiguousMethodInvoker;
      // setter方法是否存在歧义
      boolean isSetterAmbiguous = false;
      // 匹配的setter方法
      Method match = null;
      for (Method setter : setters) {
        // getter方法不存在歧义并且参数值与getter的返回值一致
        if (!isGetterAmbiguous && setter.getParameterTypes()[0].equals(getterType)) {
          // should be the best match
          match = setter;
          break;
        }
        // setter方法不存在歧义
        if (!isSetterAmbiguous) {
          // 选择合适的setter方法;
          match = pickBetterSetter(match, setter, propName);
          // 返回为空(match == null)表示存在歧义
          isSetterAmbiguous = match == null;
        }
      }
      // 不存在歧义
      if (match != null) {
        addSetMethod(propName, match);
      }
    }
  }

  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    if (setter1 == null) {
      return setter2;
    }
    Class<?> paramType1 = setter1.getParameterTypes()[0];
    Class<?> paramType2 = setter2.getParameterTypes()[0];

    // 判断参数值是否存在父子关系
    if (paramType1.isAssignableFrom(paramType2)) {
      return setter2;
    } else if (paramType2.isAssignableFrom(paramType1)) {
      return setter1;
    }
    // 将方法包装成有歧义的MethodInvoker
    MethodInvoker invoker = new AmbiguousMethodInvoker(setter1,
        MessageFormat.format(
            "Ambiguous setters defined for property ''{0}'' in class ''{1}'' with types ''{2}'' and ''{3}''.",
            property, setter2.getDeclaringClass().getName(), paramType1.getName(), paramType2.getName()));
    // 保存方法(属性名,MethodInvoker)
    setMethods.put(property, invoker);
    // 获取参数值
    Type[] paramTypes = TypeParameterResolver.resolveParamTypes(setter1, type);
    // 保存参数值(属性名,参数值)
    setTypes.put(property, typeToClass(paramTypes[0]));

    // 返回null
    return null;
  }

  private void addSetMethod(String name, Method method) {
    // 将方法包装成MethodInvoker
    MethodInvoker invoker = new MethodInvoker(method);
    // 保存方法(属性名,MethodInvoker)
    setMethods.put(name, invoker);
    // 获取参数值
    Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
    // 保存参数值(属性名,参数值)
    setTypes.put(name, typeToClass(paramTypes[0]));
  }

  private Class<?> typeToClass(Type src) {
    Class<?> result = null;
    if (src instanceof Class) {
      result = (Class<?>) src;
    } else if (src instanceof ParameterizedType) {
      result = (Class<?>) ((ParameterizedType) src).getRawType();
    } else if (src instanceof GenericArrayType) {
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      if (componentType instanceof Class) {
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      } else {
        Class<?> componentClass = typeToClass(componentType);
        result = Array.newInstance(componentClass, 0).getClass();
      }
    }
    if (result == null) {
      result = Object.class;
    }
    return result;
  }

  private void addFields(Class<?> clazz) {
    // 获取clazz中定义的全部字段
    Field[] fields = clazz.getDeclaredFields();
    for (Field field : fields) {
      // 当属性不存在setMethods中,将其保存到setMethods与setTypes中
      if (!setMethods.containsKey(field.getName())) {
        // issue #379 - removed the check for final because JDK 1.5 allows
        // modification of final fields through reflection (JSR-133). (JGB)
        // pr #16 - final static can only be set by the classloader
        // 过滤字段修饰符为final与static
        int modifiers = field.getModifiers();
        if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
          // 将字段保存到setMethods与setTypes中
          addSetField(field);
        }
      }
      // 当属性不存在getMethods中,将其保存到getMethods与getTypes中
      if (!getMethods.containsKey(field.getName())) {
        // 将字段保存到getMethods与getTypes中
        addGetField(field);
      }
    }
    // 递归获取父类的字段
    if (clazz.getSuperclass() != null) {
      addFields(clazz.getSuperclass());
    }
  }

  private void addSetField(Field field) {
    // 校验属性名是否合法
    if (isValidPropertyName(field.getName())) {
      // 对于没有setter方法的字段,将其包装成SetFieldInvoker
      // 保存方法(属性名,GetFieldInvoker)
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      // 获取字段类型
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      // 保存参数值(属性名,字段类型)
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private void addGetField(Field field) {
    // 校验属性名是否合法
    if (isValidPropertyName(field.getName())) {
      // 对于没有getter方法的字段,将其包装成GetFieldInvoker
      // 保存方法(属性名,GetFieldInvoker)
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      // 获取字段类型
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      // 保存返回值(属性名,字段类型)
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private boolean isValidPropertyName(String name) {
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

  /**
   * This method returns an array containing all methods
   * declared in this class and any superclass.
   * We use this method, instead of the simpler <code>Class.getMethods()</code>,
   * because we want to look for private methods as well.
   *
   * @param clazz The class
   * @return An array containing all methods in this class
   */
  private Method[] getClassMethods(Class<?> clazz) {
    Map<String, Method> uniqueMethods = new HashMap<>();

    // 当前类
    Class<?> currentClass = clazz;
    while (currentClass != null && currentClass != Object.class) {

      // 保存当前类所有的方法
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      // 保存所有接口的方法
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }

      // 获取父类,继续循环
      currentClass = currentClass.getSuperclass();
    }

    Collection<Method> methods = uniqueMethods.values();
    return methods.toArray(new Method[0]);
  }

  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {
      if (!currentMethod.isBridge()) {
        // 根据方法生成方法签名(格式[返回值#方法名:参数名称1,参数名称2...])
        //
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        // 判断方法是否存在(假设子类已经添加过,则无需再添加)
        if (!uniqueMethods.containsKey(signature)) {
          // 记录方法
          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  /**
   * 根据方法生成签名
   *
   * @param method
   * @return 方法返回值#方法名称:参数名称1,参数名称2
   */
  private String getSignature(Method method) {
    StringBuilder sb = new StringBuilder();
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      sb.append(returnType.getName()).append('#');
    }
    sb.append(method.getName());
    Class<?>[] parameters = method.getParameterTypes();

    for (int i = 0; i < parameters.length; i++) {
      sb.append(i == 0 ? ':' : ',').append(parameters[i].getName());
    }
    return sb.toString();
  }

  /**
   * Checks whether can control member accessible.
   *
   * @return If can control member accessible, it return {@literal true}
   * @since 3.5.0
   */
  public static boolean canControlMemberAccessible() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /**
   * Gets the name of the class the instance provides information for.
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    } else {
      throw new ReflectionException("There is no default constructor for " + type);
    }
  }

  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * Gets the type for a property setter.
   *
   * @param propertyName - the name of the property
   * @return The Class of the property setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets the type for a property getter.
   *
   * @param propertyName - the name of the property
   * @return The Class of the property getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets an array of the readable properties for an object.
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /**
   * Gets an array of the writable properties for an object.
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writablePropertyNames;
  }

  /**
   * Check to see if a class has a writable property by name.
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a writable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.containsKey(propertyName);
  }

  /**
   * Check to see if a class has a readable property by name.
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.containsKey(propertyName);
  }

  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }
}
