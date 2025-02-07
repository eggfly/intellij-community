// "Replace with max()" "true-preview"

import java.util.*;

public class Main {
  static class Person {
    String name;
    int age;

    public String getName() {
      return name;
    }

    public int getAge() {
      return age;
    }

    public Person(String name, int age) {
      this.name = name;
      this.age = age;
    }
  }

  public Person work() {
    List<Person> personList = Arrays.asList(
      new Person("Roman", 21),
      new Person("James", 25),
      new Person("Kelly", 12)
    );
    Person maxPerson = null;
    Comparator<Person> personComparator = Comparator.comparingInt(Person::getAge);
    for<caret>(Person p : personList) {
      if(p.getAge() > 13) {
        if (maxPerson == null || personComparator.compare(maxPerson, p) < 0) {
          maxPerson = p;
        }
      }
    }

    return maxPerson;
  }
}