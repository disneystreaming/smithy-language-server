#!/usr/bin/env bash

set -eu

echo $PGP_SECRET | base64 --decode > gpg_key

gpg --import  --no-tty --batch --yes gpg_key

rm gpg_key

mill lsp.publish \
      --credentials $SONATYPE_USERNAME:$SONATYPE_PASSWORD \
      --gpgArgs --passphrase=$PGP_PASSPHRASE,--no-tty,--pinentry-mode,loopback,--batch,--yes,-a,-b \
      --release true
      --signed true
