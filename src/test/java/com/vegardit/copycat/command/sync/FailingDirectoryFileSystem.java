/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.command.sync;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
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
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Delegates to a real filesystem while failing iteration of one directory.
 * This drives the JDK walker's actual postVisitDirectory error callback without permission-dependent fixtures.
 *
 * @author OpenAI Codex
 */
// Delegating overrides retain the JDK's unspecified nullness, matching the existing filesystem fixture in FileUtilsTest.
@NonNullByDefault({})
final class FailingDirectoryFileSystem extends FileSystem {
   private final FileSystem delegate;
   private final Path failingDirectory;
   private final IOException failure;
   private final int entriesBeforeFailure;
   private final FileSystemProvider provider = new Provider();
   private int failureCount;

   FailingDirectoryFileSystem(final Path failingDirectory, final IOException failure, final int entriesBeforeFailure) {
      delegate = failingDirectory.getFileSystem();
      this.failingDirectory = failingDirectory;
      this.failure = failure;
      this.entriesBeforeFailure = entriesBeforeFailure;
   }

   @NonNull
   Path wrap(final Path path) {
      return new WrappedPath(unwrap(path), this);
   }

   private @Nullable Path wrapNullable(final @Nullable Path path) {
      return path == null ? null : wrap(path);
   }

   private static Path unwrap(final Path path) {
      return path instanceof final WrappedPath wrapped ? wrapped.delegate : path;
   }

   int failureCount() {
      return failureCount;
   }

   @Override
   public FileSystemProvider provider() {
      return provider;
   }

   @Override
   public void close() {
      // The test owns only the wrapper; closing it must not close the process-wide default filesystem.
   }

   @Override
   public boolean isOpen() {
      return delegate.isOpen();
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
      throw new UnsupportedOperationException();
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
      return wrap(delegate.getPath(first, more));
   }

   @Override
   public PathMatcher getPathMatcher(final String syntaxAndPattern) {
      final var matcher = delegate.getPathMatcher(syntaxAndPattern);
      return path -> matcher.matches(unwrap(path));
   }

   @Override
   public UserPrincipalLookupService getUserPrincipalLookupService() {
      return delegate.getUserPrincipalLookupService();
   }

   @Override
   public WatchService newWatchService() throws IOException {
      return delegate.newWatchService();
   }

   /**
    * Preserves the wrapper across path operations so a walk cannot silently escape the failing provider.
    */
   private record WrappedPath(Path delegate, FailingDirectoryFileSystem fileSystem) implements Path {
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
         return fileSystem.wrapNullable(delegate.getRoot());
      }

      @Override
      public @Nullable Path getFileName() {
         return fileSystem.wrapNullable(delegate.getFileName());
      }

      @Override
      public @Nullable Path getParent() {
         return fileSystem.wrapNullable(delegate.getParent());
      }

      @Override
      public int getNameCount() {
         return delegate.getNameCount();
      }

      @Override
      public Path getName(final int index) {
         return fileSystem.wrap(delegate.getName(index));
      }

      @Override
      public Path subpath(final int beginIndex, final int endIndex) {
         return fileSystem.wrap(delegate.subpath(beginIndex, endIndex));
      }

      @Override
      public boolean startsWith(final Path other) {
         return delegate.startsWith(unwrap(other));
      }

      @Override
      public boolean endsWith(final Path other) {
         return delegate.endsWith(unwrap(other));
      }

      @Override
      public Path normalize() {
         return fileSystem.wrap(delegate.normalize());
      }

      @Override
      public Path resolve(final Path other) {
         return fileSystem.wrap(delegate.resolve(unwrap(other)));
      }

      @Override
      public Path relativize(final Path other) {
         return fileSystem.wrap(delegate.relativize(unwrap(other)));
      }

      @Override
      public URI toUri() {
         return delegate.toUri();
      }

      @Override
      public Path toAbsolutePath() {
         return fileSystem.wrap(delegate.toAbsolutePath());
      }

      @Override
      public Path toRealPath(final LinkOption... options) throws IOException {
         return fileSystem.wrap(delegate.toRealPath(options));
      }

      @Override
      public File toFile() {
         return delegate.toFile();
      }

      @Override
      public WatchKey register(final WatchService watcher, final WatchEvent.Kind<?>[] events, final WatchEvent.Modifier... modifiers)
            throws IOException {
         return delegate.register(watcher, events, modifiers);
      }

