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
package com.ibm.usecases.scanning.processmanager;

import app.bootstrap.core.cqrs.ICommand;
import app.bootstrap.core.cqrs.ICommandBus;
import app.bootstrap.core.cqrs.ProcessManager;
import app.bootstrap.core.ddd.IRepository;
import com.ibm.domain.scanning.Language;
import com.ibm.domain.scanning.ScanAggregate;
import com.ibm.domain.scanning.ScanId;
import com.ibm.usecases.scanning.commands.IndexModulesCommand;
import com.ibm.usecases.scanning.commands.RequestFolderScanCommand;
import com.ibm.usecases.scanning.commands.ScanCommand;
import com.ibm.usecases.scanning.errors.NoIndexForProject;
import com.ibm.usecases.scanning.errors.NoProjectDirectoryProvided;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.pqca.errors.ClientDisconnected;
import java.io.File;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.pqca.indexing.ProjectModule;
import org.pqca.indexing.java.JavaIndexService;
import org.pqca.indexing.python.PythonIndexService;
import org.pqca.progress.IProgressDispatcher;
import org.pqca.progress.ProgressMessage;
import org.pqca.progress.ProgressMessageType;
import org.pqca.scanning.CBOM;
import org.pqca.scanning.ScanResultDTO;
import org.pqca.scanning.java.JavaScannerService;
import org.pqca.scanning.python.PythonScannerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process manager for scanning pre-extracted folders.
 * Unlike ZipScanProcessManager, this skips extraction and uses existing
 * folders.
 */
public final class FolderScanProcessManager extends ProcessManager<ScanId, ScanAggregate> {
  private static final Logger LOGGER = LoggerFactory.getLogger(FolderScanProcessManager.class);

  @Nonnull
  private final ScanId scanId;
  @Nonnull
  private final IProgressDispatcher progressDispatcher;
  @Nonnull
  private final File projectDirectory;
  @Nonnull
  private final String projectName;
  @Nullable
  private final String subfolder;
  @Nonnull
  private final String javaJarsDirPath;

  @Nonnull
  private final Map<Language, List<ProjectModule>> index = new EnumMap<>(Language.class);
  @Nullable
  private String cbomJson;
  @Nonnull
  private final CountDownLatch completionLatch = new CountDownLatch(1);

  public FolderScanProcessManager(
      @Nonnull ScanId scanId,
      @Nonnull ICommandBus commandBus,
      @Nonnull IRepository<ScanId, ScanAggregate> repository,
      @Nonnull IProgressDispatcher progressDispatcher,
      @Nonnull File projectDirectory,
      @Nonnull String projectName,
      @Nullable String subfolder,
      @Nonnull String javaJarsDirPath) {
    super(commandBus, repository);
    this.scanId = scanId;
    this.progressDispatcher = progressDispatcher;
    this.projectDirectory = projectDirectory;
    this.projectName = projectName;
    this.subfolder = subfolder;
    this.javaJarsDirPath = javaJarsDirPath;
  }

  @Override
  public void handle(@Nonnull ICommand command) throws Exception {
    switch (command) {
      case RequestFolderScanCommand c -> handleRequestFolderScanCommand(c);
      case IndexModulesCommand c -> handleIndexModulesCommand(c);
      case ScanCommand c -> handleScanCommand(c);
      default -> {
        // nothing
      }
    }
  }

  private void sendProgress(String stage, int progress, String message)
      throws ClientDisconnected {
    String json = String.format(
        "{\"stage\":\"%s\",\"progress\":%d,\"message\":\"%s\"}",
        stage, progress, escapeJson(message));
    progressDispatcher.send(new ProgressMessage(ProgressMessageType.LABEL, json));
  }

