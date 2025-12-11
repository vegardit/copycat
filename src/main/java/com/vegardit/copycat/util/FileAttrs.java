/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

import org.eclipse.jdt.annotation.Nullable;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class FileAttrs {

   public enum Type {
      DIRECTORY,
      DIRECTORY_SYMLINK,
      FILE,
      FILE_SYMLINK,
      OTHER
   }

   public static @Nullable FileAttrs find(final Path path) throws IOException {
      try {
         final var sourceAttrs = Files.readAttributes(path, BasicFileAttributes.class, FileUtils.NOFOLLOW_LINKS);
         return new FileAttrs(path, sourceAttrs);
      } catch (final FileNotFoundException ex) {
         return null;
      }
   }

   public static FileAttrs get(final Path path) throws IOException {
      final var sourceAttrs = Files.readAttributes(path, BasicFileAttributes.class, FileUtils.NOFOLLOW_LINKS);
      return new FileAttrs(path, sourceAttrs);
   }

   public static FileAttrs get(final Path path, final BasicFileAttributes attrs) {
      return new FileAttrs(path, attrs);
   }

   private final Type type;
   private final BasicFileAttributes attrs;

   private FileAttrs(final Path path, final BasicFileAttributes attrs) {
      this.attrs = attrs;
      if (attrs.isSymbolicLink()) {
         type = Files.isRegularFile(path) ? Type.FILE_SYMLINK : Type.DIRECTORY_SYMLINK;
      } else if (attrs.isDirectory()) {
         type = Type.DIRECTORY;
      } else if (attrs.isRegularFile()) {
         type = Type.FILE;
      } else {
         type = Type.OTHER;
      }
   }

   public FileTime creationTime() {
      return attrs.creationTime();
   }

   public boolean isDir() {
      return type == Type.DIRECTORY;
   }

   public boolean isDirSymlink() {
      return type == Type.DIRECTORY_SYMLINK;
   }

   public boolean isFile() {
      return type == Type.FILE;
   }

   public boolean isFileOrFileSymlink() {
      return type == Type.FILE || type == Type.FILE_SYMLINK;
   }

   public boolean isFileOrSymlink() {
      return type == Type.FILE || isSymlink();
   }

   public boolean isFileSymlink() {
      return type == Type.FILE_SYMLINK;
   }

   public boolean isOther() {
      return type == Type.OTHER;
   }

   public boolean isSymlink() {
      return type == Type.FILE_SYMLINK || type == Type.DIRECTORY_SYMLINK;
   }

   public FileTime lastModifiedTime() {
      return attrs.lastModifiedTime();
   }

   public BasicFileAttributes nioFileAttributes() {
      return attrs;
   }

   public long size() {
      return attrs.size();
   }

   public Type type() {
      return type;
   }
}
