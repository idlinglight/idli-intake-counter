#!/usr/bin/env bash
# Mints the bcrypt hash for the app's single login password (ADR-0005),
# suitable for the idli-auth Secret's password-hash key
# (consumed as IDLI_AUTH_PASSWORD_HASH; see deploy/chart values.yaml).
# Reads the password without echo. Requires htpasswd (preinstalled on macOS).
set -euo pipefail

read -r -s -p "Password: " password
echo >&2
htpasswd -nbBC 12 "" "$password" | cut -d: -f2
