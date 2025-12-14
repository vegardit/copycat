/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

import org.eclipse.jdt.annotation.Nullable;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class FileAttrs {

   /**
    * Represents the type of a file system entries
    */
   public enum Type {
      BROKEN_SYMLINK,
      DIRECTORY,
      DIRECTORY_SYMLINK,
      FILE,
      FILE_SYMLINK,
      OTHER,
      OTHER_SYMLINK
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

   private final BasicFileAttributes attrs;
   private final Type type;

   private FileAttrs(final Path path, final BasicFileAttributes attrs) {
      this.attrs = attrs;
      if (attrs.isSymbolicLink()) {
         Type resolvedType;
         try {
            // Determine the target kind by following the link once.
            // Only a definite missing-target error marks the link as BROKEN_SYMLINK.
            // Other IO/security failures are treated conservatively to avoid accidental exclusion.
            final var targetAttrs = Files.readAttributes(path, BasicFileAttributes.class);
            if (targetAttrs.isDirectory()) {
               resolvedType = Type.DIRECTORY_SYMLINK;
            } else if (targetAttrs.isRegularFile()) {
               resolvedType = Type.FILE_SYMLINK;
            } else {
               resolvedType = Type.OTHER_SYMLINK;
            }
         } catch (final NoSuchFileException | FileNotFoundException ex) {
            resolvedType = Type.BROKEN_SYMLINK;
         } catch (final SecurityException | IOException ex) {
            resolvedType = Type.FILE_SYMLINK;
         }
         type = resolvedType;
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

   public boolean isBrokenSymlink() {
      return type == Type.BROKEN_SYMLINK;
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

   public boolean isFileSymlink() {
      return type == Type.FILE_SYMLINK;
   }

   public boolean isOther() {
      return type == Type.OTHER;
   }

   public boolean isOtherSymlink() {
      return type == Type.OTHER_SYMLINK;
   }

   public boolean isSymlink() {
      return type == Type.BROKEN_SYMLINK || type == Type.DIRECTORY_SYMLINK || type == Type.FILE_SYMLINK || type == Type.OTHER_SYMLINK;
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
