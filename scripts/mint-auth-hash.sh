#!/usr/bin/env bash
# Mints the bcrypt hash for the app's single login password (ADR-0005),
# suitable for the idli-auth Secret's password-hash key
# (consumed as IDLI_AUTH_PASSWORD_HASH; see deploy/chart values.yaml).
# Reads the password without echo. Requires htpasswd (preinstalled on macOS).
set -euo pipefail

read -r -s -p "Password: " password
echo >&2
read -r -s -p "Repeat:   " confirm
echo >&2
# A typo here mints a hash for a password nobody knows, and the only symptom
# is a permanent "wrong password" after the next deploy.
if [ "$password" != "$confirm" ]; then
	echo "Passwords do not match." >&2
	exit 1
fi

# -i reads the password from stdin; -b would pass it as an argument, putting
# it in this process's argv where any local user can read it off ps.
printf '%s\n' "$password" | htpasswd -niBC 12 "" | cut -d: -f2
