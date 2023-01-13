/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

import org.eclipse.jdt.annotation.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class YamlUtils {

   @Retention(RetentionPolicy.RUNTIME)
   @Target(ElementType.FIELD)
   public @interface ToYamlString {
      boolean ignore() default false;

      String name() default "";
   }

   private static CharSequence camelCaseToHyphen(final String str) {
      if (str.isEmpty())
         return str;

      final var sb = new StringBuilder();
      Boolean previousCharIsUpperCase = null;
      for (final var ch : str.toCharArray()) {
         if (Character.isUpperCase(ch)) {
            if (previousCharIsUpperCase == Boolean.FALSE) {
               sb.append('-');
            }
            sb.append(Character.toLowerCase(ch));
            previousCharIsUpperCase = Boolean.TRUE;
         } else {
            sb.append(ch);
            previousCharIsUpperCase = Boolean.FALSE;
         }
      }
      return sb;
   }

   public static String toYamlString(final Object obj) {
      final var options = new DumperOptions();
      options.setIndent(2);
      options.setPrettyFlow(true);
      options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
      final var representer = new Representer(options) {
         {
            multiRepresenters.put(java.nio.file.Path.class, obj -> representScalar(Tag.STR, obj.toString()));
         }

         @Override
         protected MappingNode representJavaBean(final Set<Property> properties, final Object javaBean) {
            if (!classTags.containsKey(javaBean.getClass())) {
               // prevent rendering of object class as first line
               addClassTag(javaBean.getClass(), Tag.MAP);
            }

            return super.representJavaBean(properties, javaBean);
         }

         @Nullable
         @Override
         protected NodeTuple representJavaBeanProperty(final Object javaBean, final Property property, final @Nullable Object propertyValue,
            final Tag customTag) {
            // ignore marked properties
            final var anno = property.getAnnotation(ToYamlString.class);
            if (anno != null && anno.ignore())
               return null;

            // transform the java bean property name to lower-hyphen format
            final var node = super.representJavaBeanProperty(javaBean, property, propertyValue == null ? "<not configured>" : propertyValue,
               customTag);
            if (node == null)
               return null;

            final var name = anno == null || anno.name().isEmpty() ? property.getName() : anno.name();
            return new NodeTuple(representData(camelCaseToHyphen(name).toString()), node.getValueNode());
         }
      };

      return new Yaml(representer, options).dump(obj);
   }

   private YamlUtils() {
   }
}
