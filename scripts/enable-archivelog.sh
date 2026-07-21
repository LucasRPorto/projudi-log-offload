#!/usr/bin/env bash
# =============================================================================
# enable-archivelog.sh — coloca o Oracle em ARCHIVELOG, em ambiente já de pé
#
# O Debezium Oracle Connector usa LogMiner, que EXIGE o banco em ARCHIVELOG.
# A imagem gvenzl/oracle-free sobe em NOARCHIVELOG e não interpreta a variável
# ENABLE_ARCHIVELOG (ver docs/decisoes.md, seção 5).
#
# Este script existe para não obrigar um `make reset` — que apagaria os volumes
# e levaria minutos — só para trocar o log_mode. Ele age sobre o container em
# execução, reiniciando apenas a instância Oracle.
#
# É idempotente: se o banco já estiver em ARCHIVELOG, não faz nada.
#
# Uso:  ./scripts/enable-archivelog.sh     (ou `make archivelog`)
# =============================================================================

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

COMPOSE_FILE="infra/docker-compose.yml"

if [ ! -f .env ]; then
    echo "ERRO: .env não encontrado. Rode ./scripts/setup.sh primeiro." >&2
    exit 1
fi
set -a; . ./.env; set +a
ORACLE_SYS_PASSWORD="${ORACLE_SYS_PASSWORD:-oracle_dev}"

if [ -t 1 ]; then
    C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'
    C_RED=$'\033[31m';  C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
else
    C_RESET=""; C_BOLD=""; C_RED=""; C_GREEN=""; C_YELLOW=""
fi
ok()   { printf '  %s✅%s %s\n' "${C_GREEN}"  "${C_RESET}" "$*"; }
bad()  { printf '  %s❌%s %s\n' "${C_RED}"    "${C_RESET}" "$*"; }
warn() { printf '  %s⚠️%s  %s\n' "${C_YELLOW}" "${C_RESET}" "$*"; }

dc() { docker compose --env-file .env -f "${COMPOSE_FILE}" "$@"; }

# sqlplus local como sysdba: STARTUP não funciona através do listener
ora_local() {
    dc exec -T oracle sqlplus -S "/ as sysdba" <<EOF 2>/dev/null
set heading off feedback off pagesize 0 verify off echo off
$1
exit;
EOF
}

log_mode() { ora_local 'select log_mode from v$database;' | tr -d '\r' | tr -d '[:space:]'; }

printf '\n%sARCHIVELOG — pré-requisito do CDC (Solução 2)%s\n\n' "${C_BOLD}" "${C_RESET}"

ESTADO="$(docker inspect --format '{{.State.Status}}' projudi-oracle 2>/dev/null || echo ausente)"
if [ "${ESTADO}" != "running" ]; then
    bad "o container projudi-oracle não está em execução (estado: ${ESTADO})."
    echo "     rode 'make up' antes." >&2
    exit 1
fi

ATUAL="$(log_mode)"
if [ "${ATUAL}" = "ARCHIVELOG" ]; then
    ok "o banco já está em ARCHIVELOG — nada a fazer"
    exit 0
fi

if [ -z "${ATUAL}" ]; then
    bad "não foi possível ler v\$database.log_mode. O Oracle terminou de subir?"
    echo "     tente: make logs s=oracle" >&2
    exit 1
fi

warn "log_mode atual: ${ATUAL}"
echo "     O banco será REINICIADO para trocar o modo. Nenhum dado é perdido:"
echo "     o volume oracle-data permanece intacto."
echo

# ---------------------------------------------------------------------------
# O .sql está montado dentro do container junto com os scripts de init.
# ---------------------------------------------------------------------------
SQL_NO_CONTAINER=/container-entrypoint-initdb.d/sql/05_enable_archivelog.sql

echo "  Reiniciando a instância em modo MOUNT e habilitando ARCHIVELOG..."
if ! dc exec -T oracle sqlplus -S "/ as sysdba" "@${SQL_NO_CONTAINER}" </dev/null; then
    bad "a sequência de ARCHIVELOG falhou"
    echo "     veja o log completo com: make logs s=oracle" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Depois do restart o listener leva alguns segundos para registrar de novo o
# serviço do PDB. Sem esperar, o próximo comando que use //localhost:1521/
# falharia com ORA-12514 e pareceria um erro de configuração.
# ---------------------------------------------------------------------------
echo "  Aguardando o listener registrar o serviço FREEPDB1..."
pdb_responde() {
    dc exec -T oracle sqlplus -S -L \
        "sys/${ORACLE_SYS_PASSWORD}@//localhost:1521/FREEPDB1 as sysdba" <<'EOF' 2>/dev/null \
        | tr -d '\r' | tr -d '[:space:]' | grep -qx 1
set heading off feedback off pagesize 0 echo off
select 1 from dual;
exit;
EOF
}

PDB_OK=0
for _ in $(seq 1 30); do
    if pdb_responde; then PDB_OK=1; break; fi
    sleep 3
done

FINAL="$(log_mode)"
echo

if [ "${FINAL}" = "ARCHIVELOG" ]; then
    ok "log_mode agora é ARCHIVELOG"
else
    bad "log_mode continua '${FINAL}' — o LogMiner não vai funcionar"
    exit 1
fi

if [ "${PDB_OK}" -eq 1 ]; then
    ok "FREEPDB1 aberto e registrado no listener"
else
    warn "o PDB demorou a registrar no listener; confira com 'make validate'"
fi

cat <<EOF

${C_BOLD}Pronto.${C_RESET} Confirme com:

  make validate        item 'f' deve passar sem avisos

Lembrete operacional: com ARCHIVELOG ligado o Oracle acumula archived logs até
encher a FRA. Num laboratório de vida curta não incomoda; se o ambiente ficar
semanas de pé, limpe com RMAN (DELETE ARCHIVELOG ALL) ou aumente o
db_recovery_file_dest_size.

EOF
