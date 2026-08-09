#!/usr/bin/env python3
"""
supergrok_usage.py — SuperGrok / SuperGrok Heavy weekly utilization

Fetches the same billing snapshot the official Grok CLI uses:

    GET https://cli-chat-proxy.grok.com/v1/billing?format=credits
    Authorization: Bearer <OIDC access token from ~/.grok/auth.json>

Usage
-----
    # one-shot pretty report (uses token from `grok login`)
    python3 supergrok_usage.py

    # raw JSON from the billing endpoint (+ auth metadata)
    python3 supergrok_usage.py --json

    # refresh every 30 seconds
    python3 supergrok_usage.py --watch

    # override token (testing only; never commit tokens)
    python3 supergrok_usage.py --token "$ACCESS_TOKEN"

Prerequisites
-------------
    1. Install & sign in to the Grok CLI:  grok login
    2. Optional pretty UI:  pip install rich
    3. Python 3.10+

Notes
-----
    * This is **consumer SuperGrok** weekly usage (unified pool for Build / Chat / etc.).
      It is NOT the developer API prepaid balance at console.x.ai.
    * Tokens live in ~/.grok/auth.json (owner-only). This script never hardcodes credentials.
    * If auth fails or the token is expired, run:  grok login
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

DEFAULT_AUTH_PATH = Path.home() / ".grok" / "auth.json"
BILLING_URL = "https://cli-chat-proxy.grok.com/v1/billing?format=credits"
USER_AGENT = "supergrok-usage/1.0 (+https://github.com/xai-org/grok-cli)"
REQUEST_TIMEOUT_SEC = 30
WATCH_INTERVAL_SEC = 30

# Best-effort mapping of JWT integer `tier` → product name.
# Observed live: SuperGrok Heavy accounts carry tier=5.
# Lower tiers are not fully documented by xAI; unknown values print as "Tier N".
JWT_TIER_LABELS: dict[int, str] = {
    0: "Free",
    1: "Free / entry",
    2: "SuperGrok Lite",
    3: "SuperGrok",
    4: "X Premium+",
    5: "SuperGrok Heavy",
}

# Friendly labels for productUsage[].product codes from the billing API.
PRODUCT_LABELS: dict[str, str] = {
    "GrokBuild": "Build (Grok CLI)",
    "GrokChat": "Chat (grok.com / apps)",
    "GrokImagine": "Imagine (image/video)",
    "GrokVoice": "Voice",
    "GrokAPI": "API (consumer path)",
    "Imagine": "Imagine",
    "Voice": "Voice",
    "Chat": "Chat",
    "Build": "Build",
    "API": "API",
}


# ---------------------------------------------------------------------------
# Optional rich console
# ---------------------------------------------------------------------------

try:
    from rich.console import Console
    from rich.panel import Panel
    from rich.progress import BarColumn, Progress, TextColumn
    from rich.table import Table
    from rich.text import Text

    RICH = True
    console = Console()
except ImportError:  # pragma: no cover
    RICH = False
    console = None  # type: ignore


# ---------------------------------------------------------------------------
# Data model
# ---------------------------------------------------------------------------


@dataclass
class AuthContext:
    access_token: str
    source: str  # path or "--token"
    email: Optional[str] = None
    user_id: Optional[str] = None
    team_id: Optional[str] = None
    expires_at: Optional[str] = None
    auth_mode: Optional[str] = None
    jwt_claims: dict[str, Any] = field(default_factory=dict)
    jwt_tier: Optional[int] = None
    plan_from_jwt: Optional[str] = None


@dataclass
class BillingSnapshot:
    raw: dict[str, Any]
    config: dict[str, Any]
    credit_usage_percent: Optional[float]
    period_type: Optional[str]
    period_start: Optional[str]
    period_end: Optional[str]
    product_usage: list[dict[str, Any]]
    on_demand_used: Optional[float]
    on_demand_cap: Optional[float]
    prepaid_balance: Optional[float]
    is_unified: Optional[bool]
    top_up_method: Optional[str]
    plan_from_billing: Optional[str]


# ---------------------------------------------------------------------------
# Auth helpers
# ---------------------------------------------------------------------------


def _b64url_json(segment: str) -> dict[str, Any]:
    pad = "=" * (-len(segment) % 4)
    raw = base64.urlsafe_b64decode(segment + pad)
    data = json.loads(raw.decode("utf-8"))
    if not isinstance(data, dict):
        return {}
    return data


def decode_jwt_claims(token: str) -> dict[str, Any]:
    """Decode JWT payload without verification (display only)."""
    parts = token.split(".")
    if len(parts) < 2:
        return {}
    try:
        return _b64url_json(parts[1])
    except Exception:
        return {}


def _walk_plan_hints(obj: Any, found: list[str], depth: int = 0) -> None:
    """Collect plan-ish strings from nested auth / claim structures."""
    if depth > 6:
        return
    if isinstance(obj, dict):
        for k, v in obj.items():
            kl = str(k).lower()
            if any(
                h in kl
                for h in (
                    "plan",
                    "tier",
                    "subscription",
                    "product",
                    "sku",
                    "entitlement",
                    "package",
                )
            ):
                if isinstance(v, (str, int, float)):
                    found.append(f"{k}={v}")
                elif isinstance(v, dict):
                    for sk in ("name", "display", "display_name", "label", "id", "type"):
                        if sk in v and isinstance(v[sk], (str, int)):
                            found.append(f"{k}.{sk}={v[sk]}")
            _walk_plan_hints(v, found, depth + 1)
    elif isinstance(obj, list):
        for item in obj[:40]:
            _walk_plan_hints(item, found, depth + 1)


def infer_plan_from_jwt(claims: dict[str, Any]) -> tuple[Optional[int], Optional[str]]:
    """Return (tier_int, human_label) from JWT claims."""
    tier_raw = claims.get("tier")
    tier_int: Optional[int] = None
    if isinstance(tier_raw, int):
        tier_int = tier_raw
    elif isinstance(tier_raw, str) and tier_raw.isdigit():
        tier_int = int(tier_raw)

    # Explicit string fields (rare, but check)
    for key in (
        "plan",
        "subscription",
        "subscription_tier",
        "subscription_tier_display",
        "product",
        "tier_name",
        "plan_name",
    ):
        val = claims.get(key)
        if isinstance(val, str) and val.strip():
            label = normalize_plan_name(val)
            return tier_int, label

    if tier_int is not None:
        label = JWT_TIER_LABELS.get(tier_int)
        if label:
            return tier_int, label
        return tier_int, f"Tier {tier_int}"

    # Scan nested claim values for SuperGrok strings
    blob = json.dumps(claims).lower()
    if "super" in blob and "heavy" in blob:
        return tier_int, "SuperGrok Heavy"
    if "supergrok_lite" in blob or "super grok lite" in blob:
        return tier_int, "SuperGrok Lite"
    if "supergrok" in blob or "super_grok" in blob:
        return tier_int, "SuperGrok"

    return tier_int, None


def normalize_plan_name(raw: str) -> str:
    s = raw.strip()
    key = s.lower().replace(" ", "_").replace("-", "_")
    mapping = {
        "supergrok_heavy": "SuperGrok Heavy",
        "super_grok_heavy": "SuperGrok Heavy",
        "superheavy": "SuperGrok Heavy",
        "super_heavy": "SuperGrok Heavy",
        "heavy": "SuperGrok Heavy",
        "supergrok_lite": "SuperGrok Lite",
        "super_grok_lite": "SuperGrok Lite",
        "lite": "SuperGrok Lite",
        "supergrok": "SuperGrok",
        "super_grok": "SuperGrok",
        "supergrok_plus": "SuperGrok",
        "x_premium_plus": "X Premium+",
        "x_premium": "X Premium",
        "premium_plus": "X Premium+",
        "free": "Free",
    }
    if key in mapping:
        return mapping[key]
    # Title-case multi-word SuperGrok variants
    if "heavy" in key and "super" in key:
        return "SuperGrok Heavy"
    if "lite" in key and "super" in key:
        return "SuperGrok Lite"
    if "supergrok" in key or "super_grok" in key:
        return "SuperGrok"
    return s


def load_auth_from_file(path: Path) -> AuthContext:
    if not path.is_file():
        raise FileNotFoundError(
            f"Auth file not found: {path}\n"
            "Run `grok login` to sign in with the official Grok CLI, then retry."
        )

    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"Could not parse {path}: {exc}") from exc

    if not isinstance(data, dict) or not data:
        raise ValueError(f"{path} is empty or not a JSON object. Run `grok login`.")

    # Shape A (current CLI): { "<issuer>::<client_id>": { "key": "<jwt>", ... }, ... }
    # Shape B (legacy / docs): { "https://accounts.x.ai/sign-in": { "key": "..." } }
    # Shape C: flat { "access_token": "...", "key": "..." }
    entry: Optional[dict[str, Any]] = None
    token: Optional[str] = None

    if "key" in data and isinstance(data["key"], str):
        entry = data
        token = data["key"]
    elif "access_token" in data and isinstance(data["access_token"], str):
        entry = data
        token = data["access_token"]
    else:
        # Prefer the entry with the latest expires_at / create_time
        candidates: list[tuple[str, dict[str, Any]]] = []
        for k, v in data.items():
            if isinstance(v, dict) and isinstance(v.get("key"), str) and v["key"].strip():
                candidates.append((k, v))
        if not candidates:
            raise ValueError(
                f"No access token found in {path}.\n"
                "Expected a `key` field (from `grok login`). Run `grok login` and retry."
            )
        candidates.sort(
            key=lambda kv: str(kv[1].get("expires_at") or kv[1].get("create_time") or ""),
            reverse=True,
        )
        _, entry = candidates[0]
        token = entry["key"]

    assert token is not None and entry is not None
    token = token.strip()
    if not token:
        raise ValueError(f"Empty token in {path}. Run `grok login`.")

    claims = decode_jwt_claims(token)
    tier, plan = infer_plan_from_jwt(claims)

    # Also scan the whole auth entry for plan hints
    if not plan:
        hints: list[str] = []
        _walk_plan_hints(entry, hints)
        for h in hints:
            low = h.lower()
            if "heavy" in low:
                plan = "SuperGrok Heavy"
                break
            if "lite" in low and "super" in low:
                plan = "SuperGrok Lite"
                break
            if "supergrok" in low or "super_grok" in low:
                plan = "SuperGrok"
                break

    return AuthContext(
        access_token=token,
        source=str(path),
        email=entry.get("email") if isinstance(entry.get("email"), str) else None,
        user_id=entry.get("user_id") if isinstance(entry.get("user_id"), str) else None,
        team_id=entry.get("team_id") if isinstance(entry.get("team_id"), str) else claims.get("team_id"),
        expires_at=entry.get("expires_at") if isinstance(entry.get("expires_at"), str) else None,
        auth_mode=entry.get("auth_mode") if isinstance(entry.get("auth_mode"), str) else None,
        jwt_claims=claims,
        jwt_tier=tier,
        plan_from_jwt=plan,
    )


def load_auth(token_arg: Optional[str], auth_path: Path) -> AuthContext:
    if token_arg:
        token = token_arg.strip()
        if not token:
            raise ValueError("--token was empty")
        claims = decode_jwt_claims(token)
        tier, plan = infer_plan_from_jwt(claims)
        return AuthContext(
            access_token=token,
            source="--token",
            team_id=str(claims["team_id"]) if claims.get("team_id") else None,
            jwt_claims=claims,
            jwt_tier=tier,
            plan_from_jwt=plan,
        )
    return load_auth_from_file(auth_path)


def token_looks_expired(auth: AuthContext) -> bool:
    """True if auth.json expires_at or JWT exp is in the past."""
    now = datetime.now(timezone.utc)

    if auth.expires_at:
        try:
            exp = datetime.fromisoformat(auth.expires_at.replace("Z", "+00:00"))
            if exp.tzinfo is None:
                exp = exp.replace(tzinfo=timezone.utc)
            if exp <= now:
                return True
        except ValueError:
            pass

    exp_claim = auth.jwt_claims.get("exp")
    if isinstance(exp_claim, (int, float)):
        if datetime.fromtimestamp(exp_claim, tz=timezone.utc) <= now:
            return True

    return False


# ---------------------------------------------------------------------------
# Billing fetch
# ---------------------------------------------------------------------------


def cents_or_units_to_float(val: Any) -> Optional[float]:
    """Normalize {\"val\": \"123\"} or bare numbers to float."""
    if val is None:
        return None
    if isinstance(val, dict):
        inner = val.get("val", val.get("value"))
        return cents_or_units_to_float(inner)
    if isinstance(val, (int, float)):
        return float(val)
    if isinstance(val, str):
        try:
            return float(val)
        except ValueError:
            return None
    return None


def infer_plan_from_billing(config: dict[str, Any]) -> Optional[str]:
    for key in (
        "subscriptionTier",
        "subscription_tier",
        "subscriptionTierDisplay",
        "subscription_tier_display",
        "plan",
        "planName",
        "plan_name",
        "tier",
        "tierName",
        "product",
        "billingType",
        "billing_type",
    ):
        v = config.get(key)
        if isinstance(v, str) and v.strip():
            return normalize_plan_name(v)
        if isinstance(v, dict):
            for sk in ("name", "display", "displayName", "label", "id"):
                if isinstance(v.get(sk), str) and v[sk].strip():
                    return normalize_plan_name(v[sk])
    # Nested search
    blob = json.dumps(config).lower()
    if "supergrok_heavy" in blob or "super heavy" in blob or "super_heavy" in blob:
        return "SuperGrok Heavy"
    if "supergrok_lite" in blob:
        return "SuperGrok Lite"
    if "supergrok" in blob:
        return "SuperGrok"
    return None


def parse_billing_payload(payload: dict[str, Any]) -> BillingSnapshot:
    config = payload.get("config")
    if not isinstance(config, dict):
        # Some gateways may return the config fields at the top level
        config = payload if any(k in payload for k in ("creditUsagePercent", "currentPeriod")) else {}

    period = config.get("currentPeriod") if isinstance(config.get("currentPeriod"), dict) else {}
    period_start = (
        period.get("start")
        or config.get("billingPeriodStart")
        or config.get("billing_period_start")
    )
    period_end = (
        period.get("end")
        or config.get("billingPeriodEnd")
        or config.get("billing_period_end")
    )
    period_type = period.get("type") or config.get("periodType")

    products = config.get("productUsage") or config.get("product_usage") or []
    if not isinstance(products, list):
        products = []

    return BillingSnapshot(
        raw=payload,
        config=config if isinstance(config, dict) else {},
        credit_usage_percent=cents_or_units_to_float(config.get("creditUsagePercent")),
        period_type=str(period_type) if period_type is not None else None,
        period_start=str(period_start) if period_start else None,
        period_end=str(period_end) if period_end else None,
        product_usage=[p for p in products if isinstance(p, dict)],
        on_demand_used=cents_or_units_to_float(config.get("onDemandUsed")),
        on_demand_cap=cents_or_units_to_float(config.get("onDemandCap")),
        prepaid_balance=cents_or_units_to_float(config.get("prepaidBalance")),
        is_unified=config.get("isUnifiedBillingUser")
        if isinstance(config.get("isUnifiedBillingUser"), bool)
        else config.get("is_unified_billing_user")
        if isinstance(config.get("is_unified_billing_user"), bool)
        else None,
        top_up_method=str(config["topUpMethod"])
        if isinstance(config.get("topUpMethod"), str)
        else None,
        plan_from_billing=infer_plan_from_billing(config if isinstance(config, dict) else {}),
    )


def fetch_billing(token: str, url: str = BILLING_URL) -> BillingSnapshot:
    req = urllib.request.Request(
        url,
        method="GET",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
            "User-Agent": USER_AGENT,
            # Same client identity family as the official CLI (helps some edge proxies).
            "x-grok-client-identifier": "grok-shell",
            "x-grok-client-mode": "billing",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT_SEC) as resp:
            body = resp.read().decode("utf-8")
            status = getattr(resp, "status", 200)
    except urllib.error.HTTPError as exc:
        err_body = ""
        try:
            err_body = exc.read().decode("utf-8", errors="replace")
        except Exception:
            pass
        if exc.code in (401, 403):
            raise PermissionError(
                f"HTTP {exc.code}: authentication failed for billing endpoint.\n"
                "Your session may be expired or missing grok.com scope.\n"
                "Run:  grok login\n"
                f"Details: {err_body[:400] or exc.reason}"
            ) from exc
        if exc.code == 402:
            raise RuntimeError(
                f"HTTP 402: billing blocked / payment required.\n"
                f"Details: {err_body[:500] or exc.reason}"
            ) from exc
        if exc.code == 429:
            raise RuntimeError(
                f"HTTP 429: rate limited by cli-chat-proxy.\n"
                f"Details: {err_body[:400] or exc.reason}"
            ) from exc
        raise RuntimeError(
            f"HTTP {exc.code} from billing endpoint: {err_body[:500] or exc.reason}"
        ) from exc
    except urllib.error.URLError as exc:
        raise ConnectionError(
            f"Network error reaching {url}: {exc.reason}\n"
            "Check connectivity / DNS / VPN."
        ) from exc

    if not body.strip():
        raise RuntimeError("Billing endpoint returned an empty body.")

    try:
        payload = json.loads(body)
    except json.JSONDecodeError as exc:
        raise RuntimeError(
            f"Billing endpoint returned non-JSON (HTTP {status}): {body[:300]}"
        ) from exc

    if not isinstance(payload, dict):
        raise RuntimeError(f"Unexpected billing payload type: {type(payload).__name__}")

    return parse_billing_payload(payload)


# ---------------------------------------------------------------------------
# Display helpers
# ---------------------------------------------------------------------------


def resolve_plan(auth: AuthContext, snap: BillingSnapshot) -> tuple[str, str]:
    """Return (plan_label, source_note)."""
    if snap.plan_from_billing:
        return snap.plan_from_billing, "billing endpoint"
    if auth.plan_from_jwt:
        src = "JWT claim"
        if auth.jwt_tier is not None:
            src = f"JWT tier={auth.jwt_tier}"
        return auth.plan_from_jwt, src
    if auth.jwt_tier is not None:
        return f"Tier {auth.jwt_tier} (unmapped)", f"JWT tier={auth.jwt_tier}"
    return "Unknown (not in token or billing payload)", "n/a"


def format_ts(iso: Optional[str]) -> str:
    if not iso:
        return "—"
    try:
        dt = datetime.fromisoformat(iso.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        local = dt.astimezone()
        return f"{dt.strftime('%Y-%m-%d %H:%M:%S %Z')}  (local {local.strftime('%Y-%m-%d %H:%M:%S %Z')})"
    except ValueError:
        return iso


def period_type_label(raw: Optional[str]) -> str:
    if not raw:
        return "—"
    r = raw.upper()
    if "WEEK" in r:
        return "Weekly (unified pool)"
    if "MONTH" in r:
        return "Monthly"
    if "DAY" in r:
        return "Daily"
    return raw


def money_display(val: Optional[float], *, assume_cents: bool = True) -> str:
    """onDemand / prepaid `val` fields are typically USD cents on xAI billing APIs."""
    if val is None:
        return "—"
    amount = val / 100.0 if assume_cents else val
    # If values look like already-dollars (fractional with small abs), still ok as cents/100.
    return f"${amount:,.2f}"


def usage_bar(percent: Optional[float], width: int = 28) -> str:
    if percent is None:
        return "n/a"
    p = max(0.0, min(100.0, float(percent)))
    filled = int(round((p / 100.0) * width))
    return f"[{'█' * filled}{'░' * (width - filled)}] {p:.2f}%"


def product_label(code: str) -> str:
    return PRODUCT_LABELS.get(code, code)


def emit(msg: str = "", *, err: bool = False) -> None:
    stream = sys.stderr if err else sys.stdout
    if RICH and console is not None and not err:
        console.print(msg)
    else:
        print(msg, file=stream)


def print_report(auth: AuthContext, snap: BillingSnapshot) -> None:
    plan, plan_src = resolve_plan(auth, snap)
    usage = snap.credit_usage_percent
    remaining = None if usage is None else max(0.0, 100.0 - float(usage))

    if RICH and console is not None:
        # Plan banner — most important
        plan_style = "bold white on dark_green"
        if "heavy" in plan.lower():
            plan_style = "bold white on purple"
        elif "unknown" in plan.lower():
            plan_style = "bold white on dark_orange"
        console.print()
        console.print(
            Panel.fit(
                Text(f"  PLAN: {plan}  ", style=plan_style),
                subtitle=f"source: {plan_src}",
                border_style="bright_magenta",
            )
        )

        meta = Table(show_header=False, box=None, padding=(0, 2))
        meta.add_column("k", style="dim")
        meta.add_column("v")
        if auth.email:
            meta.add_row("Account", auth.email)
        if auth.team_id:
            meta.add_row("Team ID", str(auth.team_id))
        if auth.jwt_tier is not None:
            meta.add_row("JWT tier", str(auth.jwt_tier))
        if auth.expires_at:
            meta.add_row("Token expires", auth.expires_at)
        meta.add_row("Auth source", auth.source)
        meta.add_row("Endpoint", BILLING_URL)
        console.print(meta)
        console.print()

        # Weekly usage
        console.print("[bold]Weekly utilization[/bold] (consumer SuperGrok pool)")
        if usage is not None:
            color = "green" if usage < 70 else "yellow" if usage < 90 else "red"
            console.print(f"  Used:      [{color}]{usage:.2f}%[/{color}]")
            console.print(f"  Remaining: [{color}]{remaining:.2f}%[/{color}]")
            console.print(f"  {usage_bar(usage)}")
        else:
            console.print("  [dim]creditUsagePercent not present in response[/dim]")

        console.print()
        console.print("[bold]Current period[/bold]")
        console.print(f"  Type:  {period_type_label(snap.period_type)}")
        console.print(f"  Start: {format_ts(snap.period_start)}")
        console.print(f"  End:   {format_ts(snap.period_end)}  ← weekly reset")
        console.print(
            f"  Unified billing: "
            f"{'yes (shared weekly pool)' if snap.is_unified else 'no / unknown' if snap.is_unified is False else '—'}"
        )

        if snap.product_usage:
            console.print()
            console.print("[bold]Product breakdown[/bold]")
            tbl = Table(show_header=True, header_style="bold")
            tbl.add_column("Product")
            tbl.add_column("Usage %", justify="right")
            tbl.add_column("Bar")
            for p in snap.product_usage:
                code = str(p.get("product") or p.get("name") or "?")
                pct = cents_or_units_to_float(p.get("usagePercent") if "usagePercent" in p else p.get("usage_percent"))
                pct_s = f"{pct:.2f}%" if pct is not None else "—"
                tbl.add_row(product_label(code), pct_s, usage_bar(pct) if pct is not None else "—")
            console.print(tbl)

        console.print()
        console.print("[bold]On-demand / extra usage credits[/bold]")
        console.print(f"  Used:  {money_display(snap.on_demand_used)}")
        console.print(f"  Cap:   {money_display(snap.on_demand_cap)}")
        console.print(f"  Top-up method: {snap.top_up_method or '—'}")

        console.print()
        console.print("[bold]Prepaid balance[/bold] (consumer billing envelope)")
        console.print(f"  {money_display(snap.prepaid_balance)}")
        console.print(
            "[dim]  Note: this is not the developer API balance at console.x.ai.[/dim]"
        )
        console.print()
        return

    # ---------- plain text fallback ----------
    lines = [
        "",
        "=" * 60,
        f"  PLAN: {plan}",
        f"        (source: {plan_src})",
        "=" * 60,
    ]
    if auth.email:
        lines.append(f"  Account:     {auth.email}")
    if auth.team_id:
        lines.append(f"  Team ID:     {auth.team_id}")
    if auth.jwt_tier is not None:
        lines.append(f"  JWT tier:    {auth.jwt_tier}")
    if auth.expires_at:
        lines.append(f"  Token exp:   {auth.expires_at}")
    lines.append(f"  Auth source: {auth.source}")
    lines.append(f"  Endpoint:    {BILLING_URL}")
    lines.append("")
    lines.append("Weekly utilization (consumer SuperGrok pool)")
    if usage is not None:
        lines.append(f"  Used:      {usage:.2f}%")
        lines.append(f"  Remaining: {remaining:.2f}%")
        lines.append(f"  {usage_bar(usage)}")
    else:
        lines.append("  creditUsagePercent: n/a")
    lines.append("")
    lines.append("Current period")
    lines.append(f"  Type:  {period_type_label(snap.period_type)}")
    lines.append(f"  Start: {format_ts(snap.period_start)}")
    lines.append(f"  End:   {format_ts(snap.period_end)}  ← weekly reset")
    if snap.is_unified is True:
        lines.append("  Unified billing: yes (shared weekly pool)")
    elif snap.is_unified is False:
        lines.append("  Unified billing: no")
    else:
        lines.append("  Unified billing: —")
    if snap.product_usage:
        lines.append("")
        lines.append("Product breakdown")
        for p in snap.product_usage:
            code = str(p.get("product") or p.get("name") or "?")
            pct = cents_or_units_to_float(
                p.get("usagePercent") if "usagePercent" in p else p.get("usage_percent")
            )
            pct_s = f"{pct:.2f}%" if pct is not None else "—"
            bar = usage_bar(pct) if pct is not None else ""
            lines.append(f"  • {product_label(code):28s} {pct_s:>8s}  {bar}")
    lines.append("")
    lines.append("On-demand / extra usage credits")
    lines.append(f"  Used: {money_display(snap.on_demand_used)}")
    lines.append(f"  Cap:  {money_display(snap.on_demand_cap)}")
    lines.append(f"  Top-up method: {snap.top_up_method or '—'}")
    lines.append("")
    lines.append("Prepaid balance (consumer billing envelope)")
    lines.append(f"  {money_display(snap.prepaid_balance)}")
    lines.append("  Note: not the developer API balance at console.x.ai.")
    lines.append("")
    print("\n".join(lines))


def print_json(auth: AuthContext, snap: BillingSnapshot) -> None:
    plan, plan_src = resolve_plan(auth, snap)
    out = {
        "plan": {
            "label": plan,
            "source": plan_src,
            "jwt_tier": auth.jwt_tier,
            "jwt_plan": auth.plan_from_jwt,
            "billing_plan": snap.plan_from_billing,
        },
        "auth": {
            "source": auth.source,
            "email": auth.email,
            "user_id": auth.user_id,
            "team_id": auth.team_id,
            "expires_at": auth.expires_at,
            "auth_mode": auth.auth_mode,
            # claims without dumping huge/raw token
            "jwt_claims": {
                k: v
                for k, v in auth.jwt_claims.items()
                if k not in ("sub",)  # still include tier/team; omit nothing critical
            },
        },
        "billing": snap.raw,
        "parsed": {
            "creditUsagePercent": snap.credit_usage_percent,
            "periodType": snap.period_type,
            "periodStart": snap.period_start,
            "periodEnd": snap.period_end,
            "productUsage": snap.product_usage,
            "onDemandUsed": snap.on_demand_used,
            "onDemandCap": snap.on_demand_cap,
            "prepaidBalance": snap.prepaid_balance,
            "isUnifiedBillingUser": snap.is_unified,
            "topUpMethod": snap.top_up_method,
        },
        "endpoint": BILLING_URL,
    }
    print(json.dumps(out, indent=2, sort_keys=False))


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Show SuperGrok / SuperGrok Heavy weekly usage via Grok CLI billing API.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "Examples:\n"
            "  python3 supergrok_usage.py\n"
            "  python3 supergrok_usage.py --json\n"
            "  python3 supergrok_usage.py --watch\n"
            "  python3 supergrok_usage.py --auth ~/.grok/auth.json\n"
        ),
    )
    p.add_argument(
        "--auth",
        type=Path,
        default=Path(os.environ.get("GROK_AUTH_PATH", DEFAULT_AUTH_PATH)),
        help=f"Path to auth.json (default: {DEFAULT_AUTH_PATH})",
    )
    p.add_argument(
        "--token",
        type=str,
        default=None,
        help="Bearer access token override (testing). Prefer auth.json.",
    )
    p.add_argument(
        "--json",
        action="store_true",
        help="Print raw/structured JSON instead of a human report.",
    )
    p.add_argument(
        "--watch",
        action="store_true",
        help=f"Refresh every {WATCH_INTERVAL_SEC}s until Ctrl-C.",
    )
    p.add_argument(
        "--url",
        type=str,
        default=BILLING_URL,
        help="Override billing URL (default: official CLI endpoint).",
    )
    return p


