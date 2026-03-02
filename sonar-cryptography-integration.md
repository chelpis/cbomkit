# CBOMkit 中 Sonar-Cryptography 的導入與設定指南

本文件說明 `sonar-cryptography-plugin` 在 CBOMkit 專案中的建置 (Build) 與 Docker 層面的導入方式，以及如何替換為自定義版本的步驟。

## 1. Build 層面 (Maven)

目前專案中，`sonar-cryptography-plugin` 並非直接在 `pom.xml` 中引入，而是透過 `org.pqca:cbomkit-lib` 作為 **Transitive Dependency (傳遞依賴)** 被間接引入的。

在目前的 `pom.xml` 中，可以看到：
```xml
    <dependency>
      <groupId>org.pqca</groupId>
      <artifactId>cbomkit-lib</artifactId>
      <version>1.1.0</version>
    </dependency>
```

如果你在專案中執行 `./mvnw dependency:tree`，會看到以下的依賴樹狀結構：
```
[INFO] com.ibm:cbomkit:jar:2.0.0-SNAPSHOT
[INFO] \- org.pqca:cbomkit-lib:jar:1.1.0:compile
[INFO]    \- com.ibm:sonar-cryptography-plugin:jar:1.5.1:compile
```
這代表 `sonar-cryptography-plugin` 是透過 `cbomkit-lib` 自動拉下來的。

## 2. Docker 層面

在 Docker 的整合上，目前並沒有針對 `sonar-cryptography` 做額外的手動複製動作。因為它是 Maven 的依賴之一，當 Quarkus 在打包時 (透過執行 `./mvnw package`)，會自動將所有的依賴（包含 `sonar-cryptography-plugin.jar`）通通收集並打包到 `target/quarkus-app/lib/` 資料夾之下。

因此，在 `src/main/docker/Dockerfile.jvm` 中的這段設定：
```dockerfile
# 這裡會把所有透過 Maven 抓下來的 library (包含 cbomkit-lib 與 sonar-cryptography) 複製到 Image 中
COPY --chown=185 target/quarkus-app/lib/ /deployments/lib/
```
就已經自動將 `sonar-cryptography` 帶入到 Docker 容器內並載入至執行環境 (ClassPath) 中了。

*(註：Dockerfile 中的 `COPY --chown=185 src/main/resources/java/scan/*.jar /deployments/java/scan/` 主要是用來放 `bcprov-jdk18on` (BouncyCastle) 這類供掃描時外部掛載依賴的 JAR 檔，與 sonar-cryptography 本體無關)*

---

## 3. 如何導入自己寫的 Sonar-Cryptography

如果你想要在這個專案中導入你自己 clone 下來、修改並開發的 `sonar-cryptography-plugin`，你可以透過以下三個步驟覆蓋掉原本的官方依賴：

### 步驟一：在本地編譯並安裝你的自定義版本
首先，切換到你本地自定義的 `sonar-cryptography` 專案目錄下，執行 Maven 的 install 指令，將自定義編譯好的 jar 檔安裝到你本機的 Maven 快取 (`~/.m2/repository`) 中：

```bash
mvn clean install -DskipTests
```
這會安裝例如 `com.ibm:sonar-cryptography-plugin:1.5.1-SNAPSHOT` 到你的本機中 (實際版本號請依照你自訂專案 `pom.xml` 裡的 `<version>` 為準)。

### 步驟二：修改 CBOMkit 的 `pom.xml`
回到此 `cbomkit` 專案，打開 `pom.xml`，找到 `<dependencies>` 區塊，**加入你本地編譯出的版本的直接依賴**。
根據 Maven 的依賴解析機制，直接寫在 `pom.xml` 內的 dependency 會優先於傳遞依賴，這樣就能強制覆蓋掉 `cbomkit-lib` 預設的舊版本：

```xml
  <dependencies>
    <!-- ... 其他依賴省略 ... -->

    <!-- [新增] 加入你修改過的本地開發版本 -->
    <dependency>
      <groupId>com.ibm</groupId>
      <artifactId>sonar-cryptography-plugin</artifactId>
      <version>1.5.1-SNAPSHOT</version> <!-- 填入你自己編譯出的版本號 -->
    </dependency>

    <!-- 原本的 cbomkit-lib 維持不變 -->
    <dependency>
      <groupId>org.pqca</groupId>
      <artifactId>cbomkit-lib</artifactId>
      <version>1.1.0</version>
    </dependency>
  </dependencies>
```

### 步驟三：重新打包與構建 Docker Image
最後，依照原本的流程重新編譯專案：

```bash
# 1. 重新編譯 Quarkus 專案
# Quarkus 會改抓你剛剛定義在 pom.xml 的自訂版 sonar-cryptography-plugin JAR，放到 target/quarkus-app/lib/main 內
./mvnw clean package -DskipTests

# 2. 重新建置 Docker image
docker build -f src/main/docker/Dockerfile.jvm -t quarkus/cbomkit2-jvm .
```

完成後，執行這顆新的 Docker Image，裡面就會是你自己開發與改寫的 `sonar-cryptography` 掃描邏輯了！
