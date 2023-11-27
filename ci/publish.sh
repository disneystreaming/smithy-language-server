#!/usr/bin/env bash

set -eu

gpg --version

echo "$PGP_SECRET" | base64 --decode > gpg_key

gpg --import  --no-tty --batch --yes gpg_key
gpg --list-keys

rm gpg_key

mill lsp.publish \
      --sonatypeCreds "$SONATYPE_USERNAME:$SONATYPE_PASSWORD" \
      --signed true \
      --release true \
      --readTimeout 600000 \
      --awaitTimeout 600000 \
      --gpgArgs --passphrase="$PGP_PASSPHRASE" \
      --gpgArgs --no-tty \
      --gpgArgs --pinentry-mode \
      --gpgArgs loopback \
      --gpgArgs --batch \
      --gpgArgs --yes \
      --gpgArgs -a \
      --gpgArgs -b
