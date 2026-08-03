package com.docgen;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Writes a pair of files through a directory descriptor. All names used after
 * opening the output directory are single-component, handle-relative paths.
 */
final class SecurePairWriter {
    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");
    private static final Observer NOOP_OBSERVER = (event, target) -> { };
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int STAGING_NONCE_BYTES = 32;

    private SecurePairWriter() {
    }

    static void preflight(
            Path outputDirectory,
            Path firstName,
            Path secondName,
            boolean force,
            Kind kind
    ) throws IOException {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        TargetState first = new TargetState(new Spec(firstName, ""));
        TargetState second = new TargetState(new Spec(secondName, ""));
        SecureOutputDirectory directory = null;
        Throwable operationFailure = null;
        try {
            directory = SecureOutputDirectory.openExisting(outputDirectory);
            inspectAndValidate(directory, first, force, kind);
            inspectAndValidate(directory, second, force, kind);
            directory.requireStillNamed();
        } catch (IOException | RuntimeException failure) {
            operationFailure = failure;
            throw failure;
        } finally {
            if (directory != null) {
                try {
                    directory.close();
                } catch (IOException closeFailure) {
                    IOException sanitized = new IOException(kind.closeFailureMessage);
                    if (operationFailure != null) {
                        operationFailure.addSuppressed(sanitized);
                    } else {
                        throw sanitized;
                    }
                }
            }
        }
    }

    static void write(
            Path outputDirectory,
            Spec first,
            Spec second,
            boolean force,
            Kind kind,
            Observer observer
    ) throws IOException {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        Objects.requireNonNull(kind, "kind");
        Observer effectiveObserver = observer == null ? NOOP_OBSERVER : observer;

        SecureOutputDirectory directory = null;
        Throwable operationFailure = null;
        boolean committed = false;
        TargetState firstTarget = new TargetState(first);
        TargetState secondTarget = new TargetState(second);
        try {
            directory = SecureOutputDirectory.openExisting(outputDirectory);
            effectiveObserver.on(Event.DIRECTORY_OPENED, directory.absolutePath);
            directory.requireStillNamed();

            inspectAndValidate(directory, firstTarget, force, kind);
            inspectAndValidate(directory, secondTarget, force, kind);

            firstTarget.staged = stage(
                    directory, firstTarget.spec.content(), firstTarget.spec.name(), kind, effectiveObserver);
            secondTarget.staged = stage(
                    directory, secondTarget.spec.content(), secondTarget.spec.name(), kind, effectiveObserver);
            directory.requireStillNamed();

            quarantineOriginal(directory, firstTarget, effectiveObserver);
            quarantineOriginal(directory, secondTarget, effectiveObserver);
            directory.forceDirectory();

            publish(directory, firstTarget, effectiveObserver);
            publish(directory, secondTarget, effectiveObserver);
            directory.forceDirectory();
            directory.requireStillNamed();
            committed = true;

            IOException cleanupFailure = cleanupAfterCommit(directory, firstTarget, secondTarget, kind);
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        } catch (IOException | RuntimeException failure) {
            operationFailure = failure;
            if (directory != null && !committed) {
                rollback(directory, secondTarget, failure, kind);
                rollback(directory, firstTarget, failure, kind);
                cleanupAfterFailure(directory, firstTarget.staged, failure, kind);
                cleanupAfterFailure(directory, secondTarget.staged, failure, kind);
            }
            throw failure;
        } finally {
            if (directory != null) {
                try {
                    directory.close();
                } catch (IOException closeFailure) {
                    IOException sanitized = new IOException(kind.closeFailureMessage);
                    if (operationFailure != null) {
                        operationFailure.addSuppressed(sanitized);
                    } else {
                        throw sanitized;
                    }
                }
            }
        }
    }

