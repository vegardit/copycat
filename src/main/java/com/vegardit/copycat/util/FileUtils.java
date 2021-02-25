/*
 * Copyright 2020-2021 by Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.UserDefinedFileAttributeView;

import com.sun.nio.file.ExtendedOpenOption;

import net.sf.jstuff.core.SystemUtils;
import net.sf.jstuff.core.functional.LongBiConsumer;
import net.sf.jstuff.core.io.MoreFiles;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public abstract class FileUtils {

   private static final LinkOption[] NOFOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};
   private static final OpenOption[] FILE_READ_OPTIONS = { //
      ExtendedOpenOption.NOSHARE_WRITE, //
      StandardOpenOption.READ //
   };
   private static final OpenOption[] FILE_WRITE_OPTIONS = { //
      ExtendedOpenOption.NOSHARE_READ, //
      ExtendedOpenOption.NOSHARE_WRITE, //
      ExtendedOpenOption.NOSHARE_DELETE, //
      StandardOpenOption.CREATE, //
      StandardOpenOption.TRUNCATE_EXISTING, //
      StandardOpenOption.WRITE //
   };

   @SuppressWarnings("resource")
   public static void copyFile(final Path source, final BasicFileAttributes sourceAttrs, final Path target, final boolean copyACL,
      final LongBiConsumer onBytesWritten) throws IOException {

      try (var sourceCh = FileChannel.open(source, FILE_READ_OPTIONS);
           var targetCh = FileChannel.open(target, FILE_WRITE_OPTIONS)) {
         MoreFiles.copyContent(sourceCh, targetCh, onBytesWritten);
      }

      final var sourceFS = source.getFileSystem();
      final var targetFS = target.getFileSystem();

      final var targetFSP = targetFS.provider();

      final var sourceSupportedAttrs = sourceFS.supportedFileAttributeViews();
      final var targetSupportedAttrs = targetFS.supportedFileAttributeViews();

      if (sourceAttrs instanceof DosFileAttributes) {
         final var sourceDosAttrs = (DosFileAttributes) sourceAttrs;
         final var targetDosAttrs = targetFSP.getFileAttributeView(target, DosFileAttributeView.class, NOFOLLOW_LINKS);
         targetDosAttrs.setArchive(sourceDosAttrs.isArchive());
         targetDosAttrs.setHidden(sourceDosAttrs.isHidden());
         targetDosAttrs.setReadOnly(sourceDosAttrs.isReadOnly());
         targetDosAttrs.setSystem(sourceDosAttrs.isSystem());
         if (copyACL && sourceSupportedAttrs.contains("acl") && targetSupportedAttrs.contains("acl")) {
            final var sourceFSP = sourceFS.provider();
            final var sourceAclAttrs = sourceFSP.getFileAttributeView(source, AclFileAttributeView.class, NOFOLLOW_LINKS);
            final var targetAclAttrs = targetFSP.getFileAttributeView(target, AclFileAttributeView.class, NOFOLLOW_LINKS);
            targetAclAttrs.setAcl(sourceAclAttrs.getAcl());
            if (SystemUtils.isRunningAsAdmin()) {
               targetAclAttrs.setOwner(sourceAclAttrs.getOwner());
            }
         }
         copyUserAttrs(source, target);
         copyTimeAttrs(sourceDosAttrs, targetDosAttrs);
         return;
      }

      if (sourceAttrs instanceof PosixFileAttributes) {
         final var sourcePosixAttrs = (PosixFileAttributes) sourceAttrs;
         final var targetPosixAttrs = targetFSP.getFileAttributeView(target, PosixFileAttributeView.class, NOFOLLOW_LINKS);
         if (copyACL) {
            targetPosixAttrs.setOwner(sourcePosixAttrs.owner());
            targetPosixAttrs.setGroup(sourcePosixAttrs.group());
            targetPosixAttrs.setPermissions(sourcePosixAttrs.permissions());
         }
         copyUserAttrs(source, target);
         copyTimeAttrs(sourcePosixAttrs, targetPosixAttrs);
         return;
      }

      if (copyACL && sourceSupportedAttrs.contains("owner") && targetSupportedAttrs.contains("owner")) {
         Files.setOwner(target, Files.getOwner(source, NOFOLLOW_LINKS));
      }
      copyUserAttrs(source, target);
      copyTimeAttrs( //
         sourceAttrs, //
         targetFSP.getFileAttributeView(target, BasicFileAttributeView.class, NOFOLLOW_LINKS) //
      );
   }

   private static void copyTimeAttrs(final BasicFileAttributes sourceAttrs, final BasicFileAttributeView targetAttrs) throws IOException {
      targetAttrs.setTimes(sourceAttrs.lastModifiedTime(), sourceAttrs.lastAccessTime(), sourceAttrs.creationTime());
   }

   @SuppressWarnings("resource")
   private static void copyUserAttrs(final Path source, final Path target) throws IOException {
      final var sourceFS = source.getFileSystem();
      final var targetFS = target.getFileSystem();

      final var sourceSupportedAttrs = sourceFS.supportedFileAttributeViews();
      final var targetSupportedAttrs = targetFS.supportedFileAttributeViews();
      if (sourceSupportedAttrs.contains("user") && targetSupportedAttrs.contains("user")) {
         final var sourceFSP = sourceFS.provider();

         final var sourceUserAttrs = sourceFSP.getFileAttributeView(source, UserDefinedFileAttributeView.class, NOFOLLOW_LINKS);
         final var entries = sourceUserAttrs.list();
         if (!entries.isEmpty()) {
            final var targetFSP = targetFS.provider();
            final var targetUserAttrs = targetFSP.getFileAttributeView(target, UserDefinedFileAttributeView.class, NOFOLLOW_LINKS);
            ByteBuffer buf = null;

            for (final var entry : entries) {
               final var entrySize = sourceUserAttrs.size(entry);
               if (buf == null || entrySize > buf.capacity()) {
                  buf = ByteBuffer.allocate(entrySize);
               } else {
                  buf.clear();
               }
               sourceUserAttrs.read(entry, buf);
               buf.flip();
               targetUserAttrs.write(entry, buf);
            }
         }
      }
   }

   public static boolean isDosSystemFile(final Path path) throws IOException {
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
      return path.getFileSystem().supportedFileAttributeViews().contains("posix");
   }
}
