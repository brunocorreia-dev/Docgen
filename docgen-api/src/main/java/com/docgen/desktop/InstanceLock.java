package com.docgen.desktop;

import com.docgen.api.PrivateFiles;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

/**
 * One DocGen per user: an OS file lock in the private data directory. The
 * operating system releases it when the process ends for any reason, so a
 * crash never leaves a stale lock behind.
 */
final class InstanceLock implements AutoCloseable {
    static final String FILE = "instance.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** The lock, or empty when another DocGen of this user holds it. */
    static Optional<InstanceLock> tryAcquire(Path dataDirectory) throws IOException {
        Path file = dataDirectory.resolve(FILE);
        if (Files.isSymbolicLink(file)) {
            throw new IOException("The lock file is a symbolic link");
        }
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS) && PrivateFiles.posix()) {
            try {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (FileAlreadyExistsException raced) {
                // Another start created it first; tryLock below decides which one runs.
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
            return Optional.of(new InstanceLock(channel, lock));
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
        } catch (IOException releasedWithTheChannel) {
            // Closing the channel below releases the lock as well.
        }
        try {
            channel.close();
        } catch (IOException releasedWithTheProcess) {
            // The OS drops the lock when the process ends anyway.
        }
    }
}