      @Override
      public int compareTo(final Path other) {
         return delegate.compareTo(unwrap(other));
      }

      @Override
      public Iterator<Path> iterator() {
         final var iterator = delegate.iterator();
         return new Iterator<>() {
            @Override
            public boolean hasNext() {
               return iterator.hasNext();
            }

            @Override
            public Path next() {
               return fileSystem.wrap(iterator.next());
            }
         };
      }

      @Override
      public String toString() {
         return delegate.toString();
      }
   }

   /**
    * Injects DirectoryIteratorException after opening the stream, preserving normal attribute and deletion operations.
    */
   private final class Provider extends FileSystemProvider {
      @Override
      public String getScheme() {
         return "copycat-failing-directory";
      }

      @Override
      public FileSystem newFileSystem(final URI uri, final Map<String, ?> env) {
         throw new UnsupportedOperationException();
      }

      @Override
      public FileSystem getFileSystem(final URI uri) {
         return FailingDirectoryFileSystem.this;
      }

      @Override
      public Path getPath(final URI uri) {
         throw new UnsupportedOperationException();
      }

      @Override
      public SeekableByteChannel newByteChannel(final Path path, final Set<? extends OpenOption> options, final FileAttribute<?>... attrs)
            throws IOException {
         return Files.newByteChannel(unwrap(path), options, attrs);
      }

      @Override
      @SuppressWarnings("resource") // Ownership of the underlying stream transfers to the returned wrapper's close method.
      public DirectoryStream<Path> newDirectoryStream(final Path dir, final DirectoryStream.Filter<? super Path> filter)
            throws IOException {
         final var stream = Files.newDirectoryStream(unwrap(dir), child -> filter.accept(wrap(child)));
         return new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
               final var iterator = stream.iterator();
               return new Iterator<>() {
                  private int delivered;

                  @Override
                  public boolean hasNext() {
                     // Failing here, rather than when opening the stream, exercises postVisitDirectory with a non-null exception.
                     if (delivered >= entriesBeforeFailure && unwrap(dir).equals(failingDirectory)) {
                        failureCount++;
                        throw new DirectoryIteratorException(failure);
                     }
                     return iterator.hasNext();
                  }

                  @Override
                  public Path next() {
                     final var child = iterator.next();
                     delivered++;
                     return wrap(child);
                  }
               };
            }

            @Override
            public void close() throws IOException {
               stream.close();
            }
         };
      }

      @Override
      public void createDirectory(final Path dir, final FileAttribute<?>... attrs) throws IOException {
         Files.createDirectory(unwrap(dir), attrs);
      }

      @Override
      public void delete(final Path path) throws IOException {
         Files.delete(unwrap(path));
      }

      @Override
      public void copy(final Path source, final Path target, final CopyOption... options) throws IOException {
         Files.copy(unwrap(source), unwrap(target), options);
      }

      @Override
      public void move(final Path source, final Path target, final CopyOption... options) throws IOException {
         Files.move(unwrap(source), unwrap(target), options);
      }

      @Override
      public boolean isSameFile(final Path path, final Path other) throws IOException {
         return Files.isSameFile(unwrap(path), unwrap(other));
      }

      @Override
      public boolean isHidden(final Path path) throws IOException {
         return Files.isHidden(unwrap(path));
      }

      @Override
      public FileStore getFileStore(final Path path) throws IOException {
         return Files.getFileStore(unwrap(path));
      }

      @Override
      public void checkAccess(final Path path, final AccessMode... modes) throws IOException {
         delegate.provider().checkAccess(unwrap(path), modes);
      }

      @Override
      public <V extends FileAttributeView> @Nullable V getFileAttributeView(final Path path, final Class<V> type,
            final LinkOption... options) {
         return Files.getFileAttributeView(unwrap(path), type, options);
      }

      @Override
      public <A extends BasicFileAttributes> A readAttributes(final Path path, final Class<A> type, final LinkOption... options)
            throws IOException {
         return Files.readAttributes(unwrap(path), type, options);
      }

      @Override
      public Map<String, Object> readAttributes(final Path path, final String attributes, final LinkOption... options) throws IOException {
         return Files.readAttributes(unwrap(path), attributes, options);
      }

      @Override
      public void setAttribute(final Path path, final String attribute, final Object value, final LinkOption... options)
            throws IOException {
         Files.setAttribute(unwrap(path), attribute, value, options);
      }
   }
}
