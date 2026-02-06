/*
 * CBOMkit
 * Copyright (C) 2025 PQCA
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ibm.infrastructure.progress;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.pqca.errors.ClientDisconnected;
import org.pqca.progress.IProgressDispatcher;
import org.pqca.progress.ProgressMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synchronous progress dispatcher that collects results and waits for
 * completion. Used for
 * synchronous ZIP scanning where we want to return the CBOM directly.
 */
public final class SyncProgressDispatcher implements IProgressDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(SyncProgressDispatcher.class);
    private static final long TIMEOUT_MINUTES = 60; // Increased for large projects

    private final CountDownLatch completionLatch;
    @Nullable
    private String cbomResult;
    @Nullable
    private String errorMessage;
    private boolean finished;

    public SyncProgressDispatcher() {
        this.completionLatch = new CountDownLatch(1);
        this.finished = false;
    }

    @Override
    public void send(@Nonnull ProgressMessage message) throws ClientDisconnected {
        LOGGER.info("SyncProgressDispatcher received: {} - {}", message.type(), truncate(message.message(), 100));

        switch (message.type()) {
            case CBOM -> this.cbomResult = message.message();
            case ERROR -> {
                this.errorMessage = message.message();
                this.finished = true;
                this.completionLatch.countDown();
            }
            case LABEL -> {
                if ("Finished".equals(message.message())) {
                    this.finished = true;
                    this.completionLatch.countDown();
                }
            }
            default -> {
                // just log other messages
            }
        }
    }

    /**
     * Waits for the scan to complete and returns the CBOM result.
     *
     * @return The CBOM JSON string, or null if scan failed or timed out
     */
    @Nullable
    public String getCbomResult() {
        try {
            boolean completed = this.completionLatch.await(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!completed) {
                LOGGER.warn("Scan timed out after {} minutes", TIMEOUT_MINUTES);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while waiting for scan completion");
            return null;
        }
        return this.cbomResult;
    }

    /**
     * Returns the error message if an error occurred.
     *
     * @return The error message, or null if no error
     */
    @Nullable
    public String getErrorMessage() {
        return this.errorMessage;
    }

    /**
     * Returns whether the scan has finished.
     *
     * @return true if finished (success or error)
     */
    public boolean isFinished() {
        return this.finished;
    }

    private String truncate(String s, int maxLength) {
        if (s == null) {
            return null;
        }
        if (s.length() <= maxLength) {
            return s;
        }
        return s.substring(0, maxLength) + "...";
    }
}
