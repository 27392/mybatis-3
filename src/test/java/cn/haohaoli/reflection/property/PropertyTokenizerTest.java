package cn.haohaoli.reflection.property;

import org.apache.ibatis.reflection.property.PropertyTokenizer;

/**
 * @author lwh
 */
public class PropertyTokenizerTest {

  static int counter = 1;

  public static void main(String[] args) {
    PropertyTokenizer tokenizer = new PropertyTokenizer("users[0].roles[0].name");
    print(tokenizer);

    while (tokenizer.hasNext()) {
      tokenizer = tokenizer.next();
      print(tokenizer);
    }
  }

  private static void print (PropertyTokenizer tokenizer){
    System.out.println("[" + (counter++) + "] " + "name: " + tokenizer.getName() + ", index: " + tokenizer.getIndex() + ", indexName: " + tokenizer.getIndexedName() + ", children: " + tokenizer.getChildren());
  }
}
