import json
import os
import time
import base64
from dataclasses import dataclass
from typing import Any, Dict, Optional, Tuple

import requests
from flask import Flask, Response, jsonify, request


GITHUB_API = os.environ.get("GITHUB_API", "https://api.github.com").rstrip("/")
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "").strip()
RELAY_OWNER = os.environ.get("RELAY_OWNER", "").strip()
RELAY_REPO = os.environ.get("RELAY_REPO", "").strip()
RELAY_BRANCH = os.environ.get("RELAY_BRANCH", "main").strip() or "main"
REGISTRY_PATH = os.environ.get("REGISTRY_PATH", "relay/current_device.json").strip() or "relay/current_device.json"
SECRET_PATH = os.environ.get("SECRET_PATH", "relay/phone_api_secret.json").strip() or "relay/phone_api_secret.json"
BRIDGE_BEARER_TOKEN = os.environ.get("BRIDGE_BEARER_TOKEN", "").strip()
CACHE_TTL_SECONDS = max(0, int(os.environ.get("CACHE_TTL_SECONDS", "10")))
REQUEST_TIMEOUT_SECONDS = max(3, int(os.environ.get("REQUEST_TIMEOUT_SECONDS", "20")))

app = Flask(__name__)


@dataclass
class CacheEntry:
    value: Dict[str, Any]
    fetched_at: float


_registry_cache: Optional[CacheEntry] = None
_secret_cache: Optional[CacheEntry] = None


def _headers() -> Dict[str, str]:
    if not GITHUB_TOKEN:
        raise RuntimeError("GITHUB_TOKEN is required.")
    return {
        "Authorization": f"Bearer {GITHUB_TOKEN}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "phone-ai-fast-bridge/1.0",
    }


def _assert_configured() -> None:
    missing = []
    if not GITHUB_TOKEN:
        missing.append("GITHUB_TOKEN")
    if not RELAY_OWNER:
        missing.append("RELAY_OWNER")
    if not RELAY_REPO:
        missing.append("RELAY_REPO")
    if missing:
        raise RuntimeError("Missing required environment variables: " + ", ".join(missing))


def _github_contents_url(path: str) -> str:
    return f"{GITHUB_API}/repos/{RELAY_OWNER}/{RELAY_REPO}/contents/{path}"


def _fetch_json_file(path: str) -> Dict[str, Any]:
    response = requests.get(
        _github_contents_url(path),
        headers=_headers(),
        params={"ref": RELAY_BRANCH},
        timeout=REQUEST_TIMEOUT_SECONDS,
    )
    response.raise_for_status()
    payload = response.json()
    content = payload.get("content", "") or ""
    if not content:
        raise RuntimeError(f"{path} is empty.")
    raw = base64.b64decode(content.replace("\n", "").replace("\r", ""))
    return json.loads(raw.decode("utf-8"))


def _load_registry(force: bool = False) -> Dict[str, Any]:
    global _registry_cache
    if not force and _registry_cache and time.time() - _registry_cache.fetched_at <= CACHE_TTL_SECONDS:
        return _registry_cache.value
    value = _fetch_json_file(REGISTRY_PATH)
    _registry_cache = CacheEntry(value=value, fetched_at=time.time())
    return value


def _load_secret(force: bool = False) -> Dict[str, Any]:
    global _secret_cache
    if not force and _secret_cache and time.time() - _secret_cache.fetched_at <= CACHE_TTL_SECONDS:
        return _secret_cache.value
    value = _fetch_json_file(SECRET_PATH)
    _secret_cache = CacheEntry(value=value, fetched_at=time.time())
    return value


def _resolve_target(force: bool = False) -> Tuple[str, str, Dict[str, Any]]:
    registry = _load_registry(force=force)
    public_url = str(registry.get("public_url", "") or "").strip().rstrip("/")
    if not public_url:
        raise RuntimeError("relay/current_device.json does not contain a public_url.")
    secret = _load_secret(force=force)
    phone_token = str(secret.get("phone_api_bearer_token", "") or "").strip()
    if not phone_token:
        raise RuntimeError("relay/phone_api_secret.json does not contain a phone_api_bearer_token.")
    return public_url, phone_token, registry