    private static void inspectAndValidate(
            SecureOutputDirectory directory,
            TargetState target,
            boolean force,
            Kind kind
    ) throws IOException {
        BasicFileAttributes attributes = directory.attributesIfPresent(target.spec.name());
        if (attributes == null) {
            return;
        }
        target.existed = true;
        if (!force) {
            throw new FileAlreadyExistsException(
                    target.spec.name().toString(), null, kind.overwriteHint);
        }
        if (!attributes.isRegularFile()) {
            throw new IOException(kind.nonRegularPrefix + target.spec.name());
        }
        target.initialFileKey = attributes.fileKey();
        if (target.initialFileKey == null) {
            throw new IOException(kind.unverifiableTargetMessage);
        }
        directory.requireIdentity(target.spec.name(), target.initialFileKey, kind.changedTargetMessage);
    }

    private static OwnedFile stage(
            SecureOutputDirectory directory,
            String content,
            Path targetName,
            Kind kind,
            Observer observer
    )
            throws IOException {
        for (int attempt = 0; attempt < 16; attempt++) {
            Path name = randomName(targetName, ".tmp");
            SeekableByteChannel channel;
            try {
                channel = directory.createNew(name);
            } catch (FileAlreadyExistsException collision) {
                continue;
            }

            OwnedFile staged = null;
            byte[] payload = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            byte[] stagedBytes = new byte[STAGING_NONCE_BYTES + payload.length];
            RANDOM.nextBytes(stagedBytes);
            System.arraycopy(payload, 0, stagedBytes, STAGING_NONCE_BYTES, payload.length);
            byte[] handshake = new byte[STAGING_NONCE_BYTES];
            System.arraycopy(stagedBytes, 0, handshake, 0, STAGING_NONCE_BYTES);
            try (channel) {
                observer.on(Event.AFTER_STAGE_CREATE, name);
                // The random nonce binds the channel just created to the name
                // inspected through the directory handle before ownership is
                // accepted. Sensitive payload bytes are written only after it.
                writeFully(channel, handshake);
                force(channel);
                staged = directory.ownedFile(name, "Staging-file identity is unavailable")
                        .withContent(digest(handshake), digest(handshake), 0);
                directory.requireOwnedContent(
                        staged, "Staging file changed while it was being written");
                channel.position(0);
                writeFully(channel, stagedBytes);
                channel.truncate(channel.position());
                force(channel);
                staged = staged.withContent(
                        digest(stagedBytes), digest(payload), STAGING_NONCE_BYTES);
            } catch (IOException | RuntimeException failure) {
                if (staged == null) {
                    // No content was written without an identity, and the
                    // unverifiable empty reservation is intentionally retained.
                    failure.addSuppressed(new IOException(kind.sanitizedCleanupDetail));
                } else {
                    cleanupAfterFailure(directory, staged, failure, kind);
                }
                throw failure;
            }
            directory.requireOwnedContent(staged, "Staging file changed while it was being written");
            return staged;
        }
        throw new IOException("Could not reserve a unique staging file");
    }

    private static void quarantineOriginal(
            SecureOutputDirectory directory,
            TargetState target,
            Observer observer
    ) throws IOException {
        if (!target.existed) {
            return;
        }
        directory.requireIdentity(
                target.spec.name(), target.initialFileKey, "Output target changed during the write transaction");
        byte[] originalDigest = directory.digestEntry(target.spec.name());
        directory.requireIdentity(
                target.spec.name(), target.initialFileKey, "Output target changed during the write transaction");
        Path quarantineName = directory.uniqueVacantName(target.spec.name(), ".bak");
        target.originalQuarantine = new OwnedFile(
                quarantineName, target.initialFileKey, originalDigest, originalDigest, 0);
        observer.on(Event.BEFORE_QUARANTINE_MOVE, target.spec.name());
        directory.move(target.spec.name(), quarantineName);
        BasicFileAttributes movedAttributes = directory.attributesIfPresent(quarantineName);
        if (movedAttributes == null || !movedAttributes.isRegularFile()
                || movedAttributes.fileKey() == null
                || !target.initialFileKey.equals(movedAttributes.fileKey())) {
            target.originalQuarantine = null;
            target.originalQuarantined = false;
            IOException changed = new IOException(
                    "Output target changed while it was being quarantined");
            boolean restored = directory.restoreUnexpectedMovedEntry(
                    quarantineName, target.spec.name());
            if (!restored) {
                changed.addSuppressed(new IOException(
                        "Rollback was incomplete; a displaced entry remains in recovery quarantine"));
            } else {
                changed.addSuppressed(new IOException(
                        "A displaced entry was restored; its recovery quarantine was retained"));
            }
            throw changed;
        }
        target.originalQuarantined = true;
        directory.requireOwnedContent(
                target.originalQuarantine, "Original output changed while it was quarantined");
        directory.requireAbsent(target.spec.name(), "Output target reappeared during the write transaction");
        observer.on(Event.ORIGINAL_QUARANTINED, target.spec.name());
    }

