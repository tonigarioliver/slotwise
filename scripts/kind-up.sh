#!/bin/bash
# A local Kubernetes cluster for Slotwise from scratch, in one command: creates the kind cluster if it is missing, copies the images (postgres, redis, kafka, keycloak) that are already in
# your Docker into the node (no internet; `kind load` fails with multi-arch images, so docker save | ctr import) and applies the kustomization.
set -e
cd "$(dirname "$0")/.."
kind get clusters | grep -qx slotwise || kind create cluster --name slotwise
for image in postgres:17 redis:7 apache/kafka:4.1.0 quay.io/keycloak/keycloak:26.0; do
    docker image inspect "$image" >/dev/null 2>&1 || docker pull "$image"
    docker save "$image" | docker exec -i slotwise-control-plane ctr --namespace=k8s.io images import - >/dev/null
done
kubectl apply -k .
