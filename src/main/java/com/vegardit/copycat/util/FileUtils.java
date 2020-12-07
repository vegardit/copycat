/*
 * Copyright 2020 by Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributes;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public abstract class FileUtils extends net.sf.jstuff.core.io.FileUtils {

   private static final LinkOption[] NOFOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};

   public static boolean hasDosSystemAttribute(final Path path) throws IOException {
      if (supportsDosAttributes(path)) {
         try {
            return Files.readAttributes(path, DosFileAttributes.class, NOFOLLOW_LINKS).isSystem();
         } catch (final UnsupportedOperationException e) {
            // ignore
         }
      }
      return false;
   }

   public static boolean isWritable(final Path path) {
      // Files.isWritable(targetRoot) seems to always return false SMB network shares
      return path.toFile().canWrite();
   }

   @SuppressWarnings("resource")
   public static boolean supportsDosAttributes(final Path path) {
      return path.getFileSystem().supportedFileAttributeViews().contains("dos");
   }

   @SuppressWarnings("resource")
   public static boolean supportsPosixAttributes(final Path path) {
      return path.getRoot().getFileSystem().supportedFileAttributeViews().contains("posix");
   }
}
