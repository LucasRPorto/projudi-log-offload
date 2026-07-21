#!/usr/bin/env bash
# =============================================================================
# Inicialização do Oracle para o MVP
#
# Executado UMA VEZ pela imagem gvenzl/oracle-free, no primeiro start do
# container (diretório /container-entrypoint-initdb.d), depois que o banco já
# está aberto.
#
# POR QUE UM ORQUESTRADOR .sh E NÃO VÁRIOS .sql SOLTOS
# ----------------------------------------------------
# 1. Senhas: PROJUDI e c##dbzuser precisam vir do .env. Um .sql versionado não
#    consegue ler variável de ambiente; aqui elas entram como parâmetros
#    posicionais do SQL*Plus (&1, &2).
# 2. Container: este é um banco multitenant (CDB=FREE, PDB=FREEPDB1). Parte do
#    setup do Debezium é no CDB$ROOT (usuário comum, supplemental logging de
#    banco) e parte é no PDB (schema PROJUDI, supplemental logging da tabela).
#    Um .sql solto herdaria o container em que a imagem resolveu conectar; aqui
#    cada script recebe uma connect string EXPLÍCITA, sem ambiguidade.
#
# Os .sql ficam em ./sql/ (subdiretório) justamente para que a imagem não os
# execute por conta própria — ela varre apenas o primeiro nível do diretório.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_DIR="${SCRIPT_DIR}/sql"

SYS_PWD="${ORACLE_PASSWORD:?ORACLE_PASSWORD nao definido pelo compose}"
PROJUDI_PWD="${ORACLE_PROJUDI_PASSWORD:-projudi_dev}"
DBZ_USER="${ORACLE_DBZ_USER:-c##dbzuser}"
DBZ_PWD="${ORACLE_DBZ_PASSWORD:-dbz_dev}"

CDB="//localhost:1521/FREE"
PDB="//localhost:1521/FREEPDB1"

log()  { echo "[init-projudi] $*"; }
fail() { echo "[init-projudi] ERRO: $*" >&2; exit 1; }

run_sql() {
    local target="$1"; shift
    local script="$1"; shift
    local name
    name="$(basename "${script}")"
    log "executando ${name} em ${target}"
    sqlplus -S -L "sys/${SYS_PWD}@${target} as sysdba" "@${script}" "$@" \
        || fail "falha ao executar ${name}"
}

query_scalar() {
    local target="$1"; shift
    local sql="$1"; shift
    sqlplus -S -L "sys/${SYS_PWD}@${target} as sysdba" <<EOF | tr -d '[:space:]'
set heading off feedback off pagesize 0 verify off echo off termout on
${sql}
exit;
EOF
}

# -----------------------------------------------------------------------------
# 0) Pré-condição do Debezium: o banco precisa estar em ARCHIVELOG.
#
#    A imagem gvenzl/oracle-free sobe em NOARCHIVELOG e NÃO interpreta a
#    variável ENABLE_ARCHIVELOG — isso foi confirmado em execução real (ver
#    docs/decisoes.md, seção 5). A troca é feita aqui, explicitamente.
#
#    Se ainda assim falhar, avisamos ALTO e seguimos: o schema PROJUDI e a
#    Solução 1 funcionam sem archivelog; só o CDC (Solução 2) fica bloqueado.
# -----------------------------------------------------------------------------
log "verificando log_mode do banco"
LOG_MODE="$(query_scalar "${CDB}" "select log_mode from v\$database;")"

if [ "${LOG_MODE}" = "ARCHIVELOG" ]; then
    log "log_mode = ARCHIVELOG (ok, LogMiner disponivel)"
else
    log "log_mode = ${LOG_MODE}; habilitando ARCHIVELOG (o banco sera reiniciado)"

    # A variavel de ambiente ENABLE_ARCHIVELOG NAO e interpretada por esta
    # imagem -- verificado no codigo do entrypoint e confirmado em execucao
    # real. A unica forma e a sequencia explicita de shutdown/mount/alter/open.
    # Precisa de conexao local: STARTUP nao funciona pelo listener.
    if sqlplus -S "/ as sysdba" "@${SQL_DIR}/05_enable_archivelog.sql"; then

        # Depois do restart o listener leva alguns segundos para registrar de
        # novo o servico do PDB. Sem esperar, o proximo script falharia com
        # ORA-12514 e pareceria erro de configuracao.
        log "aguardando o listener registrar FREEPDB1"
        for _ in $(seq 1 30); do
            if sqlplus -S -L "sys/${SYS_PWD}@${PDB} as sysdba" >/dev/null 2>&1 <<'EOSQL'
select 1 from dual;
exit;
EOSQL
            then break; fi
            sleep 3
        done

        LOG_MODE="$(query_scalar "${CDB}" "select log_mode from v\$database;")"
        log "log_mode agora = ${LOG_MODE}"
    else
        log "ERRO: nao foi possivel habilitar ARCHIVELOG"
    fi
fi

if [ "${LOG_MODE}" != "ARCHIVELOG" ]; then
    cat >&2 <<'EOW'

  ###########################################################################
  #  ATENCAO: o banco NAO esta em ARCHIVELOG.                               #
  #                                                                         #
  #  O Debezium Oracle Connector usa LogMiner e EXIGE ARCHIVELOG. A         #
  #  Solucao 2 (CDC) nao vai funcionar ate que isso seja corrigido.         #
  #  A Solucao 1 (offload de log) nao e afetada.                            #
  #                                                                         #
  #  Com o ambiente de pe, tente:   make archivelog                         #
  #                                                                         #
  #  Ver docs/decisoes.md, secao 5.                                         #
  ###########################################################################

EOW
fi

# -----------------------------------------------------------------------------
# 1) Tablespace do LogMiner dentro do PDB.
#    Precisa existir ANTES do CREATE USER ... CONTAINER=ALL: o Oracle exige que
#    a default tablespace de um usuário comum exista em todos os containers.
# -----------------------------------------------------------------------------
run_sql "${PDB}" "${SQL_DIR}/10_pdb_tablespace.sql"

# -----------------------------------------------------------------------------
# 2) CDB$ROOT: usuário comum do Debezium + grants + supplemental logging de banco
# -----------------------------------------------------------------------------
run_sql "${CDB}" "${SQL_DIR}/20_cdb_debezium_user.sql" "${DBZ_USER}" "${DBZ_PWD}"

# -----------------------------------------------------------------------------
# 3) PDB: schema PROJUDI, tabelas, dados de exemplo
# -----------------------------------------------------------------------------
run_sql "${PDB}" "${SQL_DIR}/30_pdb_projudi_user.sql" "${PROJUDI_PWD}"
run_sql "${PDB}" "${SQL_DIR}/40_pdb_tables.sql"
run_sql "${PDB}" "${SQL_DIR}/50_pdb_seed.sql"

# -----------------------------------------------------------------------------
# 4) PDB: supplemental logging da PROC (todas as colunas) + SELECT p/ o Debezium
# -----------------------------------------------------------------------------
run_sql "${PDB}" "${SQL_DIR}/60_pdb_supplemental_proc.sql" "${DBZ_USER}"

log "-----------------------------------------------------------------"
log "inicialização concluída"
log "  CDB .......... FREE          PDB ........... FREEPDB1"
log "  schema ....... PROJUDI       tabelas ....... LOG, LOG_TIPO, PROC"
log "  usuário CDC .. ${DBZ_USER}"
log "  log_mode ..... ${LOG_MODE}"
log "-----------------------------------------------------------------"