    private static void publish(
            SecureOutputDirectory directory,
            TargetState target,
            Observer observer
    ) throws IOException {
        directory.requireOwnedContent(target.staged, "Staging file changed before publication");
        directory.requireAbsent(target.spec.name(), "Output target appeared during the write transaction");
        observer.on(Event.BEFORE_PUBLISH, target.spec.name());

        SeekableByteChannel output;
        try {
            output = directory.createNew(target.spec.name());
        } catch (FileAlreadyExistsException concurrentTarget) {
            throw new FileAlreadyExistsException(
                    target.spec.name().toString(), null, "Output target appeared during the write transaction");
        }

        target.publicationEntryCreated = true;
        try (output) {
            observer.on(Event.AFTER_PUBLICATION_CREATE, target.spec.name());
            byte[] handshake = new byte[STAGING_NONCE_BYTES];
            RANDOM.nextBytes(handshake);
            writeFully(output, handshake);
            force(output);
            target.published = directory.ownedFile(
                            target.spec.name(), "Published-output identity is unavailable")
                    .withContent(digest(handshake), digest(handshake), 0);
            directory.requireOwnedContent(
                    target.published, "Published output channel could not be bound safely");
            output.position(0);
            copy(directory, target.staged, output);
            output.truncate(output.position());
            force(output);
            target.published = target.published.withContent(
                    target.staged.payloadDigest, target.staged.payloadDigest, 0);
        }
        directory.requireOwnedContent(
                target.published, "Published output changed while it was being written");
        observer.on(Event.AFTER_PUBLISH, target.spec.name());
    }

