"""Shared Weaviate client construction (URL + optional API key from env)."""
from __future__ import annotations

import os
from urllib.parse import urlparse

import weaviate
from weaviate.classes.init import Auth


def _parsed_weaviate_url() -> tuple[str, int, bool]:
    raw = os.environ.get("WEAVIATE_URL", "http://localhost:8008").strip()
    if not raw.startswith("http://") and not raw.startswith("https://"):
        raw = "http://" + raw
    parsed = urlparse(raw)
    host = parsed.hostname or "localhost"
    port = parsed.port or (443 if parsed.scheme == "https" else 8008)
    secure = parsed.scheme == "https"
    return host, port, secure


def connect_weaviate():
    """Connect using WEAVIATE_URL, WEAVIATE_GRPC_PORT, WEAVIATE_API_KEY."""
    host, port, secure = _parsed_weaviate_url()
    grpc_port = int(os.environ.get("WEAVIATE_GRPC_PORT", "50051"))
    api_key = os.environ.get("WEAVIATE_API_KEY", "").strip()
    auth = Auth.api_key(api_key) if api_key else None
    return weaviate.connect_to_custom(
        http_host=host,
        http_port=port,
        http_secure=secure,
        grpc_host=host,
        grpc_port=grpc_port,
        auth_credentials=auth,
    )
