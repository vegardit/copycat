/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.lateNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.sf.jstuff.core.SystemUtils;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
class FileUtilsTest {

   private static byte[] readUserAttr(final UserDefinedFileAttributeView view, final String name) throws IOException {
      final var size = view.size(name);
      final var buf = ByteBuffer.allocate(size);
      view.read(name, buf);
      buf.flip();

      final var bytes = new byte[buf.remaining()];
      buf.get(bytes);
      return bytes;
   }

   @TempDir
   Path tempDir = lateNonNull();

   @Test
   void copyDirShallowCreatesDirectoryOnly() throws IOException {
      final var sourceDir = Files.createDirectory(tempDir.resolve("srcDir"));
      Files.writeString(sourceDir.resolve("nested.txt"), "data");

      final var targetDir = tempDir.resolve("dstDir");
      FileUtils.copyDirShallow(sourceDir, targetDir, false);

      assertThat(targetDir).isDirectory();
      assertThat(targetDir.resolve("nested.txt")).doesNotExist();
   }

   @Test
   void copyDirShallowDoesNotFailIfDirectoryExists() throws IOException {
      final var sourceDir = Files.createDirectory(tempDir.resolve("srcDir"));
      Files.writeString(sourceDir.resolve("nested.txt"), "data");
      final BasicFileAttributes sourceAttrs = Files.readAttributes(sourceDir, BasicFileAttributes.class, FileUtils.NOFOLLOW_LINKS);

      final var targetDir = Files.createDirectory(tempDir.resolve("dstDir"));
      FileUtils.copyDirShallow(sourceDir, sourceAttrs, targetDir, false);

      assertThat(targetDir).isDirectory();
      assertThat(targetDir.resolve("nested.txt")).doesNotExist();
   }

   @Test
   void copyFileCopiesContent() throws IOException {
      final var source = Files.writeString(tempDir.resolve("src.txt"), "hello");
      final BasicFileAttributes sourceAttrs = Files.readAttributes(source, BasicFileAttributes.class, FileUtils.NOFOLLOW_LINKS);

      final var target = tempDir.resolve("dst.txt");
      FileUtils.copyFile(source, sourceAttrs, target, false, (a, b) -> { /* nothing to do */});

      assertThat(target).exists();
      assertThat(Files.readString(target)).isEqualTo("hello");
   }

   @Test
   void copyFileCopiesUserDefinedAttributes() throws IOException {
      final var source = Files.writeString(tempDir.resolve("src-xattr.txt"), "hello");
      final var sourceView = Files.getFileAttributeView(source, UserDefinedFileAttributeView.class, FileUtils.NOFOLLOW_LINKS);
      assumeTrue(sourceView != null);
      assert sourceView != null;

      final var attrsSmall = "copycat.test.small";
      final var attrsMedium = "copycat.test.medium";
      final var attrsSmall2 = "copycat.test.small2";
      final var bytesSmall = "small".getBytes();
      final var bytesMedium = "x".repeat(256).getBytes();
      final var bytesSmall2 = "small2".getBytes();

      try { // CHECKSTYLE:IGNORE .*
         sourceView.write(attrsSmall, ByteBuffer.wrap(bytesSmall));
         sourceView.write(attrsMedium, ByteBuffer.wrap(bytesMedium));
         sourceView.write(attrsSmall2, ByteBuffer.wrap(bytesSmall2));
      } catch (final UnsupportedOperationException ex) {
         assumeTrue(false, "User-defined file attributes not supported: " + ex);
      } catch (final IOException ex) {
         if (isXattrNoSpace(ex)) {
            assumeTrue(false, "Not enough xattr space on filesystem: " + ex);
         }
         throw ex;
      }

      final List<String> entries;
      try { // CHECKSTYLE:IGNORE .*
         entries = sourceView.list();
      } catch (final UnsupportedOperationException ex) {
         assumeTrue(false, "User-defined file attributes not supported: " + ex);
         return;
      }
      assumeTrue(entries.contains(attrsSmall) && entries.contains(attrsMedium) && entries.contains(attrsSmall2));

      final BasicFileAttributes sourceAttrs = Files.readAttributes(source, BasicFileAttributes.class, FileUtils.NOFOLLOW_LINKS);
      final var target = tempDir.resolve("dst-xattr.txt");
      FileUtils.copyFile(source, sourceAttrs, target, false, (a, b) -> { /* nothing to do */});

      final var targetView = Files.getFileAttributeView(target, UserDefinedFileAttributeView.class, FileUtils.NOFOLLOW_LINKS);
      assumeTrue(targetView != null);
      assert targetView != null;

      assertThat(readUserAttr(targetView, attrsSmall)).isEqualTo(bytesSmall);
      assertThat(readUserAttr(targetView, attrsMedium)).isEqualTo(bytesMedium);
      assertThat(readUserAttr(targetView, attrsSmall2)).isEqualTo(bytesSmall2);
   }