    private static void copy(
            SecureOutputDirectory directory,
            OwnedFile source,
            SeekableByteChannel output
    ) throws IOException {
        directory.requireOwnedContent(source, "Transaction source changed before it could be read");
        try (SeekableByteChannel input = directory.openForRead(source.name)) {
            long remainingSkip = source.payloadOffset;
            ByteBuffer skipped = ByteBuffer.allocate(8192);
            while (remainingSkip > 0) {
                skipped.clear();
                skipped.limit((int) Math.min(skipped.capacity(), remainingSkip));
                int read = input.read(skipped);
                if (read < 0) {
                    throw new IOException("Transaction source ended before its payload");
                }
                remainingSkip -= read;
            }
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                buffer.clear();
            }
        }
        directory.requireOwnedContent(source, "Transaction source changed while it was being read");
    }

    private static void rollback(
            SecureOutputDirectory directory,
            TargetState target,
            Throwable originalFailure,
            Kind kind
    ) {
        boolean targetVacant = removePublishedForRollback(directory, target, originalFailure, kind);
        if (!target.originalQuarantined) {
            return;
        }
        if (!targetVacant) {
            signalIncompleteRollback(originalFailure, kind);
            return;
        }

        try {
            directory.requireOwned(target.originalQuarantine, kind.recoveryUnavailableMessage);
            directory.requireAbsent(target.spec.name(), kind.restoreOccupiedMessage);
            OwnedFile restored = copyToVacantTarget(
                    directory, target.originalQuarantine, target.spec.name());
            directory.requireOwnedContent(restored, kind.restoreFailedMessage);
            directory.removeOwned(target.originalQuarantine);
            target.originalQuarantined = false;
            directory.forceDirectory();
        } catch (IOException | RuntimeException rollbackFailure) {
            // The original remains in its randomly named quarantine whenever
            // restoration cannot be proved safe.
            signalIncompleteRollback(originalFailure, kind);
        }
    }

    private static boolean removePublishedForRollback(
            SecureOutputDirectory directory,
            TargetState target,
            Throwable originalFailure,
            Kind kind
    ) {
        if (!target.publicationEntryCreated) {
            return true;
        }
        BasicFileAttributes current;
        try {
            current = directory.attributesIfPresent(target.spec.name());
        } catch (IOException | RuntimeException inspectionFailure) {
            signalIncompleteRollback(originalFailure, kind);
            return false;
        }
        if (current == null) {
            return true;
        }
        if (target.published == null
                || target.published.fileKey == null
                || !target.published.fileKey.equals(current.fileKey())) {
            // Never remove an entry whose identity is not the one published by
            // this transaction. A zero-length, identity-less entry is retained.
            signalIncompleteRollback(originalFailure, kind);
            return false;
        }

        try {
            directory.removeOwned(target.published);
            return directory.attributesIfPresent(target.spec.name()) == null;
        } catch (IOException | RuntimeException cleanupFailure) {
            signalIncompleteRollback(originalFailure, kind);
            return false;
        }
    }

    private static OwnedFile copyToVacantTarget(
            SecureOutputDirectory directory,
            OwnedFile source,
            Path targetName
    ) throws IOException {
        SeekableByteChannel output;
        Set<PosixFilePermission> sourcePermissions = directory.permissions(source.name);
        try {
            output = directory.createNew(targetName, sourcePermissions);
        } catch (FileAlreadyExistsException occupied) {
            throw new IOException("Recovery destination is occupied");
        }

        OwnedFile restored = null;
        try (output) {
            byte[] handshake = new byte[STAGING_NONCE_BYTES];
            RANDOM.nextBytes(handshake);
            writeFully(output, handshake);
            force(output);
            restored = directory.ownedFile(targetName, "Restored-output identity is unavailable")
                    .withContent(digest(handshake), digest(handshake), 0);
            directory.requireOwnedContent(
                    restored, "Restored-output channel could not be bound safely");
            output.position(0);
            copy(directory, source, output);
            output.truncate(output.position());
            force(output);
            restored = restored.withContent(source.payloadDigest, source.payloadDigest, 0);
        } catch (IOException | RuntimeException restoreFailure) {
            if (restored != null) {
                try {
                    directory.removeOwned(restored);
                } catch (IOException | RuntimeException cleanupFailure) {
                    // Preserve the known recovery source and surface only a
                    // generic status through the caller's rollback signal.
                }
            }
            throw restoreFailure;
        }
        directory.requireOwnedContent(restored, "Restored output changed after it was written");
        directory.setPermissions(restored, sourcePermissions);
        return restored;
    }

    private static IOException cleanupAfterCommit(
            SecureOutputDirectory directory,
            TargetState first,
            TargetState second,
            Kind kind
    ) {
        IOException failure = null;
        failure = cleanupCommittedFile(directory, first.staged, failure, kind);
        failure = cleanupCommittedFile(directory, second.staged, failure, kind);
        failure = cleanupCommittedFile(directory, first.originalQuarantine, failure, kind);
        failure = cleanupCommittedFile(directory, second.originalQuarantine, failure, kind);
        try {
            directory.forceDirectory();
        } catch (IOException forceFailure) {
            if (failure == null) {
                failure = new IOException(kind.cleanupFailureMessage);
            }
            failure.addSuppressed(new IOException(kind.sanitizedCleanupDetail));
        }
        return failure;
    }

    private static IOException cleanupCommittedFile(
            SecureOutputDirectory directory,
            OwnedFile file,
            IOException existingFailure,
            Kind kind
    ) {
        if (file == null) {
            return existingFailure;
        }
        try {
            directory.removeOwned(file);
        } catch (IOException | RuntimeException cleanupFailure) {
            IOException failure = existingFailure;
            if (failure == null) {
                failure = new IOException(kind.cleanupFailureMessage);
            }
            failure.addSuppressed(new IOException(kind.sanitizedCleanupDetail));
            return failure;
        }
        return existingFailure;
    }

    private static void cleanupAfterFailure(
            SecureOutputDirectory directory,
            OwnedFile file,
            Throwable originalFailure,
            Kind kind
    ) {
        if (file == null) {
            return;
        }
        try {
            directory.removeOwned(file);
        } catch (IOException | RuntimeException cleanupFailure) {
            originalFailure.addSuppressed(new IOException(kind.sanitizedCleanupDetail));
        }
    }

    private static void signalIncompleteRollback(Throwable failure, Kind kind) {
        for (Throwable suppressed : failure.getSuppressed()) {
            if (kind.rollbackIncompleteMessage.equals(suppressed.getMessage())) {
                return;
            }
        }
        failure.addSuppressed(new IOException(kind.rollbackIncompleteMessage));
    }

    private static Path randomName(Path targetName, String suffix) {
        return Path.of("." + targetName.getFileName() + "-" + UUID.randomUUID() + suffix);
    }

    private static void writeFully(SeekableByteChannel channel, byte[] content) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(content);
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }

    private static byte[] digest(byte[] content) {
        MessageDigest digest = sha256();
        return digest.digest(content);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void force(SeekableByteChannel channel) throws IOException {
        if (channel instanceof FileChannel fileChannel) {
            fileChannel.force(true);
        }
    }

    record Spec(Path name, String content) {
        Spec {
            Objects.requireNonNull(name, "name");
            if (name.isAbsolute() || name.getNameCount() != 1
                    || ".".equals(name.toString()) || "..".equals(name.toString())) {
                throw new IllegalArgumentException("Output target must be a single file name");
            }
        }
    }

    enum Kind {
        DOCUMENTATION(
                "Use --force to overwrite",
                "Refusing to overwrite non-regular output target: ",
                "Refusing to overwrite an output whose identity cannot be verified safely",
                "Output target changed during the write transaction",
                "Documentation recovery backup is unavailable",
                "Documentation recovery destination is occupied",
                "Documentation restoration could not be verified",
                "Documentation rollback was incomplete; recovery files may remain",
                "Documentation was written, but a transaction file could not be removed",
                "A documentation transaction file could not be removed safely",
                "Documentation output directory could not be closed safely"
        ),
        PROMPT_PREVIEW(
                "Use --force to overwrite prompt previews",
                "Refusing to overwrite a non-regular prompt preview: ",
                "Refusing to overwrite a prompt preview whose identity cannot be verified safely",
                "Prompt preview target changed during the write transaction",
                "Prompt preview recovery backup is unavailable",
                "Prompt preview recovery destination is occupied",
                "Prompt preview restoration could not be verified",
                "Prompt preview rollback was incomplete; recovery files may remain",
                "Prompt previews were written, but a transaction file could not be removed",
                "A prompt preview transaction file could not be removed safely",
                "Prompt preview output directory could not be closed safely"
        );

        private final String overwriteHint;
        private final String nonRegularPrefix;
        private final String unverifiableTargetMessage;
        private final String changedTargetMessage;
        private final String recoveryUnavailableMessage;
        private final String restoreOccupiedMessage;
        private final String restoreFailedMessage;
        private final String rollbackIncompleteMessage;
        private final String cleanupFailureMessage;
        private final String sanitizedCleanupDetail;
        private final String closeFailureMessage;

        Kind(
                String overwriteHint,
                String nonRegularPrefix,
                String unverifiableTargetMessage,
                String changedTargetMessage,
                String recoveryUnavailableMessage,
                String restoreOccupiedMessage,
                String restoreFailedMessage,
                String rollbackIncompleteMessage,
                String cleanupFailureMessage,
                String sanitizedCleanupDetail,
                String closeFailureMessage
        ) {
            this.overwriteHint = overwriteHint;
            this.nonRegularPrefix = nonRegularPrefix;
            this.unverifiableTargetMessage = unverifiableTargetMessage;
            this.changedTargetMessage = changedTargetMessage;
            this.recoveryUnavailableMessage = recoveryUnavailableMessage;
            this.restoreOccupiedMessage = restoreOccupiedMessage;
            this.restoreFailedMessage = restoreFailedMessage;
            this.rollbackIncompleteMessage = rollbackIncompleteMessage;
            this.cleanupFailureMessage = cleanupFailureMessage;
            this.sanitizedCleanupDetail = sanitizedCleanupDetail;
            this.closeFailureMessage = closeFailureMessage;
        }
    }

    enum Event {
        DIRECTORY_OPENED,
        AFTER_STAGE_CREATE,
        BEFORE_QUARANTINE_MOVE,
        ORIGINAL_QUARANTINED,
        BEFORE_PUBLISH,
        AFTER_PUBLICATION_CREATE,
        AFTER_PUBLISH
    }

    @FunctionalInterface
    interface Observer {
        void on(Event event, Path target) throws IOException;
    }

    private static final class TargetState {
        private final Spec spec;
        private boolean existed;
        private Object initialFileKey;
        private OwnedFile staged;
        private OwnedFile originalQuarantine;
        private boolean originalQuarantined;
        private boolean publicationEntryCreated;
        private OwnedFile published;

        private TargetState(Spec spec) {
            this.spec = spec;
        }
    }

    private record OwnedFile(
            Path name,
            Object fileKey,
            byte[] entryDigest,
            byte[] payloadDigest,
            long payloadOffset
    ) {
        private OwnedFile(Path name, Object fileKey) {
            this(name, fileKey, null, null, 0);
        }

        private OwnedFile withContent(byte[] entry, byte[] payload, long offset) {
            return new OwnedFile(
                    name,
                    fileKey,
                    entry == null ? null : entry.clone(),
                    payload == null ? null : payload.clone(),
                    offset
            );
        }
    }

    private static final class SecureOutputDirectory implements AutoCloseable {
        private final Path absolutePath;
        private final SecureDirectoryStream<Path> stream;
        private final Object directoryFileKey;
        private final boolean posix;

        private SecureOutputDirectory(
                Path absolutePath,
                SecureDirectoryStream<Path> stream,
                Object directoryFileKey
        ) {
            this.absolutePath = absolutePath;
            this.stream = stream;
            this.directoryFileKey = directoryFileKey;
            this.posix = stream.getFileAttributeView(PosixFileAttributeView.class) != null;
        }

        private static SecureOutputDirectory openExisting(Path requested) throws IOException {
            Path absolute = requested.toAbsolutePath().normalize();
            if (absolute.getRoot() == null) {
                throw new IOException("Output directory must resolve to an absolute path");
            }
            return traverse(absolute);
        }

        @SuppressWarnings("unchecked")
        private static SecureOutputDirectory traverse(Path absolute) throws IOException {
            DirectoryStream<Path> rootDirectory = Files.newDirectoryStream(absolute.getRoot());
            if (!(rootDirectory instanceof SecureDirectoryStream<?>)) {
                rootDirectory.close();
                throw new IOException("Secure directory operations are unavailable on this filesystem");
            }

            SecureDirectoryStream<Path> current = (SecureDirectoryStream<Path>) rootDirectory;
            boolean success = false;
            try {
                directoryIdentity(current);
                for (Path component : absolute) {
                    BasicFileAttributes childAttributes;
                    try {
                        childAttributes = childAttributes(current, component);
                    } catch (NoSuchFileException missing) {
                        throw new IOException(
                                "Output directory must already exist and remain unchanged");
                    }

                    if (!childAttributes.isDirectory() || childAttributes.isSymbolicLink()
                            || childAttributes.fileKey() == null) {
                        throw new IOException("Output path contains a symbolic link or unverifiable directory");
                    }

                    SecureDirectoryStream<Path> child;
                    try {
                        child = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                    } catch (IOException openFailure) {
                        throw new IOException("Output path contains a symbolic link or changed directory");
                    }
                    Object openedKey;
                    try {
                        openedKey = directoryIdentity(child);
                    } catch (IOException identityFailure) {
                        child.close();
                        throw identityFailure;
                    }
                    if (!childAttributes.fileKey().equals(openedKey)) {
                        child.close();
                        throw new IOException("Output directory changed while it was being opened");
                    }

                    current.close();
                    current = child;
                }
                Object finalKey = directoryIdentity(current);
                requireExclusiveWriteDirectory(current);
                success = true;
                return new SecureOutputDirectory(absolute, current, finalKey);
            } finally {
                if (!success) {
                    current.close();
                }
            }
        }

        private static BasicFileAttributes childAttributes(
                SecureDirectoryStream<Path> directory,
                Path name
        ) throws IOException {
            BasicFileAttributeView view = directory.getFileAttributeView(
                    name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                throw new IOException("File identities are unavailable on this filesystem");
            }
            return view.readAttributes();
        }

        private static Object directoryIdentity(SecureDirectoryStream<Path> directory) throws IOException {
            BasicFileAttributeView view = directory.getFileAttributeView(BasicFileAttributeView.class);
            if (view == null) {
                throw new IOException("Directory identities are unavailable on this filesystem");
            }
            BasicFileAttributes attributes = view.readAttributes();
            if (!attributes.isDirectory() || attributes.fileKey() == null) {
                throw new IOException("Directory identity cannot be verified safely");
            }
            return attributes.fileKey();
        }

        private static void requireExclusiveWriteDirectory(
                SecureDirectoryStream<Path> directory
        ) throws IOException {
            PosixFileAttributeView view = directory.getFileAttributeView(PosixFileAttributeView.class);
            if (view == null) {
                return;
            }
            Set<PosixFilePermission> permissions = view.readAttributes().permissions();
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IOException(
                        "Output directory must not be writable by group or other users");
            }
        }

        private BasicFileAttributes attributesIfPresent(Path name) throws IOException {
            try {
                return childAttributes(stream, name);
            } catch (NoSuchFileException missing) {
                return null;
            }
        }

        private OwnedFile ownedFile(Path name, String unavailableMessage) throws IOException {
            BasicFileAttributes attributes = attributesIfPresent(name);
            if (attributes == null || !attributes.isRegularFile() || attributes.fileKey() == null) {
                throw new IOException(unavailableMessage);
            }
            return new OwnedFile(name, attributes.fileKey());
        }

        private void requireIdentity(Path name, Object expectedFileKey, String message) throws IOException {
            BasicFileAttributes attributes = attributesIfPresent(name);
            if (attributes == null || !attributes.isRegularFile() || expectedFileKey == null
                    || !expectedFileKey.equals(attributes.fileKey())) {
                throw new IOException(message);
            }
        }

        private void requireOwned(OwnedFile file, String message) throws IOException {
            if (file == null) {
                throw new IOException(message);
            }
            requireIdentity(file.name, file.fileKey, message);
        }

        private void requireOwnedContent(OwnedFile file, String message) throws IOException {
            requireOwned(file, message);
            if (file.entryDigest == null
                    || !MessageDigest.isEqual(file.entryDigest, digestEntry(file.name))) {
                throw new IOException(message);
            }
            requireOwned(file, message);
        }

        private byte[] digestEntry(Path name) throws IOException {
            MessageDigest digest = sha256();
            try (SeekableByteChannel input = openForRead(name)) {
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                while (true) {
                    int read = input.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        continue;
                    }
                    buffer.flip();
                    digest.update(buffer);
                    buffer.clear();
                }
            }
            return digest.digest();
        }

        private void requireAbsent(Path name, String message) throws IOException {
            if (attributesIfPresent(name) != null) {
                throw new FileAlreadyExistsException(name.toString(), null, message);
            }
        }

        private SeekableByteChannel createNew(Path name) throws IOException {
            return createNew(name, posix ? OWNER_ONLY : null);
        }

        private SeekableByteChannel createNew(
                Path name,
                Set<PosixFilePermission> permissions
        ) throws IOException {
            Set<OpenOption> options = new HashSet<>();
            options.add(StandardOpenOption.CREATE_NEW);
            options.add(StandardOpenOption.WRITE);
            if (posix && permissions != null) {
                return stream.newByteChannel(
                        name,
                        options,
                        PosixFilePermissions.asFileAttribute(Set.copyOf(permissions))
                );
            }
            return stream.newByteChannel(name, options);
        }

        private Set<PosixFilePermission> permissions(Path name) throws IOException {
            if (!posix) {
                return null;
            }
            PosixFileAttributeView view = stream.getFileAttributeView(
                    name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                throw new IOException("POSIX metadata became unavailable during recovery");
            }
            PosixFileAttributes attributes = view.readAttributes();
            return Set.copyOf(attributes.permissions());
        }

        private void setPermissions(
                OwnedFile file,
                Set<PosixFilePermission> expectedPermissions
        ) throws IOException {
            if (!posix || expectedPermissions == null) {
                return;
            }
            requireOwnedContent(file, "Restored output changed before metadata recovery");
            PosixFileAttributeView view = stream.getFileAttributeView(
                    file.name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                throw new IOException("POSIX metadata became unavailable during recovery");
            }
            view.setPermissions(Set.copyOf(expectedPermissions));
            requireOwnedContent(file, "Restored output changed during metadata recovery");
            if (!expectedPermissions.equals(permissions(file.name))) {
                throw new IOException("Restored output permissions could not be verified");
            }
        }

        private SeekableByteChannel openForRead(Path name) throws IOException {
            Set<OpenOption> options = new HashSet<>();
            options.add(StandardOpenOption.READ);
            options.add(LinkOption.NOFOLLOW_LINKS);
            return stream.newByteChannel(name, options);
        }

        private void move(Path source, Path target) throws IOException {
            stream.move(source, stream, target);
        }

        private boolean restoreUnexpectedMovedEntry(Path quarantineName, Path originalName) {
            try {
                BasicFileAttributes attributes = attributesIfPresent(quarantineName);
                if (attributes == null || !attributes.isRegularFile() || attributes.fileKey() == null) {
                    return false;
                }
                byte[] contentDigest = digestEntry(quarantineName);
                OwnedFile displaced = new OwnedFile(
                        quarantineName,
                        attributes.fileKey(),
                        contentDigest,
                        contentDigest,
                        0
                );
                requireOwnedContent(displaced, "Displaced entry changed before recovery");
                if (attributesIfPresent(originalName) != null) {
                    return false;
                }
                OwnedFile restored = copyToVacantTarget(this, displaced, originalName);
                requireOwnedContent(restored, "Displaced entry recovery could not be verified");
                forceDirectory();
                return true;
            } catch (IOException | RuntimeException recoveryFailure) {
                return false;
            }
        }

        private Path uniqueVacantName(Path targetName, String suffix) throws IOException {
            for (int attempt = 0; attempt < 16; attempt++) {
                Path candidate = randomName(targetName, suffix);
                if (attributesIfPresent(candidate) == null) {
                    return candidate;
                }
            }
            throw new IOException("Could not reserve a unique transaction name");
        }

        private void removeOwned(OwnedFile owned) throws IOException {
            requireOwnedContent(owned, "Transaction file identity changed before cleanup");
            Path trash = uniqueVacantName(owned.name, ".delete");
            move(owned.name, trash);
            BasicFileAttributes movedAttributes = attributesIfPresent(trash);
            if (movedAttributes == null || !movedAttributes.isRegularFile()
                    || movedAttributes.fileKey() == null
                    || !owned.fileKey.equals(movedAttributes.fileKey())) {
                restoreUnexpectedMovedEntry(trash, owned.name);
                throw new IOException("Transaction file identity changed during cleanup");
            }
            OwnedFile moved = new OwnedFile(
                    trash,
                    owned.fileKey,
                    owned.entryDigest,
                    owned.payloadDigest,
                    owned.payloadOffset
            );
            requireOwnedContent(moved, "Transaction file changed during cleanup");
            stream.deleteFile(trash);
        }

        private void requireStillNamed() throws IOException {
            try (SecureOutputDirectory current = openExisting(absolutePath)) {
                if (!directoryFileKey.equals(current.directoryFileKey)) {
                    throw new IOException("Output directory changed during the write transaction");
                }
            }
        }

        private void forceDirectory() throws IOException {
            try (SeekableByteChannel channel = stream.newByteChannel(
                    Path.of("."), Set.of(StandardOpenOption.READ))) {
                force(channel);
            } catch (UnsupportedOperationException unsupported) {
                // Directory fsync is provider-specific. File channels are still
                // forced before every publication and restoration.
            }
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }
}
