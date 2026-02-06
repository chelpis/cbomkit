# 1. 先編譯 Java 專案                     
./mvnw package -DskipTests
# 2. 用 Dockerfile.jvm 建立 image
docker build -f src/main/docker/Dockerfile.jvm -t cbomkit:local-dev .
# 3. 重啟 container
docker compose --profile prod down
docker compose --profile prod up -d