/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class MapUtils {

   public static <T> Boolean getBoolean(final Map<T, ?> map, final T key, final boolean remove) {
      final var value = remove ? map.remove(key) : map.get(key);
      if (value == null)
         return null; // CHECKSTYLE:IGNORE .*
      if (value instanceof Boolean)
         return (Boolean) value;
      return Boolean.parseBoolean(value.toString());
   }

   public static <T> Integer getInteger(final Map<T, ?> map, final T key, final boolean remove) {
      final var value = remove ? map.remove(key) : map.get(key);
      if (value == null)
         return null;
      if (value instanceof Number)
         return ((Number) value).intValue();
      try {
         return Integer.parseInt(value.toString());
      } catch (final NumberFormatException ex) {
         throw new IllegalArgumentException("Cannot parse attribute [" + key + "] with value [" + value + "] as integer. " + ex
            .getMessage(), ex);
      }
   }

   public static <T> Path getPath(final Map<T, ?> map, final T key, final boolean remove) {
      final var value = remove ? map.remove(key) : map.get(key);
      if (value == null)
         return null;
      try {
         return Path.of(value.toString());
      } catch (final InvalidPathException ex) {
         throw new IllegalArgumentException("Cannot parse attribute [" + key + "] with value [" + value + "] as path. " + ex.getMessage(),
            ex);
      }
   }

   @SuppressWarnings("unchecked")
   public static <T> List<String> getStringList(final Map<T, ?> map, final T key, final boolean remove) {
      final var value = remove ? map.remove(key) : map.get(key);
      if (value == null)
         return null; // CHECKSTYLE:IGNORE .*
      if (value instanceof List) {
         ((List<Object>) value).replaceAll(Object::toString);
         return (List<String>) value;
      }
      throw new IllegalArgumentException("Cannot parse attribute [" + key + "] with value [" + value + "] as a list.");
   }

   private MapUtils() {
   }
}
