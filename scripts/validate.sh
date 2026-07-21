#!/usr/bin/env bash
# =============================================================================
# validate.sh — bateria de validação do ambiente
#
# Roda depois de `make up`. Confere, em ordem:
#   a) ClickHouse responde; os dois bancos e as tabelas esperadas existem
#   b) Oracle responde; as 3 tabelas do PROJUDI existem e têm dados de exemplo
#   c) Kafka responde e lista tópicos
#   d) Kafka Connect responde e o plugin Oracle do Debezium está disponível
#   e) INSERT + SELECT de ida e volta em projudi_logs.log_raw, pelo usuário de
#      aplicação (é o caminho que a Frente B vai usar via JDBC)
#
# Extras (não bloqueiam o resultado, mas são pré-requisito do CDC):
#   f) Oracle em ARCHIVELOG, supplemental logging ligado, usuário do Debezium ok
#
# Sai com 0 se tudo passou, 1 se algum item obrigatório falhou.
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

CH_APP_USER="${CH_APP_USER:-projudi_app}"
CH_APP_PASSWORD="${CH_APP_PASSWORD:-projudi_app_dev}"
ORACLE_SYS_PASSWORD="${ORACLE_SYS_PASSWORD:-oracle_dev}"
ORACLE_PROJUDI_PASSWORD="${ORACLE_PROJUDI_PASSWORD:-projudi_dev}"
ORACLE_DBZ_USER="${ORACLE_DBZ_USER:-c##dbzuser}"

# ---- cosmética -------------------------------------------------------------
if [ -t 1 ]; then
    C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
    C_RED=$'\033[31m';  C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
else
    C_RESET=""; C_BOLD=""; C_DIM=""; C_RED=""; C_GREEN=""; C_YELLOW=""
fi

PASS=0; FAIL=0; WARNS=0

ok()    { PASS=$((PASS+1));   printf '  %s✅%s %s\n' "${C_GREEN}"  "${C_RESET}" "$*"; }
bad()   { FAIL=$((FAIL+1));   printf '  %s❌%s %s\n' "${C_RED}"    "${C_RESET}" "$*"; }
warn()  { WARNS=$((WARNS+1)); printf '  %s⚠️%s  %s\n' "${C_YELLOW}" "${C_RESET}" "$*"; }
info()  { printf '     %s%s%s\n' "${C_DIM}" "$*" "${C_RESET}"; }
title() { printf '\n%s%s%s\n' "${C_BOLD}" "$*" "${C_RESET}"; }

dc() { docker compose --env-file .env -f "${COMPOSE_FILE}" "$@"; }

trim() { tr -d '\r' | tr -d '[:space:]'; }

ch()     { dc exec -T clickhouse clickhouse-client --query "$1" 2>/dev/null; }
ch_app() { dc exec -T clickhouse clickhouse-client --user "${CH_APP_USER}" \
              --password "${CH_APP_PASSWORD}" --query "$1" 2>/dev/null; }

ora() {
    dc exec -T oracle sqlplus -S -L \
        "PROJUDI/${ORACLE_PROJUDI_PASSWORD}@//localhost:1521/FREEPDB1" 2>/dev/null <<EOF
set heading off feedback off pagesize 0 verify off echo off
$1
exit;
EOF
}

ora_sys() {
    dc exec -T oracle sqlplus -S -L \
        "sys/${ORACLE_SYS_PASSWORD}@//localhost:1521/FREE as sysdba" 2>/dev/null <<EOF
set heading off feedback off pagesize 0 verify off echo off
$1
exit;
EOF
}

# -----------------------------------------------------------------------------
title "0  Healthchecks dos containers"

wait_healthy() {
    local container="$1" limite="${2:-180}" esperado=0
    local status
    while [ "${esperado}" -lt "${limite}" ]; do
        status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
                  "${container}" 2>/dev/null || echo "ausente")"
        case "${status}" in
            healthy|running) return 0 ;;
            ausente)         return 2 ;;
        esac
        sleep 3
        esperado=$((esperado+3))
    done
    return 1
}

for c in projudi-clickhouse projudi-oracle projudi-kafka projudi-connect; do
    printf '     aguardando %s ...\r' "${c}"
    if wait_healthy "${c}" 300; then
        ok "${c} saudável"
    else
        rc=$?
        if [ "${rc}" -eq 2 ]; then
            bad "${c} não existe — o ambiente subiu? (make up)"
        else
            bad "${c} não ficou saudável em 5 min — veja 'make logs s=${c#projudi-}'"
        fi
    fi
done

if [ "${FAIL}" -gt 0 ]; then
    printf '\n%sAbortando: containers não estão saudáveis.%s\n\n' "${C_RED}" "${C_RESET}"
    exit 1
fi

# -----------------------------------------------------------------------------
title "a  ClickHouse — bancos e tabelas"

if [ "$(ch 'SELECT 1' | trim)" = "1" ]; then
    ok "ClickHouse respondendo (versão $(ch 'SELECT version()' | trim))"
else
    bad "ClickHouse não respondeu a 'SELECT 1'"
