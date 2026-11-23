#!/bin/sh
# Creates .env for docker compose from .env.example, with a strong random POSTGRES_PASSWORD.
# Never overwrites an existing .env. The new file is readable only by its owner.
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
target="$root/.env"

if [ -e "$target" ]; then
  echo ".env already exists; leaving it unchanged." >&2
  exit 0
fi

if command -v openssl > /dev/null 2>&1; then
  password=$(openssl rand -hex 24)
else
  password=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
fi

umask 077
sed "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=${password}/" "$root/.env.example" > "$target"
echo "Created .env with a random 48-character POSTGRES_PASSWORD."
