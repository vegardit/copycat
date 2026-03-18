/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import static net.sf.jstuff.core.validation.NullAnalysisHelper.lateNonNull;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.sf.jstuff.core.SystemUtils;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
@NonNullByDefault({})
class FileUtilsTest {

   private static class DelegatingFileChannel extends FileChannel {
      protected final FileChannel delegate;

      private DelegatingFileChannel(final FileChannel delegate) {
         this.delegate = delegate;
      }

      @Override
      public void force(final boolean metaData) throws IOException {
         delegate.force(metaData);
      }

      @Override
      public FileLock lock(final long position, final long size, final boolean shared) throws IOException {
         return delegate.lock(position, size, shared);
      }

      @Override
      public MappedByteBuffer map(final FileChannel.MapMode mode, final long position, final long size) throws IOException {
         return delegate.map(mode, position, size);
      }

      @Override
      public long position() throws IOException {
         return delegate.position();
      }

      @Override
      public FileChannel position(final long newPosition) throws IOException {
         delegate.position(newPosition);
         return this;
      }

      @Override
      public int read(final ByteBuffer dst) throws IOException {
         return delegate.read(dst);
      }

      @Override
      public long read(final ByteBuffer[] dsts, final int offset, final int length) throws IOException {
         return delegate.read(dsts, offset, length);
      }

      @Override
      public int read(final ByteBuffer dst, final long position) throws IOException {
         return delegate.read(dst, position);
      }

      @Override
      public long size() throws IOException {
         return delegate.size();
      }

      @Override
      public long transferFrom(final java.nio.channels.ReadableByteChannel src, final long position, final long count) throws IOException {
         return delegate.transferFrom(src, position, count);
      }

      @Override
      public long transferTo(final long position, final long count, final WritableByteChannel target) throws IOException {
         return delegate.transferTo(position, count, target);
      }

      @Override
      public @Nullable FileLock tryLock(final long position, final long size, final boolean shared) throws IOException {
         return delegate.tryLock(position, size, shared);
      }

      @Override
      public FileChannel truncate(final long size) throws IOException {
         delegate.truncate(size);
         return this;
      }

      @Override
      public int write(final ByteBuffer src) throws IOException {
         return delegate.write(src);
      }

      @Override
      public long write(final ByteBuffer[] srcs, final int offset, final int length) throws IOException {
         return delegate.write(srcs, offset, length);
      }

      @Override
      public int write(final ByteBuffer src, final long position) throws IOException {
         return delegate.write(src, position);
      }

      @Override
      protected void implCloseChannel() throws IOException {
         delegate.close();
      }
   }

   private static final class FailingTransferFileChannel extends DelegatingFileChannel {
      private final long failAfterBytes;

      private FailingTransferFileChannel(final FileChannel delegate, final long failAfterBytes) {
         super(delegate);
         this.failAfterBytes = failAfterBytes;
      }

      @Override
      public long transferTo(final long position, final long count, final WritableByteChannel target) throws IOException {
         if (position >= failAfterBytes)
            throw new IOException("Simulated read failure");

         final long maxCount = Math.min(count, failAfterBytes - position);
         final long copied = super.transferTo(position, maxCount, target);
         if (position + copied >= failAfterBytes)
            throw new IOException("Simulated read failure");
         return copied;
      }
   }

   private static final class FailingTransferFileSystem extends FileSystem {
      private final FileSystem delegate;
      private final FailingTransferFileSystemProvider provider;

      private FailingTransferFileSystem(final Path delegatePath, final long failAfterBytes) {
         delegate = delegatePath.getFileSystem();
         provider = new FailingTransferFileSystemProvider(this, failAfterBytes);
      }

      @Override
      public FileSystemProvider provider() {
         return provider;
      }

      @Override
      public void close() throws IOException {
         // no-op
      }

      @Override
      public boolean isOpen() {
         return true;
      }

      @Override
      public boolean isReadOnly() {
         return delegate.isReadOnly();
      }

      @Override
      public String getSeparator() {
         return delegate.getSeparator();
      }

      @Override
      public Iterable<Path> getRootDirectories() {
         return delegate.getRootDirectories();
      }

      @Override
      public Iterable<FileStore> getFileStores() {
         return delegate.getFileStores();
      }

      @Override
      public Set<String> supportedFileAttributeViews() {
         return delegate.supportedFileAttributeViews();
      }

      @Override
      public Path getPath(final String first, final String... more) {
         return delegate.getPath(first, more);
      }

      @Override
      public PathMatcher getPathMatcher(final String syntaxAndPattern) {
         return delegate.getPathMatcher(syntaxAndPattern);
      }

      @Override
      public UserPrincipalLookupService getUserPrincipalLookupService() {
         return delegate.getUserPrincipalLookupService();
      }

