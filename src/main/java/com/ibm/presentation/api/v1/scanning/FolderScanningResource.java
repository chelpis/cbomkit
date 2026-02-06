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
package com.ibm.presentation.api.v1.scanning;

import app.bootstrap.core.cqrs.ICommandBus;
import app.bootstrap.core.ddd.IDomainEventBus;
import com.ibm.domain.scanning.ScanId;
import com.ibm.infrastructure.progress.SyncProgressDispatcher;
import com.ibm.infrastructure.progress.WebSocketProgressDispatcher;
import com.ibm.infrastructure.scanning.IScanConfiguration;
import com.ibm.infrastructure.scanning.repositories.ScanRepository;
import com.ibm.usecases.scanning.commands.IndexModulesCommand;
import com.ibm.usecases.scanning.commands.RequestFolderScanCommand;
import com.ibm.usecases.scanning.commands.ScanCommand;
import com.ibm.usecases.scanning.processmanager.FolderScanProcessManager;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.Session;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST API endpoint for scanning pre-extracted folders by UUID.
 *
 * <p>
 * Usage:
 * 1. Place extracted project in {CBOMKIT_SCAN_FOLDER_PATH}/{uuid}/
 * 2. Connect WebSocket to ws://host/v1/scan/folder/{uuid}
 * 3. POST to /api/v1/scan/folder with uuid
 * 4. Receive progress via WebSocket
 */
@Path("/api/v1/scan/folder")
@ApplicationScoped
@Tag(name = "Folder Scanning Resource")
public final class FolderScanningResource {
  private static final Logger LOGGER = LoggerFactory.getLogger(FolderScanningResource.class);

  @Nonnull
  private final ICommandBus commandBus;
  @Nonnull
  private final IDomainEventBus domainEventBus;
  @Nonnull
  private final IScanConfiguration configuration;

  @Inject
  WebsocketFolderScanningResource websocketResource;

  public FolderScanningResource(
      @Nonnull ICommandBus commandBus,
      @Nonnull IDomainEventBus domainEventBus,
      @Nonnull IScanConfiguration configuration) {
    this.commandBus = commandBus;
    this.domainEventBus = domainEventBus;
    this.configuration = configuration;
  }

  public record FolderScanRequest(
      @Nonnull String uuid,
      @Nullable String projectName,
      @Nullable String subfolder) {
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Scan pre-extracted folder by UUID", description = "Scan a folder that was previously extracted. "
      + "UUID is used as both folder name and WebSocket clientId.")
  public Response scanFolder(FolderScanRequest request) {
    final String uuid = request.uuid();
    if (uuid == null || uuid.isBlank()) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(Map.of("error", "uuid is required"))
          .build();
    }

    final String projectName = Optional.ofNullable(request.projectName())
        .filter(s -> !s.isBlank())
        .orElse(uuid);
    final String subfolder = request.subfolder();

    LOGGER.info("Folder scan request for uuid: {}", uuid);

    // Validate folder exists
    final File folderPath = new File(configuration.getBaseScanFolderPath(), uuid);
    if (!folderPath.exists() || !folderPath.isDirectory()) {
      LOGGER.warn("Folder not found: {}", folderPath.getAbsolutePath());
      return Response.status(Response.Status.NOT_FOUND)
          .entity(Map.of("error", "Folder not found: " + uuid,
              "path", folderPath.getAbsolutePath()))
          .build();
    }

    // UUID is used as clientId for WebSocket
    final Optional<Session> wsSession = websocketResource.getSession(uuid);

    if (wsSession.isPresent() && wsSession.get().isOpen()) {
      // WebSocket mode
      return handleWebSocketScan(uuid, projectName, subfolder, folderPath, wsSession.get());
    } else {
      // Sync mode
      return handleSyncScan(uuid, projectName, subfolder, folderPath);
    }
  }

  private Response handleWebSocketScan(
      String uuid,
      String projectName,
      @Nullable String subfolder,
      File folderPath,
      Session session) {

    LOGGER.info("Starting WebSocket folder scan for uuid: {}", uuid);

    Thread.startVirtualThread(() -> {
      try {
        final ScanRepository scanRepository = new ScanRepository(this.domainEventBus);
        final ScanId scanId = new ScanId();
        final WebSocketProgressDispatcher progressDispatcher = new WebSocketProgressDispatcher(session);

        final FolderScanProcessManager processManager = new FolderScanProcessManager(
            scanId,
            this.commandBus,
            scanRepository,
            progressDispatcher,
            folderPath,
            projectName,
            subfolder,
            configuration.getJavaDependencyJARSPath());

        this.commandBus.register(
            processManager,
            List.of(
                RequestFolderScanCommand.class,
                IndexModulesCommand.class,
                ScanCommand.class));

        commandBus.send(new RequestFolderScanCommand(scanId, uuid, projectName, subfolder)).get();

      } catch (Exception e) {
        LOGGER.error("Error in WebSocket folder scan for uuid: {}", uuid, e);
      }
    });

    return Response.accepted()
        .entity(Map.of("message", "Scan started", "uuid", uuid))
        .build();
  }

  private Response handleSyncScan(
      String uuid,
      String projectName,
      @Nullable String subfolder,
      File folderPath) {

    LOGGER.info("Starting sync folder scan for uuid: {}", uuid);

    try {
      final ScanRepository scanRepository = new ScanRepository(this.domainEventBus);
      final ScanId scanId = new ScanId();
      final SyncProgressDispatcher progressDispatcher = new SyncProgressDispatcher();

      final FolderScanProcessManager processManager = new FolderScanProcessManager(
          scanId,
          this.commandBus,
          scanRepository,
          progressDispatcher,
          folderPath,
          projectName,
          subfolder,
          configuration.getJavaDependencyJARSPath());

      this.commandBus.register(
          processManager,
          List.of(
              RequestFolderScanCommand.class,
              IndexModulesCommand.class,
              ScanCommand.class));

      commandBus.send(new RequestFolderScanCommand(scanId, uuid, projectName, subfolder)).get();

      final String cbomJson = processManager.getCbomJson();
      if (cbomJson != null) {
        return Response.ok(cbomJson, MediaType.APPLICATION_JSON).build();
      } else {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .entity(Map.of("error", "Scan completed but no CBOM generated"))
            .build();
      }
    } catch (Exception e) {
      LOGGER.error("Error in sync folder scan for uuid: {}", uuid, e);
      return Response.serverError()
          .entity(Map.of("error", e.getMessage()))
          .build();
    }
  }
}
