# CBOM 掃描功能擴充與腳本使用說明 (feat/file-url Branch)

## 相關 Repository

- 主要偵測 Plugin：[chelpis/sonar-cryptography](https://github.com/chelpis/sonar-cryptography)
- 主要 Server：[chelpis/cbomkit](https://github.com/chelpis/cbomkit)

---

## 1. 新增本機資料夾掃描 (Folder Scanning) API

### REST API
- 新增 `FolderScanningResource`
  - 相關檔案：[`src/main/java/com/ibm/presentation/api/v1/scanning/FolderScanningResource.java`](./src/main/java/com/ibm/presentation/api/v1/scanning/FolderScanningResource.java)
  - 使用者可直接傳入本機資料夾路徑啟動 CBOM 掃描
  - 不需透過 Git clone 流程
  - Commit Hash: `2513b27` (feat(scan): add UUID-based folder scanning API)

### WebSocket 通訊支援
- 新增 `WebsocketFolderScanningResource`
  - 相關檔案：[`src/main/java/com/ibm/presentation/api/v1/scanning/WebsocketFolderScanningResource.java`](./src/main/java/com/ibm/presentation/api/v1/scanning/WebsocketFolderScanningResource.java)
  - 即時回傳掃描進度字串給前端
  - 補充 [`docs/websocket-api.md`](./docs/websocket-api.md) 使用說明
  - Commit Hash: `5e104ba` (docs: add WebSocket API documentation)

### 邏輯處理
- 實作 `FolderScanProcessManager`
  - 相關檔案：[`src/main/java/com/ibm/usecases/scanning/processmanager/FolderScanProcessManager.java`](./src/main/java/com/ibm/usecases/scanning/processmanager/FolderScanProcessManager.java)
  - 每次掃描任務以 UUID 作為識別碼
  - 專門處理 Folder Scan 任務生命週期
  - Commit Hash: `2513b27` (feat(scan): add UUID-based folder scanning API)

---

## 2. 擴充語言掃描支援

- 在 Folder Scanning 流程中整合 Go 語言掃描
- 包含：
  - `add go scan in folder scanner` (Commit Hash: `e4ec870`)
  - `Go integration (#323)` (Commit Hash: `5c19662`)

---

## 3. ZIP 掃描功能雛型

- 新增 `RequestZipScanCommand.java`
  - 相關檔案：[`src/main/java/com/ibm/usecases/scanning/commands/RequestZipScanCommand.java`](./src/main/java/com/ibm/usecases/scanning/commands/RequestZipScanCommand.java)
  - 為「上傳 ZIP 壓縮檔進行掃描」功能建立基礎指令架構
  - 作為後續 ZIP Scan 功能擴充的準備

---

## 4. 基礎設施與建置流程

### OpenAPI 更新
- 更新 [`openapi.yaml`](./openapi.yaml)
- 更新 [`openapi.json`](./openapi.json)
- 同步新增 Scanner 相關端點定義
- Commit Hash: `46a2e71` (chore: update OpenAPI spec)

### Docker / AWS ECR Scripts
新增多個輔助腳本：

- [`build_and_push_ecr.sh`](./build_and_push_ecr.sh) (Commit Hash: `62a3623`): 快速建置並推送 Docker Image 至 ECR。
- [`build_and_save.sh`](./build_and_save.sh) (Commit Hash: `cdcb2de`): 建置 Image 並匯出為 tar 檔。
- [`build_and_run_docker_compose.sh`](./build_and_run_docker_compose.sh) (Commit Hash: `cdcb2de`): 簡化本地編譯與啟動容器流程。
- [`scan_folder.sh`](./scan_folder.sh) (Commit Hash: `cdcb2de`): 用於觸發並測試本機資料夾掃描的請求。

---

## 5. 核心腳本使用指南

### 腳本一：[`build_and_run_docker_compose.sh`](./build_and_run_docker_compose.sh)

這支腳本的主要目的是幫助開發者**「一鍵完成 Java 專案編譯、建立最新的映像檔 (Image) 並使用 Docker Compose 更新重啟服務」**。

**執行方式：**
```bash
chmod +x build_and_run_docker_compose.sh
./build_and_run_docker_compose.sh
```

**⚠️ 重要注意事項與 `.env` 設定限制：**
在腳本中，執行 `docker compose` 時並不會像 `Makefile` 那樣自動帶入環境變數。因此，強烈建議在專案根目錄下建立一個 `.env` 檔案，來對齊 `Makefile` 中的設定（特別是資料庫密碼）：

```dotenv
# .env 檔案範例 (放置於專案根目錄)
CBOMKIT_VIEWER=false
POSTGRESQL_AUTH_USERNAME=postgres
POSTGRESQL_AUTH_PASSWORD=password
```
*(注意：若執行後沒有吃到新的 Image 版本，請至 `docker-compose.yaml` 的 prod profile 中確認 backend image 標籤是否已改為 `cbomkit:local-dev`。)*

---

### 腳本二：[`scan_folder.sh`](./scan_folder.sh)

這是一支用於發送 API 請求以觸發本地端 Folder Scan 並觀測回傳狀態的除錯腳本。它支援一般 HTTP 同步模式與 WebSocket 即時進度收聽（如果系統有裝 `websocat`）。

**使用環境假設：**
目標伺服器預設為 `http://localhost:8081`。伺服器上會讀取配置去掃描對應路徑下的目錄。

**執行方式：**
```bash
chmod +x scan_folder.sh
./scan_folder.sh <uuid>
```
1. 傳入一個 `<uuid>` 作為要除錯掃描的目標 ID。伺服器端將會嘗試去尋找 `$CBOMKIT_SCAN_FOLDER_PATH/<uuid>` 對應的目錄進行分析。

**執行流程重點：**
1. **Health Check**：先戳 `/api` 確保伺服器存活返回 `200`。
2. **WebSocket 模式（若有安裝 websocat）**：會在背景連線 `ws://localhost:8081/v1/scan/folder/<uuid>` 監聽 WebSocket 進度，收到型態為 `CBOM` 的訊息就會輸出結果，將報告存為 `<uuid>_cbom.json`。
3. **Sync HTTP 模式（若無 websocat）**：若是沒有上述工具，則會改發一般的 REST POST 到 `/api/v1/scan/folder`。
4. **輸出結果**：一旦掃描完成，執行目錄下就會自動多出一個 `<uuid>_cbom.json` 檔案記錄掃描結果。