      @Override
      public WatchService newWatchService() throws IOException {
         return delegate.newWatchService();
      }
   }

   private static final class FailingTransferFileSystemProvider extends FileSystemProvider {
      private final FailingTransferFileSystem fileSystem;
      private final long failAfterBytes;

      private FailingTransferFileSystemProvider(final FailingTransferFileSystem fileSystem, final long failAfterBytes) {
         this.fileSystem = fileSystem;
         this.failAfterBytes = failAfterBytes;
      }

      @Override
      public String getScheme() {
         return "copycat-failing-transfer";
      }

      @Override
      public FileSystem newFileSystem(final URI uri, final Map<String, ?> env) {
         throw new UnsupportedOperationException();
      }

      @Override
      public FileSystem getFileSystem(final URI uri) {
         return fileSystem;
      }

      @Override
      public Path getPath(final URI uri) {
         throw new UnsupportedOperationException();
      }

      @Override
      public java.io.InputStream newInputStream(final Path path, final OpenOption... options) throws IOException {
         return unwrap(path).getFileSystem().provider().newInputStream(unwrap(path), options);
      }

      @Override
      public java.io.OutputStream newOutputStream(final Path path, final OpenOption... options) throws IOException {
         return unwrap(path).getFileSystem().provider().newOutputStream(unwrap(path), options);
      }

      @Override
      public SeekableByteChannel newByteChannel(final Path path, final Set<? extends OpenOption> options, final FileAttribute<?>... attrs)
            throws IOException {
         return unwrap(path).getFileSystem().provider().newByteChannel(unwrap(path), options, attrs);
      }

      @Override
      public DirectoryStream<Path> newDirectoryStream(final Path dir, final DirectoryStream.Filter<? super Path> filter)
            throws IOException {
         return unwrap(dir).getFileSystem().provider().newDirectoryStream(unwrap(dir), filter);
      }

      @Override
      public void createDirectory(final Path dir, final FileAttribute<?>... attrs) throws IOException {
         unwrap(dir).getFileSystem().provider().createDirectory(unwrap(dir), attrs);
      }

      @Override
      public void delete(final Path path) throws IOException {
         unwrap(path).getFileSystem().provider().delete(unwrap(path));
      }

      @Override
      public void copy(final Path source, final Path target, final CopyOption... options) throws IOException {
         unwrap(source).getFileSystem().provider().copy(unwrap(source), unwrap(target), options);
      }

      @Override
      public void move(final Path source, final Path target, final CopyOption... options) throws IOException {
         unwrap(source).getFileSystem().provider().move(unwrap(source), unwrap(target), options);
      }

      @Override
      public boolean isSameFile(final Path path, final Path path2) throws IOException {
         return unwrap(path).getFileSystem().provider().isSameFile(unwrap(path), unwrap(path2));
      }

      @Override
      public boolean isHidden(final Path path) throws IOException {
         return unwrap(path).getFileSystem().provider().isHidden(unwrap(path));
      }

      @Override
      public FileStore getFileStore(final Path path) throws IOException {
         return unwrap(path).getFileSystem().provider().getFileStore(unwrap(path));
      }

      @Override
      public void checkAccess(final Path path, final AccessMode... modes) throws IOException {
         unwrap(path).getFileSystem().provider().checkAccess(unwrap(path), modes);
      }

      @Override
      public <V extends FileAttributeView> @Nullable V getFileAttributeView(final Path path, final Class<V> type,
            final LinkOption... options) {
         return unwrap(path).getFileSystem().provider().getFileAttributeView(unwrap(path), type, options);
      }

      @Override
      public <A extends BasicFileAttributes> A readAttributes(final Path path, final Class<A> type, final LinkOption... options)
            throws IOException {
         return unwrap(path).getFileSystem().provider().readAttributes(unwrap(path), type, options);
      }

      @Override
      public Map<String, Object> readAttributes(final Path path, final String attributes, final LinkOption... options) throws IOException {
         return unwrap(path).getFileSystem().provider().readAttributes(unwrap(path), attributes, options);
      }

      @Override
      public void setAttribute(final Path path, final String attribute, final Object value, final LinkOption... options)
            throws IOException {
         unwrap(path).getFileSystem().provider().setAttribute(unwrap(path), attribute, value, options);
      }

      @Override
      public FileChannel newFileChannel(final Path path, final Set<? extends OpenOption> options, final FileAttribute<?>... attrs)
            throws IOException {
         final FileChannel delegate = unwrap(path).getFileSystem().provider().newFileChannel(unwrap(path), options, attrs);
         return new FailingTransferFileChannel(delegate, failAfterBytes);
      }

