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

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket endpoint for folder scanning progress.
 * UUID is used as both folder name and clientId.
 *
 * <p>
 * Usage:
 * <ol>
 * <li>Connect to ws://host/v1/scan/folder/{uuid}</li>
 * <li>POST to /api/v1/scan/folder with same uuid</li>
 * <li>Receive progress messages via WebSocket</li>
 * </ol>
 */
@ServerEndpoint("/v1/scan/folder/{uuid}")
@ApplicationScoped
public class WebsocketFolderScanningResource {
  private static final Logger LOGGER = LoggerFactory.getLogger(WebsocketFolderScanningResource.class);

  @Nonnull
  private final Map<String, Session> sessions;

  public WebsocketFolderScanningResource() {
    this.sessions = new ConcurrentHashMap<>();
  }

  @OnOpen
  public void onOpen(Session session, @PathParam("uuid") String uuid) {
    LOGGER.info("Folder scan WebSocket opened for uuid: {}", uuid);
    sessions.put(uuid, session);
  }

  @OnClose
  public void onClose(Session session, @PathParam("uuid") String uuid) {
    LOGGER.info("Folder scan WebSocket closed for uuid: {}", uuid);
    sessions.remove(uuid);
  }

  @OnError
  public void onError(Session session, @PathParam("uuid") String uuid, Throwable throwable) {
    LOGGER.error("Folder scan WebSocket error for uuid: {}", uuid, throwable);
    sessions.remove(uuid);
  }

  @OnMessage
  public void onMessage(String message, Session session, @PathParam("uuid") String uuid) {
    LOGGER.debug("Folder scan WebSocket received from uuid {}: {}", uuid, message);
    // Keep connection alive
  }

  @Nonnull
  public Optional<Session> getSession(@Nonnull String uuid) {
    return Optional.ofNullable(sessions.get(uuid));
  }

  public boolean isConnected(@Nonnull String uuid) {
    Session session = sessions.get(uuid);
    return session != null && session.isOpen();
  }
}
