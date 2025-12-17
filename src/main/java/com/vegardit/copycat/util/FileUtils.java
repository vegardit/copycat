/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.UserDefinedFileAttributeView;

import org.apache.commons.lang3.CharUtils;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.sun.nio.file.ExtendedOpenOption;

import net.sf.jstuff.core.Strings;
import net.sf.jstuff.core.SystemUtils;
import net.sf.jstuff.core.functional.BiLongConsumer;
import net.sf.jstuff.core.io.MoreFiles;
import net.sf.jstuff.core.logging.Logger;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class FileUtils {
   private static final Logger LOG = Logger.create();

   private static final @NonNull CopyOption[] COPY_WITH_ATTRS_OPTIONS = {StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS};
   static final @NonNull LinkOption[] NOFOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};

   private static final OpenOption[] FILE_READ_OPTIONS_STRICT = SystemUtils.IS_OS_WINDOWS //
         ? new OpenOption[] {ExtendedOpenOption.NOSHARE_WRITE, StandardOpenOption.READ}
         : new OpenOption[] {StandardOpenOption.READ};

   private static final OpenOption[] FILE_READ_OPTIONS_SHARED = new OpenOption[] {StandardOpenOption.READ};

   private static final OpenOption[] FILE_WRITE_OPTIONS = SystemUtils.IS_OS_WINDOWS //
         ? new OpenOption[] { //
            ExtendedOpenOption.NOSHARE_READ, ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE, //
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE}
         : new OpenOption[] { //
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};

   @SuppressWarnings("resource")
   public static void copyAttributes(final Path source, final BasicFileAttributes sourceAttrs, final Path target, final boolean copyACL,
         final boolean bestEffortMetadata) throws IOException {
      final var sourceFS = source.getFileSystem();
      final var targetFS = target.getFileSystem();

      final var targetFSP = targetFS.provider();
      final var targetBasicAttrs = targetFSP.getFileAttributeView(target, BasicFileAttributeView.class, NOFOLLOW_LINKS);

      final var sourceSupportedAttrs = sourceFS.supportedFileAttributeViews();
      final var targetSupportedAttrs = targetFS.supportedFileAttributeViews();

      @Nullable
      DosFileAttributes sourceDosAttrs = null;
      if (SystemUtils.IS_OS_WINDOWS && sourceAttrs instanceof final DosFileAttributes dosAttrs) {
         sourceDosAttrs = dosAttrs;
      }

      final var targetDosAttrs = SystemUtils.IS_OS_WINDOWS //
            ? targetFSP.getFileAttributeView(target, DosFileAttributeView.class, NOFOLLOW_LINKS)
            : null;
      final var targetPosixAttrs = targetFSP.getFileAttributeView(target, PosixFileAttributeView.class, NOFOLLOW_LINKS);

      if (sourceDosAttrs != null && targetDosAttrs != null) {
         try {
            targetDosAttrs.setArchive(sourceDosAttrs.isArchive());
            targetDosAttrs.setHidden(sourceDosAttrs.isHidden());
            targetDosAttrs.setReadOnly(sourceDosAttrs.isReadOnly());
            targetDosAttrs.setSystem(sourceDosAttrs.isSystem());
         } catch (final IOException ex) {
            if (!bestEffortMetadata)
               throw ex;
            LOG.debug("Failed to copy DOS attributes for [%s] (best-effort)...", target);
            LOG.debug(ex);
         }
      }

      if (copyACL && SystemUtils.IS_OS_WINDOWS && sourceSupportedAttrs.contains("acl") && targetSupportedAttrs.contains("acl")) {
         final var sourceFSP = sourceFS.provider();
         final var sourceAclAttrs = sourceFSP.getFileAttributeView(source, AclFileAttributeView.class, NOFOLLOW_LINKS);
         final var targetAclAttrs = targetFSP.getFileAttributeView(target, AclFileAttributeView.class, NOFOLLOW_LINKS);
         if (sourceAclAttrs != null && targetAclAttrs != null) {
            try {
               targetAclAttrs.setAcl(sourceAclAttrs.getAcl());
               if (SystemUtils.isRunningAsAdmin()) {
                  targetAclAttrs.setOwner(sourceAclAttrs.getOwner());
               }
            } catch (final IOException ex) {
               if (!bestEffortMetadata)
                  throw ex;
               LOG.debug("Failed to copy ACL attributes for [%s] (best-effort)...", target);
               LOG.debug(ex);
            }
         }
      }

      boolean ownerCopied = false;

      @Nullable
      PosixFileAttributes sourcePosixAttrs = null;
      if (sourceAttrs instanceof final PosixFileAttributes posixAttrs) {
         sourcePosixAttrs = posixAttrs;
      } else if (copyACL && targetPosixAttrs != null && sourceSupportedAttrs.contains("posix")) {
         try {
            sourcePosixAttrs = Files.readAttributes(source, PosixFileAttributes.class, NOFOLLOW_LINKS);
         } catch (final UnsupportedOperationException ex) {
            // ignore
         }
      }

      if (copyACL && sourcePosixAttrs != null && targetPosixAttrs != null) {
         try {
            targetPosixAttrs.setOwner(sourcePosixAttrs.owner());
            targetPosixAttrs.setGroup(sourcePosixAttrs.group());
            targetPosixAttrs.setPermissions(sourcePosixAttrs.permissions());
            ownerCopied = true;
         } catch (final IOException ex) {
            if (!bestEffortMetadata)
               throw ex;
            LOG.debug("Failed to copy POSIX attributes for [%s] (best-effort)...", target);
            LOG.debug(ex);
         }
      }

      if (copyACL && !ownerCopied && !SystemUtils.IS_OS_WINDOWS //
            && sourceSupportedAttrs.contains("owner") && targetSupportedAttrs.contains("owner")) {
         try {
            Files.setOwner(target, Files.getOwner(source, NOFOLLOW_LINKS));
         } catch (final IOException ex) {
            if (!bestEffortMetadata)
               throw ex;
            LOG.debug("Failed to copy owner for [%s] (best-effort)...", target);
            LOG.debug(ex);
         }
      }

      try {
         copyUserAttrs(source, target);
      } catch (final IOException | RuntimeException ex) {
         if (!bestEffortMetadata)
            throw ex;
         LOG.debug("Failed to copy user-defined attributes for [%s] (best-effort)...", target);
         LOG.debug(ex);
      }

      if (targetBasicAttrs != null) {
         try {
            copyTimeAttrs(sourceAttrs, targetBasicAttrs);
         } catch (final IOException ex) {
            if (!bestEffortMetadata)
               throw ex;
            LOG.debug("Failed to copy time attributes for [%s] (best-effort)...", target);
            LOG.debug(ex);
         }
      } else if (targetPosixAttrs != null) {
         try {
            copyTimeAttrs(sourceAttrs, targetPosixAttrs);
         } catch (final IOException ex) {
            if (!bestEffortMetadata)
               throw ex;
            LOG.debug("Failed to copy time attributes for [%s] (best-effort)...", target);
            LOG.debug(ex);
         }
      } else if (targetDosAttrs != null) {
         try {
            copyTimeAttrs(sourceAttrs, targetDosAttrs);
         } catch (final IOException ex) {
            if (!bestEffortMetadata)
               throw ex;
            LOG.debug("Failed to copy time attributes for [%s] (best-effort)...", target);
            LOG.debug(ex);
         }
      }
   }

   public static void copyDirShallow(final Path source, final Path target, final boolean copyACL) throws IOException {
      final var sourceAttrs = MoreFiles.readAttributes(source);
      copyDirShallow(source, sourceAttrs, target, copyACL);
   }

   public static void copyDirShallow(final Path source, final BasicFileAttributes sourceAttrs, final Path target, final boolean copyACL)
         throws IOException {
      if (copyACL) {
         try {
            Files.copy(source, target, NOFOLLOW_LINKS);
         } catch (final FileAlreadyExistsException ex) {
            if (!Files.isDirectory(target, NOFOLLOW_LINKS))
               throw ex;
         }
         copyAttributes(source, sourceAttrs, target, true, false);
      } else {
         try {
            Files.copy(source, target, COPY_WITH_ATTRS_OPTIONS);
         } catch (final FileAlreadyExistsException ex) {
            if (!Files.isDirectory(target, NOFOLLOW_LINKS))
               throw ex;
            copyAttributes(source, sourceAttrs, target, false, false);
         }
      }
   }

   public static void copyFile(final Path source, final BasicFileAttributes sourceAttrs, final Path target, final boolean copyACL,
         final BiLongConsumer onBytesWritten) throws IOException {
      copyFile(source, sourceAttrs, target, copyACL, false, onBytesWritten);
   }

   public static void copyFile(final Path source, final BasicFileAttributes sourceAttrs, final Path target, final boolean copyACL,
         final boolean allowReadingOpenFiles, final BiLongConsumer onBytesWritten) throws IOException {

      final OpenOption[] sourceReadOptions = allowReadingOpenFiles && SystemUtils.IS_OS_WINDOWS //
            ? FILE_READ_OPTIONS_SHARED
            : FILE_READ_OPTIONS_STRICT;

      copyFileContent(source, target, sourceReadOptions, onBytesWritten);
      copyAttributes(source, sourceAttrs, target, copyACL, allowReadingOpenFiles);
   }

   private static void copyFileContent(final Path source, final Path target, final OpenOption[] sourceReadOptions,
         final BiLongConsumer onBytesWritten) throws IOException {
      try (var sourceCh = FileChannel.open(source, sourceReadOptions);
           var targetCh = FileChannel.open(target, FILE_WRITE_OPTIONS)) {
         MoreFiles.copyContent(sourceCh, targetCh, onBytesWritten);
      }
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
         if (sourceUserAttrs == null)
            return;

         final var entries = sourceUserAttrs.list();
         if (!entries.isEmpty()) {
            final var targetFSP = targetFS.provider();
            final var targetUserAttrs = targetFSP.getFileAttributeView(target, UserDefinedFileAttributeView.class, NOFOLLOW_LINKS);
            if (targetUserAttrs == null)
               return;
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

   public static Path toAbsolute(Path path) {
      path = path.toAbsolutePath().normalize();

      if (SystemUtils.IS_OS_WINDOWS) {
         // ensure drive letter is uppercase
         final var pathStr = path.toString();
         if (!CharUtils.isAsciiAlphaUpper(pathStr.charAt(0)))
            return Path.of(Strings.capitalize(pathStr));
      }
      return path;
   }

   private FileUtils() {
   }
}
