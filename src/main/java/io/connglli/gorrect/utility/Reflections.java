package io.connglli.gorrect.utility;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class Reflections {

  /*
   * Returns a list of fields in the given class and its superclasses that
   * are annotated with the specified annotation. The fields are made accessible
   * if they are private.
   */
  public static List<Field>
  getAnnotatedFields(Object object, Class<? extends Annotation> annotation) {
    Class<?> clazz = object.getClass();
    List<Field> fields = new ArrayList<>();
    while (clazz != null) {
      for (Field field : clazz.getDeclaredFields()) {
        if (field.isAnnotationPresent(annotation)) {
          fields.add(field);
        }
      }
      clazz = clazz.getSuperclass();
    }
    return fields;
  }
}
