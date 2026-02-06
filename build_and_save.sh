#!/bin/bash
set -e

# Build the backend jar
echo "Building backend jar..."
./mvnw clean package

# Build the backend image with the local-dev tag
echo "Building cbomkit:local-dev..."
docker build --platform linux/amd64 \
    -t cbomkit:local-dev \
    -f src/main/docker/Dockerfile.jvm \
    . \
    --load

# Save the image to a tar file
SAFE_TAG="cbomkit-local-dev.tar"
echo "Saving cbomkit:local-dev to ${SAFE_TAG}..."
docker save -o "${SAFE_TAG}" cbomkit:local-dev

echo "Done! Image saved to ${SAFE_TAG}"