fi

for db in projudi_logs projudi_historico; do
    if [ "$(ch "SELECT count() FROM system.databases WHERE name = '${db}'" | trim)" = "1" ]; then
        ok "banco ${db} existe"
    else
        bad "banco ${db} NÃO existe — os DDLs de init não rodaram (volume pré-existente? use 'make reset')"
    fi
done

check_tabela() {
    local db="$1" tabela="$2" tipo="$3"
    if [ "$(ch "SELECT count() FROM system.tables WHERE database='${db}' AND name='${tabela}'" | trim)" = "1" ]; then
        ok "${db}.${tabela} existe (${tipo})"
    else
        bad "${db}.${tabela} NÃO existe"
    fi
}

check_tabela projudi_logs      log_raw        "MergeTree"
check_tabela projudi_logs      log_tipo       "ReplacingMergeTree"
check_tabela projudi_historico proc_cdc       "MergeTree"
check_tabela projudi_historico proc_cdc_kafka "Kafka engine"
check_tabela projudi_historico proc_cdc_mv    "MATERIALIZED VIEW"

N_COLS="$(ch "SELECT count() FROM system.columns WHERE database='projudi_historico' AND table='proc_cdc'" | trim)"
if [ "${N_COLS}" = "47" ]; then
    ok "proc_cdc com 47 colunas (43 da PROC + 4 de metadata CDC)"
else
    bad "proc_cdc tem ${N_COLS} colunas, esperado 47 (43 da PROC + 4 de metadata)"
fi

if [ "$(ch "SELECT count() FROM system.users WHERE name = '${CH_APP_USER}'" | trim)" = "1" ]; then
    ok "usuário de aplicação '${CH_APP_USER}' existe"
else
    bad "usuário de aplicação '${CH_APP_USER}' NÃO existe (ver ddl/90_app_user.sh)"
fi

# -----------------------------------------------------------------------------
title "b  Oracle — schema PROJUDI e dados de exemplo"

if [ "$(ora 'select 1 from dual;' | trim)" = "1" ]; then
    ok "Oracle respondendo, usuário PROJUDI autentica no FREEPDB1"
else
    bad "não foi possível conectar como PROJUDI no FREEPDB1"
fi

check_tabela_ora() {
    local tabela="$1" minimo="$2"
    local n
    n="$(ora "select count(*) from PROJUDI.${tabela};" | trim)"
    if ! [ "${n}" -ge 0 ] 2>/dev/null; then
        bad "PROJUDI.${tabela} não existe ou não pôde ser consultada"
        return
    fi
    if [ "${n}" -ge "${minimo}" ]; then
        ok "PROJUDI.${tabela} existe com ${n} linha(s) (mínimo esperado: ${minimo})"
    else
        bad "PROJUDI.${tabela} tem apenas ${n} linha(s), esperado ao menos ${minimo}"
    fi
}

check_tabela_ora LOG_TIPO 6
check_tabela_ora LOG      8
check_tabela_ora PROC     6

N_COLS_PROC="$(ora "select count(*) from user_tab_columns where table_name = 'PROC';" | trim)"
if [ "${N_COLS_PROC}" = "43" ]; then
    ok "PROJUDI.PROC com as 43 colunas de produção"
else
    bad "PROJUDI.PROC tem ${N_COLS_PROC} colunas, esperado 43"
fi

# -----------------------------------------------------------------------------
title "c  Kafka — broker e tópicos"

TOPICOS="$(dc exec -T kafka kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null | tr -d '\r')"
if [ -n "${TOPICOS}" ]; then
    ok "Kafka respondendo — $(printf '%s\n' "${TOPICOS}" | grep -c . ) tópico(s)"
    printf '%s\n' "${TOPICOS}" | grep . | sed 's/^/       - /'
elif dc exec -T kafka kafka-topics --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then
    ok "Kafka respondendo — nenhum tópico ainda (esperado antes de registrar o conector)"
else
    bad "Kafka não respondeu ao kafka-topics"
fi

# -----------------------------------------------------------------------------
title "d  Kafka Connect — plugins do Debezium"

