package com.docgen.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * "Apagar todos os meus dados": cancels running work, deletes every row of
 * the owner in one transaction, empties the upload staging area and asks for
 * the database file to be compacted (see {@link LocalDatabase}) so the deleted
 * rows do not linger in free pages of the file.
 *
 * <p>Logs are not user content (they never hold prompts, answers, attachments
 * or tokens) and are kept; the UI says so. Downloaded models belong to Ollama,
 * not to DocGen, and are removed in the model manager.</p>
 */
@Service
class DataErasureService {
    private static final Logger LOG = LoggerFactory.getLogger(DataErasureService.class);
    /** Children before parents, although the foreign keys also cascade from projects. */
    private static final String[] TABLES = {"artifacts", "plans", "attachments", "jobs", "projects", "app_settings"};

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JobService jobs;
    private final StorageConfig.StoragePaths paths;

    DataErasureService(JdbcClient jdbc, TransactionTemplate transactions, JobService jobs,
                       StorageConfig.StoragePaths paths) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.jobs = jobs;
        this.paths = paths;
    }

    void eraseAll(String owner) {
        jobs.cancelAllJobs(owner);
        transactions.executeWithoutResult(status -> {
            for (String table : TABLES) {
                jdbc.sql("DELETE FROM " + table + " WHERE owner = :owner").param("owner", owner).update();
            }
        });
        try {
            StorageConfig.clearStaleStagingFiles(paths.staging());
            if (!paths.temporary()) {
                LocalDatabase.requestCompaction(paths.dataDirectory());
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Local data cleanup failed", failure);
        }
        LOG.info("All local data was erased at the user's request");
    }
}
