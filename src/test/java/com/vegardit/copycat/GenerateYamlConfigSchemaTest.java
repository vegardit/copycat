/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;

import com.vegardit.copycat.command.sync.SyncCommandConfig;
import com.vegardit.copycat.command.watch.WatchCommandConfig;
import com.vegardit.copycat.util.YamlUtils;
import com.vegardit.copycat.util.YamlUtils.ToYamlString;

/**
 * Generates a JSON schema for CopyCat's YAML config file(s).
 *
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
final class GenerateYamlConfigSchemaTest {

   private static final class JsonWriter {

      static String toJson(final Object value) {
         final var sb = new StringBuilder(8 * 1024);
         writeValue(sb, value, 0);
         sb.append(System.lineSeparator());
         return sb.toString();
      }

      private static void writeArray(final StringBuilder sb, final List<Object> list, final int level) {
         sb.append('[');
         if (list.isEmpty()) {
            sb.append(']');
            return;
         }
         sb.append(System.lineSeparator());

         for (int i = 0; i < list.size(); i++) {
            writeIndent(sb, level + 1);
            writeValue(sb, list.get(i), level + 1);
            if (i + 1 < list.size()) {
               sb.append(',');
            }
            sb.append(System.lineSeparator());
         }

         writeIndent(sb, level);
         sb.append(']');
      }

      private static void writeIndent(final StringBuilder sb, final int level) {
         sb.append("  ".repeat(level));
      }

      private static void writeObject(final StringBuilder sb, final Map<String, Object> map, final int level) {
         sb.append('{');
         if (map.isEmpty()) {
            sb.append('}');
            return;
         }
         sb.append(System.lineSeparator());

         int i = 0;
         for (final var entry : map.entrySet()) {
            writeIndent(sb, level + 1);
            writeString(sb, entry.getKey());
            sb.append(": ");
            writeValue(sb, entry.getValue(), level + 1);
            if (++i < map.size()) {
               sb.append(',');
            }
            sb.append(System.lineSeparator());
         }

         writeIndent(sb, level);
         sb.append('}');
      }

      private static void writeString(final StringBuilder sb, final String str) {
         sb.append('"');
         for (int i = 0; i < str.length(); i++) {
            final char ch = str.charAt(i);
            switch (ch) {
               case '"' -> sb.append("\\\"");
               case '\\' -> sb.append("\\\\");
               case '\b' -> sb.append("\\b");
               case '\f' -> sb.append("\\f");
               case '\n' -> sb.append("\\n");
               case '\r' -> sb.append("\\r");
               case '\t' -> sb.append("\\t");
               default -> {
                  if (ch < 0x20) {
                     sb.append(String.format("\\u%04x", (int) ch));
                  } else {
                     sb.append(ch);
                  }
               }
            }
         }
         sb.append('"');
      }

      @SuppressWarnings("unchecked")
      private static void writeValue(final StringBuilder sb, final @Nullable Object value, final int level) {
         if (value == null) {
            sb.append("null");
            return;
         }
         if (value instanceof final String str) {
            writeString(sb, str);
            return;
         }
         if (value instanceof final Boolean b) {
            sb.append(b ? "true" : "false");
            return;
         }
         if (value instanceof final Number n) {
            sb.append(n);
            return;
         }
         if (value instanceof final Map<?, ?> map) {
            writeObject(sb, (Map<String, Object>) map, level);
            return;
         }
         if (value instanceof final List<?> list) {
            writeArray(sb, (List<Object>) list, level);
            return;
         }
         writeString(sb, value.toString());
      }

      private JsonWriter() {
      }
   }

   @SuppressWarnings("null")
   private static final Comparator<String> TASK_KEY_ORDER = Comparator.comparingInt((final String key) -> switch (key) {
      case "source" -> 0;
      case "target" -> 1;
      case "filters" -> 2;
      case "filter" -> 3;
      case "exclude" -> 4;
      default -> 10;
   }).thenComparing(Comparator.naturalOrder());

   private static Map<String, Object> buildRootConfigSchema(final String defaultsTaskRef, final String taskRequiredRef) {
      final var props = new LinkedHashMap<String, Object>();
      props.put("defaults", Map.of("$ref", defaultsTaskRef));
      props.put("sync", Map.of( //
         "type", "array", //
         "items", Map.of("$ref", taskRequiredRef) //
      ));

      final var schema = new LinkedHashMap<String, Object>();
      schema.put("type", "object");
      schema.put("properties", props);
      schema.put("additionalProperties", false);
      return schema;
   }

   private static Map<String, Object> buildTaskSchema(final Class<?> configClass) {
      final var props = new TreeMap<>(TASK_KEY_ORDER);

      for (final Field field : configClass.getFields()) {
         if (Modifier.isStatic(field.getModifiers())) {
            continue;
         }

         final var yamlAnno = field.getAnnotation(ToYamlString.class);
         if (yamlAnno != null && yamlAnno.ignore()) {
            continue;
         }

         final String rawName = yamlAnno != null && !yamlAnno.name().isEmpty() ? yamlAnno.name() : field.getName();
         final String yamlKey = YamlUtils.camelCaseToHyphen(rawName).toString();

         props.putIfAbsent(yamlKey, schemaForField(field, yamlKey));
      }

      // aliases and deprecated legacy keys that are accepted while parsing
      props.putIfAbsent("filter", Map.of( //
         "type", "array", //
         "items", Map.of("type", "string"), //
         "description", "Alias for 'filters'." //
      ));

      final var schema = new LinkedHashMap<String, Object>();
      schema.put("type", "object");
      schema.put("properties", new LinkedHashMap<>(props));
      schema.put("additionalProperties", false);
      return schema;
   }

   private static Map<String, Object> requiredTaskRef(final String taskRef) {
      return Map.of( //
         "allOf", List.of( //
            Map.of("$ref", taskRef), //
            Map.of("required", List.of("source", "target")) //
         ) //
      );
   }

   private static Map<String, Object> schemaForField(final Field field, final String yamlKey) {
      final Class<?> fieldType = field.getType();

      if ("stall-timeout".equals(yamlKey) || "stallTimeout".equals(field.getName()))
         return Map.of( //
            "anyOf", List.of( //
               Map.of( //
                  "type", "integer", //
                  "minimum", 0, //
                  "description", "Minutes (0 disables stall detection)." //
               ), //
               Map.of( //
                  "type", "string", //
                  "description", "Duration (e.g. ISO-8601) or relative expression." //
               ) //
            ) //
         );

      if (fieldType == Boolean.class || fieldType == boolean.class)
         return Map.of("type", "boolean");
      if (fieldType == Integer.class || fieldType == int.class) {
         if ("max-depth".equals(yamlKey))
            return Map.of( //
               "type", "integer", //
               "minimum", 0 //
            );
         if ("threads".equals(yamlKey))
            return Map.of( //
               "type", "integer", //
               "minimum", 1 //
            );
         return Map.of("type", "integer");
      }

      if (List.class.isAssignableFrom(fieldType))
         return Map.of( //
            "type", "array", //
            "items", Map.of("type", "string") //
         );

      if (fieldType == Path.class)
         return Map.of("type", "string");
      if (fieldType == FileTime.class)
         return Map.of( //
            "type", "string", //
            "description", "Date/time expression (see --since/--until)." //
         );

      return Map.of("type", "string");
   }

   @Test
   void generateYamlConfigSchema() throws IOException {
      final var defs = new LinkedHashMap<String, Object>();

      defs.put("WatchTask", buildTaskSchema(WatchCommandConfig.class));
      defs.put("SyncTask", buildTaskSchema(SyncCommandConfig.class));

      defs.put("WatchTaskRequired", requiredTaskRef("#/$defs/WatchTask"));
      defs.put("SyncTaskRequired", requiredTaskRef("#/$defs/SyncTask"));

      defs.put("WatchConfig", buildRootConfigSchema("#/$defs/WatchTask", "#/$defs/WatchTaskRequired"));
      defs.put("SyncConfig", buildRootConfigSchema("#/$defs/SyncTask", "#/$defs/SyncTaskRequired"));

      final var rootSchema = new LinkedHashMap<String, Object>();
      rootSchema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
      rootSchema.put("title", "copycat config.yaml");
      rootSchema.put("$defs", defs);
      rootSchema.put("anyOf", List.of( //
         Map.of("$ref", "#/$defs/SyncConfig"), //
         Map.of("$ref", "#/$defs/WatchConfig") //
      ));

      final var json = JsonWriter.toJson(rootSchema);
      final var outFile = Path.of("target/config.schema.json");
      Files.createDirectories(outFile.getParent());
      Files.writeString(outFile, json, StandardCharsets.UTF_8);
   }
}