def _bridge_auth_ok() -> bool:
    if not BRIDGE_BEARER_TOKEN:
        return True
    auth = request.headers.get("Authorization", "").strip()
    return auth == f"Bearer {BRIDGE_BEARER_TOKEN}"


def _forward(path: str, force_refresh: bool = False) -> Response:
    public_url, phone_token, registry = _resolve_target(force=force_refresh)
    target_url = f"{public_url}/{path.lstrip('/')}" if path else public_url
    headers = {
        "Authorization": f"Bearer {phone_token}",
        "User-Agent": "phone-ai-fast-bridge/1.0",
        "bypass-tunnel-reminder": "1",
    }
    content_type = request.headers.get("Content-Type", "").strip()
    if content_type:
        headers["Content-Type"] = content_type
    upstream = requests.request(
        method=request.method,
        url=target_url,
        headers=headers,
        params=request.args,
        data=request.get_data() if request.method not in {"GET", "HEAD"} else None,
        timeout=REQUEST_TIMEOUT_SECONDS,
    )
    if upstream.status_code in {502, 503, 504} and not force_refresh:
        refreshed_public_url, _, _ = _resolve_target(force=True)
        if refreshed_public_url != public_url:
            return _forward(path, force_refresh=True)
    response_headers = {}
    if upstream.headers.get("Content-Type"):
        response_headers["Content-Type"] = upstream.headers["Content-Type"]
    if upstream.headers.get("Content-Disposition"):
        response_headers["Content-Disposition"] = upstream.headers["Content-Disposition"]
    response_headers["X-Phone-Public-Url"] = public_url
    response_headers["X-Registry-Updated-At"] = str(registry.get("updated_at", ""))
    return Response(upstream.content, status=upstream.status_code, headers=response_headers)


@app.get("/healthz")
def healthz() -> Response:
    try:
        _assert_configured()
        public_url, _, registry = _resolve_target(force=False)
        return jsonify(
            {
                "ok": True,
                "bridge_mode": "github-registry-fast-bridge",
                "relay_owner": RELAY_OWNER,
                "relay_repo": RELAY_REPO,
                "relay_branch": RELAY_BRANCH,
                "registry_path": REGISTRY_PATH,
                "public_url": public_url,
                "registry_updated_at": registry.get("updated_at"),
                "healthy": registry.get("healthy"),
                "bridge_auth_required": bool(BRIDGE_BEARER_TOKEN),
            }
        )
    except Exception as exc:
        return jsonify({"ok": False, "error": str(exc)}), 503


@app.get("/openapi.json")
def openapi_proxy() -> Response:
    try:
        if not _bridge_auth_ok():
            return jsonify({"error": "Unauthorized"}), 401
        public_url, phone_token, _ = _resolve_target(force=False)
        upstream = requests.get(
            public_url + "/openapi.json",
            headers={
                "Authorization": f"Bearer {phone_token}",
                "User-Agent": "phone-ai-fast-bridge/1.0",
                "bypass-tunnel-reminder": "1",
            },
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
        upstream.raise_for_status()
        schema = upstream.json()
        schema["servers"] = [{"url": request.url_root.rstrip("/")}]
        info = schema.setdefault("info", {})
        info["title"] = str(info.get("title", "Phone AI API")) + " (via Fast Bridge)"
        return jsonify(schema)
    except Exception as exc:
        return jsonify({"error": str(exc)}), 502


@app.route("/", defaults={"path": ""}, methods=["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"])
@app.route("/<path:path>", methods=["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"])
def proxy(path: str) -> Response:
    if not _bridge_auth_ok():
        return jsonify({"error": "Unauthorized"}), 401
    if path in {"healthz", "openapi.json"}:
        return jsonify({"error": "Reserved path"}), 404
    try:
        return _forward(path, force_refresh=False)
    except Exception as exc:
        return jsonify({"error": str(exc)}), 502


if __name__ == "__main__":
    _assert_configured()
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "8788")))
