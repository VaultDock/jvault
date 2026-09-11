#!/usr/bin/env python3
"""Check the claims this design marked TO VERIFY against a real Jira Cloud site.

Credentials come from the environment and are never written anywhere:

    export JIRA_SITE=https://your-site.atlassian.net
    export JIRA_EMAIL=you@example.com     # the Atlassian account email
    export JIRA_TOKEN=...                 # an API token, used with basic auth
    ./scripts/verify_jira.py [PROJECT_KEY] [--write]

Read-only unless --write is given. The write check creates a real issue and prints the
command to delete it; it is the one that settles docs/00-verified-capabilities.md 0.9,
which the ambiguity protocol depends on.
"""

import base64
import json
import os
import ssl
import sys
import urllib.error
import urllib.request

SITE = os.environ["JIRA_SITE"].rstrip("/")
EMAIL = os.environ["JIRA_EMAIL"]
TOKEN = os.environ["JIRA_TOKEN"]

_AUTH = base64.b64encode(f"{EMAIL}:{TOKEN}".encode()).decode()


def _tls_context():
    """A verifying TLS context, with a CA bundle this interpreter can actually find.

    Some Python installs on macOS ship without a usable CA store, which makes every HTTPS call
    fail with CERTIFICATE_VERIFY_FAILED. The fix is to point at a real bundle — certifi's, or the
    system one — never to disable verification. A `verify=False` here would be the same
    "trustAllCertificates" switch the design says must not exist, and switches like that have a
    way of surviving into production.
    """
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except ImportError:
        pass
    for candidate in ("/etc/ssl/cert.pem", "/usr/local/etc/openssl/cert.pem"):
        if os.path.exists(candidate):
            return ssl.create_default_context(cafile=candidate)
    return ssl.create_default_context()


_TLS = _tls_context()


def call(method, path, body=None):
    """Returns (status, parsed_body_or_text, headers). Never raises on an HTTP error."""
    url = path if path.startswith("http") else SITE + path
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Authorization", "Basic " + _AUTH)
    request.add_header("Accept", "application/json")
    if data:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, context=_TLS) as response:
            raw = response.read().decode()
            headers = dict(response.headers)
            status = response.status
    except urllib.error.HTTPError as error:
        raw = error.read().decode()
        headers = dict(error.headers)
        status = error.code
    except Exception as error:                                  # noqa: BLE001
        return 0, str(error), {}
    try:
        return status, json.loads(raw) if raw else None, headers
    except json.JSONDecodeError:
        return status, raw, headers