PLUGINS="$(dc exec -T connect curl -sf http://localhost:8083/connector-plugins 2>/dev/null || true)"
if [ -n "${PLUGINS}" ]; then
    ok "Kafka Connect REST respondendo na 8083"
    if printf '%s' "${PLUGINS}" | grep -q 'io.debezium.connector.oracle.OracleConnector'; then
        ok "plugin io.debezium.connector.oracle.OracleConnector disponível"
    else
        bad "plugin do Debezium para Oracle NÃO está na lista de plugins"
        info "plugins vistos: $(printf '%s' "${PLUGINS}" | grep -o '"class":"[^"]*"' | sed 's/.*://' | tr -d '"' | tr '\n' ' ')"
    fi
else
    bad "Kafka Connect não respondeu em http://localhost:8083/connector-plugins"
fi

if dc exec -T connect sh -c 'ls /kafka/libs/ojdbc11.jar' >/dev/null 2>&1; then
    ok "driver ojdbc11 presente no classpath do Connect"
else
    bad "ojdbc11.jar ausente em /kafka/libs — o conector Oracle vai falhar ao registrar"
fi

# -----------------------------------------------------------------------------
title "e  ClickHouse — escrita e leitura pelo usuário de aplicação"

MARCADOR="__validate__"
ID_TESTE=999999999999

ch_app "ALTER TABLE projudi_logs.log_raw DELETE WHERE ID_LOG = ${ID_TESTE} SETTINGS mutations_sync = 1" >/dev/null 2>&1

INSERT_OK=1
ch_app "INSERT INTO projudi_logs.log_raw
        (ID_LOG, ID_LOG_TIPO, ID_USU, IP_COMPUTADOR, DATA, HORA, TABELA,
         VALOR_ATUAL, VALOR_NOVO, CODIGO_TEMP, ID_TABELA, HASH, QTD_ERROS_DIA)
        VALUES
        (${ID_TESTE}, 2, 4242, '127.0.0.1', now(), now(), '${MARCADOR}',
         'CAMPO=antes', 'CAMPO=depois', NULL, 7, '00000000000000000000000000000000', 0)" \
    >/dev/null 2>&1 || INSERT_OK=0

if [ "${INSERT_OK}" -eq 1 ]; then
    ok "INSERT em projudi_logs.log_raw aceito (usuário ${CH_APP_USER})"
else
    bad "INSERT em projudi_logs.log_raw falhou — permissão do usuário de aplicação?"
fi

LIDO="$(ch_app "SELECT VALOR_NOVO FROM projudi_logs.log_raw WHERE ID_LOG = ${ID_TESTE}" | trim)"
if [ "${LIDO}" = "CAMPO=depois" ]; then
    ok "SELECT devolveu a linha gravada, com o conteúdo íntegro"
else
    bad "SELECT não devolveu a linha esperada (recebido: '${LIDO}')"
fi

if ch_app "ALTER TABLE projudi_logs.log_raw DELETE WHERE ID_LOG = ${ID_TESTE} SETTINGS mutations_sync = 1" >/dev/null 2>&1; then
    ok "linha de teste removida (tabela limpa)"
else
    warn "não foi possível remover a linha de teste ID_LOG=${ID_TESTE}"
fi

# -----------------------------------------------------------------------------
title "f  Pré-requisitos do CDC (Solução 2)"

LOG_MODE="$(ora_sys 'select log_mode from v$database;' | trim)"
if [ "${LOG_MODE}" = "ARCHIVELOG" ]; then
    ok "Oracle em ARCHIVELOG"
else
    warn "Oracle em '${LOG_MODE}', não ARCHIVELOG — o Debezium não vai conseguir usar LogMiner"
    info "ver docs/decisoes.md, seção ARCHIVELOG, para o procedimento manual"
fi

SUPP_DB="$(ora_sys 'select supplemental_log_data_min from v$database;' | trim)"
case "${SUPP_DB}" in
    YES|IMPLICIT) ok "supplemental logging de banco ativo (${SUPP_DB})" ;;
    *)            warn "supplemental logging de banco: '${SUPP_DB}' — esperado YES/IMPLICIT" ;;
esac

SUPP_PROC="$(ora "select count(*) from user_log_groups where table_name = 'PROC';" | trim)"
if [ "${SUPP_PROC}" -ge 1 ] 2>/dev/null; then
    ok "supplemental logging (ALL) COLUMNS ativo na PROJUDI.PROC"
else
    warn "não foi encontrado log group na PROJUDI.PROC — updates virão com colunas nulas"
fi

DBZ_OK="$(ora_sys "select count(*) from cdb_users where username = upper('${ORACLE_DBZ_USER}') and rownum = 1;" | trim)"
if [ "${DBZ_OK}" = "1" ]; then
    ok "usuário do Debezium '${ORACLE_DBZ_USER}' existe"
else
    warn "usuário do Debezium '${ORACLE_DBZ_USER}' não encontrado"
fi

# -----------------------------------------------------------------------------
printf '\n%s────────────────────────────────────────────────────────%s\n' "${C_BOLD}" "${C_RESET}"
printf '  %s%d passaram%s   %s%d falharam%s   %s%d avisos%s\n' \
    "${C_GREEN}" "${PASS}" "${C_RESET}" \
    "${C_RED}"   "${FAIL}" "${C_RESET}" \
    "${C_YELLOW}" "${WARNS}" "${C_RESET}"
printf '%s────────────────────────────────────────────────────────%s\n\n' "${C_BOLD}" "${C_RESET}"

if [ "${FAIL}" -gt 0 ]; then
    echo "Ambiente NÃO está pronto. Veja os itens marcados com ❌ acima."
    echo
    exit 1
fi

cat <<EOF
Ambiente pronto.

  Frente B (log-writer):  ClickHouse em jdbc:ch://localhost:${CLICKHOUSE_HTTP_PORT:-8123}/projudi_logs
                          usuário ${CH_APP_USER}
  Frente C (CDC):         ./scripts/register-connector.sh   para registrar o conector Debezium

EOF
exit 0