  private String escapeJson(String s) {
    if (s == null)
      return "";
    return s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private void handleRequestFolderScanCommand(@Nonnull RequestFolderScanCommand command)
      throws ClientDisconnected, Exception {
    if (!this.scanId.equals(command.id())) {
      return;
    }

    LOGGER.info("Starting folder scan for uuid: {}", command.uuid());
    sendProgress("SCANNING", 5, "Folder scan started for " + command.uuid());

    progressDispatcher.send(
        new ProgressMessage(ProgressMessageType.FOLDER, projectDirectory.getAbsolutePath()));

    sendProgress("INDEXING", 30, "Indexing modules...");
    this.commandBus.send(new IndexModulesCommand(command.id()));
  }

  private void handleIndexModulesCommand(@Nonnull IndexModulesCommand command)
      throws ClientDisconnected, Exception {
    if (!this.scanId.equals(command.id())) {
      return;
    }

    Path subfolderPath = this.subfolder != null ? Path.of(this.subfolder) : null;

    // Java indexing
    sendProgress("INDEXING", 35, "Indexing Java modules...");
    final JavaIndexService javaIndexService = new JavaIndexService(
        this.progressDispatcher, projectDirectory);
    final List<ProjectModule> javaIndex = javaIndexService.index(subfolderPath);
    this.index.put(Language.JAVA, javaIndex);

    // Python indexing
    sendProgress("INDEXING", 40, "Indexing Python modules...");
    final PythonIndexService pythonIndexService = new PythonIndexService(
        this.progressDispatcher, projectDirectory);
    final List<ProjectModule> pythonIndex = pythonIndexService.index(subfolderPath);
    this.index.put(Language.PYTHON, pythonIndex);

    sendProgress("SCANNING", 50, "Starting code scan...");
    this.commandBus.send(new ScanCommand(command.id()));
  }

  private void handleScanCommand(@Nonnull ScanCommand command)
      throws ClientDisconnected,
      NoProjectDirectoryProvided, NoIndexForProject {
    if (!this.scanId.equals(command.id())) {
      return;
    }

    try {
      Optional.of(this.index).filter(m -> !m.isEmpty()).orElseThrow(NoIndexForProject::new);

      final long startTime = System.currentTimeMillis();
      int numberOfScannedLines = 0;
      int numberOfScannedFiles = 0;

      // Java scanning
      sendProgress("SCANNING", 55, "Scanning Java files...");
      final JavaScannerService javaScannerService = new JavaScannerService(
          this.progressDispatcher, projectDirectory);
      javaScannerService.setRequireBuild(false);
      javaScannerService.addJavaDependencyJar(this.javaJarsDirPath);

      final ScanResultDTO javaScanResultDTO = javaScannerService.scan(this.index.get(Language.JAVA));
      numberOfScannedLines = javaScanResultDTO.numberOfScannedLines();
      numberOfScannedFiles = javaScanResultDTO.numberOfScannedFiles();
      sendProgress("SCANNING", 70, "Java scan complete: " + numberOfScannedFiles + " files");

      CBOM consolidatedCBOM = javaScanResultDTO.cbom();
      if (consolidatedCBOM != null) {
        consolidatedCBOM.addMetadata("folder://" + this.projectName, "folder", null, this.subfolder);
      }

      // Python scanning
      sendProgress("SCANNING", 75, "Scanning Python files...");
      final PythonScannerService pythonScannerService = new PythonScannerService(
          this.progressDispatcher, projectDirectory);
      final ScanResultDTO pythonScanResultDTO = pythonScannerService.scan(this.index.get(Language.PYTHON));
      numberOfScannedLines += pythonScanResultDTO.numberOfScannedLines();
      numberOfScannedFiles += pythonScanResultDTO.numberOfScannedFiles();
      sendProgress("SCANNING", 85, "Python scan complete");

      if (pythonScanResultDTO.cbom() != null) {
        pythonScanResultDTO.cbom().addMetadata(
            "folder://" + this.projectName, "folder", null, this.subfolder);

        if (consolidatedCBOM != null) {
          consolidatedCBOM.merge(pythonScanResultDTO.cbom());
        } else {
          consolidatedCBOM = pythonScanResultDTO.cbom();
        }
      }

      sendProgress("GENERATING", 90, "Generating CBOM...");

      // Send results
      progressDispatcher.send(
          new ProgressMessage(
              ProgressMessageType.SCANNED_DURATION,
              String.valueOf((System.currentTimeMillis() - startTime) / 1000)));
      progressDispatcher.send(
          new ProgressMessage(
              ProgressMessageType.SCANNED_FILE_COUNT,
              String.valueOf(numberOfScannedFiles)));
      progressDispatcher.send(
          new ProgressMessage(
              ProgressMessageType.SCANNED_NUMBER_OF_LINES,
              String.valueOf(numberOfScannedLines)));

      if (consolidatedCBOM != null) {
        this.cbomJson = consolidatedCBOM.toJSON().toString();
        progressDispatcher.send(
            new ProgressMessage(ProgressMessageType.CBOM, this.cbomJson));
      }

      sendProgress("COMPLETED", 100, "Scan finished");
    } catch (Exception | NoSuchMethodError e) {
      sendProgress("FAILED", 0, e.getMessage());
      progressDispatcher.send(new ProgressMessage(ProgressMessageType.ERROR, e.getMessage()));
      throw new RuntimeException(e);
    } finally {
      this.compensate(command.id());
    }
  }

  @Override
  public void compensate(@Nonnull ScanId id) {
    // Unregister process manager (but don't delete folder)
    this.commandBus.unregister(
        this,
        List.of(
            RequestFolderScanCommand.class,
            IndexModulesCommand.class,
            ScanCommand.class));
    completionLatch.countDown();
  }

  @Nullable
  public String getCbomJson() throws InterruptedException {
    completionLatch.await(30, TimeUnit.MINUTES);
    return cbomJson;
  }
}