def run_once(args: argparse.Namespace) -> int:
    try:
        auth = load_auth(args.token, args.auth)
    except FileNotFoundError as exc:
        emit(str(exc), err=True)
        return 2
    except ValueError as exc:
        emit(str(exc), err=True)
        emit("Run:  grok login", err=True)
        return 2

    if token_looks_expired(auth) and not args.token:
        emit(
            "Warning: access token appears expired (expires_at / JWT exp in the past).\n"
            "If the request fails, run:  grok login",
            err=True,
        )

    try:
        snap = fetch_billing(auth.access_token, url=args.url)
    except PermissionError as exc:
        emit(str(exc), err=True)
        return 3
    except ConnectionError as exc:
        emit(str(exc), err=True)
        return 4
    except RuntimeError as exc:
        emit(str(exc), err=True)
        return 5

    if args.json:
        print_json(auth, snap)
    else:
        print_report(auth, snap)
    return 0


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.watch:
        try:
            while True:
                if RICH and console is not None and not args.json:
                    console.clear()
                else:
                    # Minimal separator for plain mode
                    print("\n" + "─" * 60)
                    print(datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S %Z"))
                code = run_once(args)
                if code not in (0,):
                    # Keep watching on transient network errors; stop on auth hard-fail
                    if code in (2, 3):
                        return code
                time.sleep(WATCH_INTERVAL_SEC)
        except KeyboardInterrupt:
            emit("\nStopped.", err=True)
            return 0
    return run_once(args)


if __name__ == "__main__":
    sys.exit(main())
