#!/bin/bash
# A local Kubernetes cluster for Slotwise from scratch, in one command: creates the kind cluster if it is missing, copies the images (postgres, redis, kafka, keycloak, the OpenTelemetry Collector and Grafana LGTM) that are already in
# your Docker into the node (no internet; `kind load` fails with multi-arch images, so docker save | ctr import) and applies the kustomization.
set -e
cd "$(dirname "$0")/.."
kind get clusters | grep -qx slotwise || kind create cluster --name slotwise
for image in postgres:17 redis:7 apache/kafka:4.1.0 quay.io/keycloak/keycloak:26.0 otel/opentelemetry-collector-contrib:0.163.1 grafana/otel-lgtm:0.37.0 istio/pilot:1.31.4 istio/proxyv2:1.31.4; do
    docker image inspect "$image" >/dev/null 2>&1 || docker pull "$image"
    docker save "$image" | docker exec -i slotwise-control-plane ctr --namespace=k8s.io images import - >/dev/null
done
# The services' images are built here (Docker's layer cache makes an unchanged one instant) and copied like the others; re-run the script after changing code.
for service in audit-service booking-service notification-service; do
    docker build -q --build-arg SERVICE="$service" -t "slotwise/$service:dev" . >/dev/null
    docker save "slotwise/$service:dev" | docker exec -i slotwise-control-plane ctr --namespace=k8s.io images import - >/dev/null
done
# Istio (the entry to the cluster): the Gateway API CRDs (server-side apply: some are too big for the usual one) and the control plane, before the
# kustomization, which contains a Gateway. Needs istioctl 1.31.4 on the PATH (see decisions.md).
command -v istioctl >/dev/null || { echo "istioctl 1.31.4 is needed, see decisions.md" >&2; exit 1; }
kubectl apply --server-side -f k8s/istio/gateway-api-crds-v1.6.3.yaml >/dev/null
istioctl install -f k8s/istio/istio-operator.yaml -y
kubectl apply -k .
