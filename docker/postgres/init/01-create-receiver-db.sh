#!/bin/bash
set -euo pipefail
psql -v ON_ERROR_STOP=1 -v pw="$RECEIVER_DB_PASSWORD" -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<-'EOSQL'
	CREATE ROLE receiver LOGIN PASSWORD :'pw';
	CREATE DATABASE receiver OWNER receiver;
EOSQL