   private static boolean isXattrNoSpace(final IOException ex) {
      if (ex instanceof final FileSystemException fse) {
         final var reason = fse.getReason();
         if (reason != null && reason.contains("No space left on device"))
            return true;
      }
      final var msg = ex.getMessage();
      return msg != null && msg.contains("No space left on device");
   }

   @Test
   void isDosSystemFileDefaultsToFalse() throws IOException {
      final var file = Files.createTempFile(tempDir, "copycat", ".txt");
      assertThat(FileUtils.isDosSystemFile(file)).isFalse();
   }

   @Test
   void isWritableReturnsTrueForTempDir() {
      assertThat(FileUtils.isWritable(tempDir)).isTrue();
   }

   @Test
   void supportsPlatformNativeFileAttributes() {
      @SuppressWarnings("resource")
      final var supported = tempDir.getFileSystem().supportedFileAttributeViews();
      assertThat(FileUtils.supportsDosAttributes(tempDir)).isEqualTo(supported.contains("dos"));
      assertThat(FileUtils.supportsPosixAttributes(tempDir)).isEqualTo(supported.contains("posix"));
   }

   @Test
   void copyAttributesCopiesPosixPermissionsEvenIfSourceAttrsAreDos() throws IOException {
      assumeFalse(SystemUtils.IS_OS_WINDOWS);
      assumeTrue(FileUtils.supportsPosixAttributes(tempDir));

      final var source = Files.writeString(tempDir.resolve("src-perm.txt"), "hello");
      final var target = Files.writeString(tempDir.resolve("dst-perm.txt"), "world");

      final Set<PosixFilePermission> sourcePerms;
      try { // CHECKSTYLE:IGNORE .*
         Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rw-------"));
         Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));
         sourcePerms = Files.getPosixFilePermissions(source);
      } catch (final UnsupportedOperationException ex) {
         assumeTrue(false, "POSIX file permissions not supported: " + ex);
         return;
      }

      final var basic = Files.readAttributes(source, BasicFileAttributes.class, FileUtils.NOFOLLOW_LINKS);
      FileUtils.copyAttributes(source, new FakeDosFileAttributes(basic), target, true, false);

      assertThat(Files.getPosixFilePermissions(target)).isEqualTo(sourcePerms);
   }

   @Test
   void toAbsoluteReturnsNormalizedAbsolutePath() {
      final var input = Path.of("..").resolve("copycat").resolve("..").resolve("copycat");
      assertThat(FileUtils.toAbsolute(input)).isEqualTo(input.toAbsolutePath().normalize());
   }

   @Test
   void toAbsoluteUppercasesDriveLetterOnWindows() {
      assumeTrue(SystemUtils.IS_OS_WINDOWS);

      final var input = Path.of("c:\\temp\\..\\copycat");
      final var abs = FileUtils.toAbsolute(input);

      assertThat(abs.toString()).startsWith("C:\\");
      assertThat(abs).isEqualTo(Path.of("C:\\copycat"));
   }

   private static final class FakeDosFileAttributes implements java.nio.file.attribute.DosFileAttributes {
      private final BasicFileAttributes delegate;

      private FakeDosFileAttributes(final BasicFileAttributes delegate) {
         this.delegate = delegate;
      }

      @Override
      public java.nio.file.attribute.FileTime lastModifiedTime() {
         return delegate.lastModifiedTime();
      }

      @Override
      public java.nio.file.attribute.FileTime lastAccessTime() {
         return delegate.lastAccessTime();
      }

      @Override
      public java.nio.file.attribute.FileTime creationTime() {
         return delegate.creationTime();
      }

      @Override
      public boolean isRegularFile() {
         return delegate.isRegularFile();
      }

      @Override
      public boolean isDirectory() {
         return delegate.isDirectory();
      }

      @Override
      public boolean isSymbolicLink() {
         return delegate.isSymbolicLink();
      }

      @Override
      public boolean isOther() {
         return delegate.isOther();
      }

      @Override
      public long size() {
         return delegate.size();
      }

      @Override
      public @Nullable Object fileKey() {
         return delegate.fileKey();
      }

      @Override
      public boolean isReadOnly() {
         return false;
      }

      @Override
      public boolean isHidden() {
         return false;
      }

      @Override
      public boolean isArchive() {
         return false;
      }

      @Override
      public boolean isSystem() {
         return false;
      }
   }
}
