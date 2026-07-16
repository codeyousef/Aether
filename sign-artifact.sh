#!/bin/bash
set -euo pipefail

PASSPHRASE="${AETHER_SIGNING_PASSPHRASE:?AETHER_SIGNING_PASSPHRASE is required}"
KEY_FILE="$1"
OUTPUT_FILE="$2"
INPUT_FILE="$3"

GPG_BIN="${GPG_BIN:-$(command -v gpg)}"

if [[ -z "$GPG_BIN" ]]; then
  echo "gpg not found in PATH" >&2
  exit 1
fi

if [[ ! -f "$KEY_FILE" ]]; then
  echo "Key file not found: $KEY_FILE" >&2
  exit 1
fi

GNUPGHOME_DIR="${GNUPGHOME:-}"
if [[ -z "$GNUPGHOME_DIR" ]]; then
  GNUPGHOME_DIR="$(mktemp -d)"
  trap 'rm -rf "$GNUPGHOME_DIR"' EXIT
fi
export GNUPGHOME="$GNUPGHOME_DIR"

# Import the private key
"$GPG_BIN" --batch --yes --import "$KEY_FILE"

# Extract the first secret key fingerprint
KEY_ID=$("$GPG_BIN" --batch --with-colons --list-secret-keys | awk -F: '$1=="sec"{print $5; exit}')

if [[ -z "$KEY_ID" ]]; then
  echo "Unable to determine secret key ID after import." >&2
  exit 1
fi

# Sign the artifact
printf '%s\n' "$PASSPHRASE" | "$GPG_BIN" --batch --yes --pinentry-mode loopback \
  --passphrase-fd 0 \
  --default-key "$KEY_ID" \
  --local-user "$KEY_ID" \
  --armor --detach-sign \
  --output "$OUTPUT_FILE" \
  "$INPUT_FILE"

chmod 600 "$OUTPUT_FILE"
echo "   ✔ Signed $(basename "$INPUT_FILE") with key $KEY_ID"