      @Override
      public AsynchronousFileChannel newAsynchronousFileChannel(final Path path, final Set<? extends OpenOption> options,
            final ExecutorService executor, final FileAttribute<?>... attrs) throws IOException {
         return unwrap(path).getFileSystem().provider().newAsynchronousFileChannel(unwrap(path), options, executor, attrs);
      }

      private static Path unwrap(final Path path) {
         if (path instanceof final FailingTransferPath failingPath)
            return failingPath.delegate;
         return path;
      }
   }

   private static final class FailingTransferPath implements Path {
      private final Path delegate;
      private final FailingTransferFileSystem fileSystem;

      private FailingTransferPath(final Path delegate, final long failAfterBytes) {
         this.delegate = delegate;
         fileSystem = new FailingTransferFileSystem(delegate, failAfterBytes);
      }

      @Override
      public FileSystem getFileSystem() {
         return fileSystem;
      }

      @Override
      public boolean isAbsolute() {
         return delegate.isAbsolute();
      }

      @Override
      public @Nullable Path getRoot() {
         return delegate.getRoot();
      }

      @Override
      public @Nullable Path getFileName() {
         return delegate.getFileName();
      }

      @Override
      public @Nullable Path getParent() {
         return delegate.getParent();
      }

      @Override
      public int getNameCount() {
         return delegate.getNameCount();
      }

      @Override
      public Path getName(final int index) {
         return delegate.getName(index);
      }

      @Override
      public Path subpath(final int beginIndex, final int endIndex) {
         return delegate.subpath(beginIndex, endIndex);
      }

      @Override
      public boolean startsWith(final Path other) {
         return delegate.startsWith(other);
      }

      @Override
      public boolean startsWith(final String other) {
         return delegate.startsWith(other);
      }

      @Override
      public boolean endsWith(final Path other) {
         return delegate.endsWith(other);
      }

      @Override
      public boolean endsWith(final String other) {
         return delegate.endsWith(other);
      }

      @Override
      public Path normalize() {
         return delegate.normalize();
      }

      @Override
      public Path resolve(final Path other) {
         return delegate.resolve(other);
      }

      @Override
      public Path resolve(final String other) {
         return delegate.resolve(other);
      }

      @Override
      public Path resolveSibling(final Path other) {
         return delegate.resolveSibling(other);
      }

      @Override
      public Path resolveSibling(final String other) {
         return delegate.resolveSibling(other);
      }

      @Override
      public Path relativize(final Path other) {
         return delegate.relativize(other);
      }

      @Override
      public URI toUri() {
         return delegate.toUri();
      }

      @Override
      public Path toAbsolutePath() {
         return delegate.toAbsolutePath();
      }

      @Override
      public Path toRealPath(final LinkOption... options) throws IOException {
         return delegate.toRealPath(options);
      }

      @Override
      public java.io.File toFile() {
         return delegate.toFile();
      }

      @Override
      public WatchKey register(final WatchService watcher, final WatchEvent.Kind<?>[] events, final WatchEvent.Modifier... modifiers)
            throws IOException {
         return delegate.register(watcher, events, modifiers);
      }

      @Override
      public WatchKey register(final WatchService watcher, final WatchEvent.Kind<?>... events) throws IOException {
         return delegate.register(watcher, events);
      }

      @Override
      public Iterator<Path> iterator() {
         return delegate.iterator();
      }

      @Override
      public int compareTo(final Path other) {
         return delegate.compareTo(other);
      }

      @Override
      public boolean equals(final @Nullable Object obj) {
         return obj instanceof final FailingTransferPath other && delegate.equals(other.delegate);
      }

      @Override
      public int hashCode() {
         return delegate.hashCode();
      }

      @Override
      public String toString() {
         return delegate.toString();
      }
   }

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
   void copyFileKeepsExistingTargetWhenCopyFailsMidTransfer() throws IOException {
      final byte[] sourceBytes = "0123456789abcdef".repeat(1024).getBytes(StandardCharsets.UTF_8);
      final var source = Files.write(tempDir.resolve("src-large.txt"), sourceBytes);
      final BasicFileAttributes sourceAttrs = Files.readAttributes(source, BasicFileAttributes.class, FileUtils.NOFOLLOW_LINKS);
      final var target = Files.writeString(tempDir.resolve("dst-existing.txt"), "stable-old-content", StandardCharsets.UTF_8);
      final var failingSource = new FailingTransferPath(source, 4096);

      assertThatThrownBy(() -> FileUtils.copyFile(failingSource, sourceAttrs, target, false, (a, b) -> { /* nothing to do */}))
         .isInstanceOf(IOException.class).hasMessageContaining("Simulated read failure");

      assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("stable-old-content");
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
