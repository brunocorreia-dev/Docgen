package com.docgen.launcher;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

/**
 * One launcher per user: an OS file lock in the private state directory. The
 * operating system releases it when the launcher exits for any reason, so a
 * crash never leaves a stale lock behind. A second launcher is told that
 * DocGen is already open; it never touches the first one or its backend.
 */
final class SingleInstanceLock implements AutoCloseable {
    static final String FILE = "launcher.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private SingleInstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Returns the lock, or empty when another launcher of this user holds it. */
    static Optional<SingleInstanceLock> acquire(Path stateDirectory) throws IOException {
        SecureDirectories.createPrivate(stateDirectory);
        Path file = stateDirectory.resolve(FILE);
        if (Files.isSymbolicLink(file)) {
            throw new IOException("The lock file is a symbolic link: " + file);
        }
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS) && SecureDirectories.posix()) {
            try {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                // Another launcher created it first; locking below decides who runs.
            }
        }
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new SingleInstanceLock(channel, lock));
        } catch (OverlappingFileLockException heldByThisProcess) {
            channel.close();
            return Optional.empty();
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // Closing the channel below releases it as well.
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // The OS drops the lock when the process ends anyway.
        }
    }
}
