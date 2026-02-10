#!/bin/bash
set -e

# ============================================================
# Build & Push Docker image to ECR
# Usage:
#   ./build_and_push_ecr.sh              # tag = develop-<timestamp>
#   ./build_and_push_ecr.sh my-tag       # tag = my-tag
# ============================================================

ECR_REPO="442277771319.dkr.ecr.us-west-1.amazonaws.com/pqscan/cbomkit"
AWS_REGION="us-west-1"

# Determine tag
if [ -n "$1" ]; then
    TAG="$1"
else
    TAG="develop-$(date +%Y%m%d%H%M%S)"
fi

FULL_IMAGE="${ECR_REPO}:${TAG}"

echo "============================================"
echo "  ECR Repo : ${ECR_REPO}"
echo "  Tag      : ${TAG}"
echo "  Platform : linux/amd64"
echo "============================================"

# Step 1: Build the backend jar
echo ""
echo "▶ Building backend jar..."
./mvnw clean package -DskipTests

# Step 2: Login to ECR
echo ""
echo "▶ Logging in to ECR..."
aws ecr get-login-password --region "${AWS_REGION}" | \
    docker login --username AWS --password-stdin "${ECR_REPO%%/*}"

# Step 3: Build the Docker image
echo ""
echo "▶ Building Docker image: ${FULL_IMAGE}"
docker build --platform linux/amd64 \
    -t "${FULL_IMAGE}" \
    -f src/main/docker/Dockerfile.jvm \
    .

# Step 4: Push to ECR
echo ""
echo "▶ Pushing image to ECR..."
docker push "${FULL_IMAGE}"

echo ""
echo "============================================"
echo "  ✅ Done!"
echo "  Image pushed: ${FULL_IMAGE}"
echo "============================================"