def section(title):
    print(f"\n\033[1m{title}\033[0m")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    write = "--write" in sys.argv
    project = args[0] if args else None

    section("1. Authentication and identity")
    status, me, _ = call("GET", "/rest/api/3/myself")
    print(f"  GET /rest/api/3/myself -> {status}")
    if status != 200:
        print("  authentication failed; nothing below would be meaningful")
        return 1
    print(f"  accountId   {me.get('accountId')}")
    print(f"  accountType {me.get('accountType')}   active={me.get('active')}")

    section("2. Deployment")
    status, info, _ = call("GET", "/rest/api/3/serverInfo")
    if status == 200:
        print(f"  deploymentType={info.get('deploymentType')}  version={info.get('version')}")
        print(f"  baseUrl={info.get('baseUrl')}")

    section("3. Rate-limit headers on an ordinary call (docs 0.3)")
    _, _, headers = call("GET", "/rest/api/3/myself")
    interesting = {k: v for k, v in headers.items()
                   if k.lower().startswith(("x-ratelimit", "retry-after", "ratelimit"))}
    print(f"  {interesting if interesting else 'none present far from the limit, as expected'}")

    section("4. Projects visible to this identity")
    status, projects, _ = call("GET", "/rest/api/3/project/search?maxResults=50")
    if status == 200:
        for p in projects.get("values", []):
            print(f"  {p['key']:10} {p['name']:30} id={p['id']:6} style={p.get('style')}")
        if not project and projects.get("values"):
            project = projects["values"][0]["key"]
    print(f"  using project: {project}")
    if not project:
        return 1

    section("5. createmeta: the DEPRECATED aggregate form (docs 0.5)")
    status, _, _ = call("GET", f"/rest/api/3/issue/createmeta?projectKeys={project}")
    verdict = "GONE" if status == 404 else "still responding"
    print(f"  GET /issue/createmeta -> {status}  ({verdict})")

    section("6. createmeta: the REPLACEMENT endpoints (docs 0.5)")
    status, types, _ = call("GET", f"/rest/api/3/issue/createmeta/{project}/issuetypes")
    print(f"  GET /issue/createmeta/{project}/issuetypes -> {status}")
    issue_type = None
    if status == 200:
        values = types.get("issueTypes", types.get("values", []))
        for t in values:
            print(f"  {t['id']:8} {t['name']:20} subtask={t.get('subtask')}")
        candidates = [t for t in values if not t.get("subtask")]
        issue_type = candidates[0]["id"] if candidates else None

    if issue_type:
        section(f"7. Field metadata for issue type {issue_type} (drives the dynamic form)")
        status, meta, _ = call(
            "GET", f"/rest/api/3/issue/createmeta/{project}/issuetypes/{issue_type}")
        print(f"  -> {status}")
        if status == 200:
            fields = meta.get("fields", meta.get("values", []))
            if isinstance(fields, dict):
                fields = list(fields.values())
            for f in fields:
                schema = f.get("schema", {})
                custom = schema.get("custom", "")
                kind = custom.split(":")[-1] if custom else schema.get("type")
                allowed = len(f.get("allowedValues", []) or [])
                required = "required" if f.get("required") else "optional"
                key = f.get("fieldId") or f.get("key")
                print(f"  {str(key):26} {required:9} {str(kind):18} allowedValues={allowed}")

    section("8. Rich text format: ADF or not? (docs 0.8)")
    status, found, _ = call(
        "GET", f"/rest/api/3/search/jql?jql=project%3D{project}&maxResults=1&fields=description")
    if status == 200 and found.get("issues"):
        description = found["issues"][0].get("fields", {}).get("description")
        shape = "ADF document" if isinstance(description, dict) else type(description).__name__
        print(f"  first issue's description is: {shape}")
        if isinstance(description, dict):
            print(f"  version={description.get('version')} type={description.get('type')}")
    else:
        print(f"  search -> {status}, no issue available to inspect")

    if not write:
        section("9. WRITE checks skipped")
        print("  pass --write to run them; they create one real issue and print its delete command")
        return 0

    section("9. WRITE: does POST /issue accept `properties` at create time? (docs 0.9)")
    payload = {
        "fields": {
            "project": {"key": project},
            "issuetype": {"id": issue_type},
            "summary": "[jvault] verification probe - safe to delete",
        },
        "properties": [{
            "key": "jvault.origin",
            "value": {"ticketRef": "probe", "correlationId": "probe-1", "channel": "VERIFY"},
        }],
    }
    status, created, _ = call("POST", "/rest/api/3/issue", payload)
    print(f"  POST /issue with properties -> {status}")
    if status != 201:
        print(f"  {json.dumps(created)[:400]}")
        print("  => properties at create time are NOT usable; set them in a follow-up call")
        return 0

    key = created["key"]
    print(f"  created {key}")

    status, prop, _ = call("GET", f"/rest/api/3/issue/{key}/properties/jvault.origin")
    print(f"  reading jvault.origin back -> {status}")
    if status == 200:
        print(f"  {json.dumps(prop.get('value'))}")
        print("  => VERIFIED: create-time properties work; the ambiguity protocol can rely on them")
    else:
        print("  => the create succeeded but the property did not stick")

    section("10. WRITE: is a remote link idempotent on globalId? (docs 0.6)")
    link = {
        "globalId": "jvault:content:probe",
        "object": {"url": "https://example.invalid/c/probe", "title": "jvault probe"},
    }
    for attempt in (1, 2):
        status, _, _ = call("POST", f"/rest/api/3/issue/{key}/remotelink", link)
        print(f"  attempt {attempt} -> {status}")
    status, links, _ = call("GET", f"/rest/api/3/issue/{key}/remotelink")
    if status == 200:
        count = len(links)
        print(f"  remote links on the issue: {count} "
              f"({'upsert confirmed' if count == 1 else 'DUPLICATED - not idempotent'})")

    section("Clean up")
    print(f"  ./scripts/verify_jira.py --delete {key}")
    print(f"  or: curl -u $JIRA_EMAIL:$JIRA_TOKEN -X DELETE {SITE}/rest/api/3/issue/{key}")
    return 0


if __name__ == "__main__":
    if "--delete" in sys.argv:
        target = sys.argv[sys.argv.index("--delete") + 1]
        code, body, _ = call("DELETE", f"/rest/api/3/issue/{target}")
        print(f"DELETE {target} -> {code}")
        sys.exit(0)
    sys.exit(main())
