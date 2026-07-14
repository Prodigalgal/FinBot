#!/bin/sh
set -eu

legacy_root="${FINBOT_LEGACY_ROOT:-/legacy}"
source_file="${FINBOT_LEGACY_SQLITE_PATH:-${legacy_root}/data/finbot.sqlite3}"
import_jar="${FINBOT_LEGACY_IMPORT_JAR:-/app/finbot-migration.jar}"
completion_marker="${legacy_root}/.finbot-postgresql-migration-completed"

list_legacy_files() {
  find "$legacy_root" -type f \( \
    -name '*.sqlite' -o -name '*.sqlite-*' -o \
    -name '*.sqlite3' -o -name '*.sqlite3-*' -o \
    -name '*.db' -o -name '*.db-*' \
  \) -print
  if [ -d "${legacy_root}/backups" ]; then
    find "${legacy_root}/backups" -maxdepth 1 -type f -name 'finbot*.tar.gz' -print
  fi
}

if [ -s "$source_file" ]; then
  java --enable-native-access=ALL-UNNAMED -jar "$import_jar" --source "$source_file"
  date -u '+%Y-%m-%dT%H:%M:%SZ' > "$completion_marker"
  sync
elif [ ! -f "$completion_marker" ] && [ -n "$(list_legacy_files | head -n 1)" ]; then
  echo "Legacy files exist without a completed PostgreSQL migration marker" >&2
  exit 1
fi

if [ -f "$completion_marker" ]; then
  find "$legacy_root" -type f \( \
    -name '*.sqlite' -o -name '*.sqlite-*' -o \
    -name '*.sqlite3' -o -name '*.sqlite3-*' -o \
    -name '*.db' -o -name '*.db-*' \
  \) -print -delete
  if [ -d "${legacy_root}/backups" ]; then
    find "${legacy_root}/backups" -maxdepth 1 -type f -name 'finbot*.tar.gz' -print -delete
    find "${legacy_root}/backups" -depth -type d -empty -delete
  fi
fi

if [ -n "$(list_legacy_files | head -n 1)" ]; then
  echo "Legacy SQLite cleanup did not remove every matching file" >&2
  exit 1
fi

echo "Legacy SQLite data cleanup completed"
