import json
import os
import pathlib
import re
import sys
import urllib.error
import urllib.request
import urllib.parse
from datetime import datetime, timezone


REQUEST_MARKER = "<!-- phone-ai-relay-request -->"
RESULT_PATH = os.environ["RELAY_RESULT_PATH"]
WORKSPACE = pathlib.Path(os.environ.get("GITHUB_WORKSPACE", ".")).resolve()
EVENT_PATH = pathlib.Path(os.environ["GITHUB_EVENT_PATH"]).resolve()


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def load_event() -> dict:
    return json.loads(EVENT_PATH.read_text(encoding="utf-8"))


def parse_request_body(issue_body: str) -> dict:
    body = issue_body or ""
    if REQUEST_MARKER not in body:
        raise ValueError("Issue body is missing the phone relay marker.")
    fenced = re.search(r"```json\s*(\{.*?\})\s*```", body, re.DOTALL)
    if fenced:
        payload_text = fenced.group(1)
    else:
        fallback = body.split(REQUEST_MARKER, 1)[1].strip()
        payload_text = fallback
    payload = json.loads(payload_text)
    if not isinstance(payload, dict):
        raise ValueError("Relay payload must be a JSON object.")
    path = str(payload.get("path", "")).strip()
    method = str(payload.get("method", "GET")).strip().upper()
    if not path.startswith("/"):
        raise ValueError("Relay payload path must start with '/'.")
    if method not in {"GET", "POST", "PUT", "PATCH", "DELETE"}:
        raise ValueError(f"Unsupported method: {method}")
    if not path.startswith("/v1/") and path != "/healthz":
        raise ValueError("Relay path must target /healthz or /v1/* only.")
    timeout_sec = payload.get("timeout_sec", 45)
    if timeout_sec is None:
        timeout_sec = 45
    timeout_sec = int(timeout_sec)
    payload["timeout_sec"] = max(5, min(timeout_sec, 120))
    close_issue = payload.get("close_issue", True)
    payload["close_issue"] = bool(close_issue)
    expected_status = payload.get("expected_status")
    if expected_status is not None:
        if isinstance(expected_status, int):
            expected_status = [expected_status]
        if not isinstance(expected_status, list) or not expected_status:
            raise ValueError("expected_status must be an integer or a non-empty list of integers.")
        payload["expected_status"] = [int(item) for item in expected_status]
    query = payload.get("query")
    if query is not None and not isinstance(query, dict):
        raise ValueError("query must be an object when provided.")
    return payload


def load_public_url() -> str:
    secret_url = os.environ.get("PHONE_API_PUBLIC_URL", "").strip()
    if secret_url:
        return secret_url.rstrip("/")
    current_device = WORKSPACE / "relay" / "current_device.json"
    if current_device.exists():
        data = json.loads(current_device.read_text(encoding="utf-8"))
        return str(data.get("public_url", "")).strip().rstrip("/")
    raise RuntimeError("No phone API public URL is configured. Set PHONE_API_PUBLIC_URL or commit relay/current_device.json.")


def load_bearer_token() -> str:
    token = os.environ.get("PHONE_API_BEARER_TOKEN", "").strip()
    if not token:
        raise RuntimeError("PHONE_API_BEARER_TOKEN secret is missing.")
    return token


def build_target_url(public_url: str, payload: dict) -> str:
    path = str(payload["path"]).strip()
    query = payload.get("query") or {}
    if not query:
        return public_url + path
    normalized_query = []
    for key, value in query.items():
        if isinstance(value, list):
            for item in value:
                normalized_query.append((key, str(item)))
        else:
            normalized_query.append((key, str(value)))
    return public_url + path + "?" + urllib.parse.urlencode(normalized_query, doseq=True)


def make_request(public_url: str, token: str, payload: dict) -> dict:
    method = str(payload.get("method", "GET")).upper()
    url = build_target_url(public_url, payload)
    request_body = payload.get("body")
    body_bytes = None
    headers = {
        "User-Agent": "phone-ai-github-relay/1.0",
        "Accept": "application/json",
        "Authorization": f"Bearer {token}",
        "bypass-tunnel-reminder": "1",
    }
    if request_body is not None:
        body_bytes = json.dumps(request_body).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"

    request = urllib.request.Request(url, data=body_bytes, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=int(payload.get("timeout_sec", 45))) as response:
            raw = response.read()
            status = response.getcode()
            content_type = response.headers.get("Content-Type", "")
    except urllib.error.HTTPError as error:
        raw = error.read()
        status = error.code
        content_type = error.headers.get("Content-Type", "")
    except Exception as error:  # noqa: BLE001
        return {
            "ok": False,
            "status_code": -1,
            "content_type": "text/plain",
            "response_text": f"{type(error).__name__}: {error}",
        }

    try:
        response_text = raw.decode("utf-8")
    except UnicodeDecodeError:
        response_text = raw.decode("utf-8", errors="replace")

    expected_status = payload.get("expected_status")
    ok = status in expected_status if expected_status else 200 <= status < 300
    result = {
        "ok": ok,
        "status_code": status,
        "content_type": content_type,
        "response_text": response_text,
    }
    if "json" in content_type.lower():
        try:
            result["response_json"] = json.loads(response_text)
        except json.JSONDecodeError:
            pass
    return result


def truncate_text(text: str, max_chars: int = 50000) -> str:
    if len(text) <= max_chars:
        return text
    return text[:max_chars] + "\n... [truncated]"


def build_comment(issue_number: int, public_url: str, payload: dict, result: dict) -> str:
    request_id = str(payload.get("request_id", f"issue-{issue_number}"))
    lines = [
        "<!-- phone-ai-relay-response -->",
        f"Relay handled at: {utc_now()}",
        f"Request ID: {request_id}",
        f"Method: {str(payload.get('method', 'GET')).upper()}",
        f"Path: {payload.get('path', '')}",
        f"Phone API URL used: {public_url}",
        f"HTTP status: {result.get('status_code', -1)}",
        f"Result: {'completed' if result.get('ok') else 'failed'}",
        "",
    ]
    if "response_json" in result:
        response_block = json.dumps(result["response_json"], ensure_ascii=False, indent=2)
        lines.extend(["```json", truncate_text(response_block), "```"])
    else:
        lines.extend(["```text", truncate_text(result.get("response_text", "")), "```"])
    return "\n".join(lines)


def write_result(ok: bool, comment_body: str, close_issue: bool = True) -> None:
    output = {
        "ok": ok,
        "comment_body": comment_body,
        "close_issue": close_issue,
    }
    pathlib.Path(RESULT_PATH).write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> int:
    event = load_event()
    issue = event.get("issue") or {}
    issue_number = issue.get("number", 0)
    try:
        payload = parse_request_body(issue.get("body", ""))
        public_url = load_public_url()
        token = load_bearer_token()
        relay_result = make_request(public_url, token, payload)
        comment_body = build_comment(issue_number, public_url, payload, relay_result)
        write_result(relay_result.get("ok", False), comment_body, close_issue=bool(payload.get("close_issue", True)))
        return 0
    except Exception as error:  # noqa: BLE001
        comment_body = "\n".join(
            [
                "<!-- phone-ai-relay-response -->",
                f"Relay handled at: {utc_now()}",
                "Result: failed before the phone request could be sent.",
                "",
                "```text",
                truncate_text(f"{type(error).__name__}: {error}"),
                "```",
            ]
        )
        write_result(False, comment_body, close_issue=True)
        return 0


if __name__ == "__main__":
    sys.exit(main())
