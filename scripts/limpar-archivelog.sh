#!/usr/bin/env bash
# =============================================================================
# limpar-archivelog.sh — apaga archived redo logs, sem RMAN
#
# POR QUE ISTO EXISTE
# -------------------
# O CDC (Solução 2) exige o banco em ARCHIVELOG (decisão 5). Com ARCHIVELOG
# ligado, todo redo log preenchido é arquivado e fica lá. Nada apaga esses
# arquivos: em produção é rotina de DBA; em container, não existe DBA. O
# destino enche, o Oracle suspende as escritas (ORA-19809/ORA-19804), e o
# .vhdx do WSL2 leva o disco do host junto.
#
# A decisão 5 já registrava isso — como *lembrete operacional em texto*, e
# mandava usar `RMAN> DELETE ARCHIVELOG ALL`. Em 2026-08-05 o host caiu mesmo
# assim. Ver decisão 27: documentar não é implementar.
#
# POR QUE NÃO USA RMAN
# --------------------
# Porque não há RMAN. Verificado em execução real:
#
#   $ docker compose exec oracle rman target /
#   exec: "rman": executable file not found in $PATH
#   $ ls $ORACLE_HOME/bin/rman  ->  No such file or directory
#
# A imagem `gvenzl/oracle-free:23-slim-faststart` remove o RMAN — é parte do
# que a torna *slim*. O remédio prescrito pela decisão 5 nunca foi executável
# neste ambiente, e ninguém percebeu porque nunca foi executado.
#
# A alternativa usada aqui é `SYS.DBMS_BACKUP_RESTORE.deleteArchivedLog`, o
# pacote que o próprio RMAN chama por baixo: ele apaga o arquivo do disco E
# baixa o registro no controlfile, que é o par que mantém a contabilidade da
# FRA correta. Apagar os arquivos com `rm` faria o Oracle continuar contando
# espaço que já não existe, até recusar arquivamento com o disco vazio.
#
# O CUIDADO QUE NÃO É ÓBVIO
# -------------------------
# O Debezium lê o redo pelo LogMiner. Apagar um archived log que o conector
# ainda não consumiu o quebra com ORA-01291 (missing logfile), e a recuperação
# é re-registrar o conector — perdendo a posição. Por isso o padrão NÃO é
# `DELETE ARCHIVELOG ALL`: preserva-se uma janela recente (1 h por padrão), que
# é ordens de grandeza maior que a latência medida do pipeline (casa de
# segundos). O modo agressivo existe, mas exige `--tudo` explícito.
#
# Uso:
#   ./scripts/limpar-archivelog.sh              apaga o que tem mais de 1 hora
#   ./scripts/limpar-archivelog.sh --horas 6    apaga o que tem mais de 6 horas
#   ./scripts/limpar-archivelog.sh --tudo       apaga TUDO (pode quebrar o conector)
#
# Idempotente: rodar duas vezes seguidas não é erro; a segunda não acha nada.
# =============================================================================

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

COMPOSE_FILE="infra/docker-compose.yml"

HORAS=1
TUDO=0
while [ $# -gt 0 ]; do
    case "$1" in
        --tudo)  TUDO=1; shift ;;
        --horas) HORAS="${2:?--horas exige um número}"; shift 2 ;;
        -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
        *) echo "Uso: $0 [--horas N | --tudo]" >&2; exit 2 ;;
    esac
done

if ! [ "${HORAS}" -ge 0 ] 2>/dev/null; then
    echo "ERRO: --horas espera um inteiro não negativo (recebido: '${HORAS}')" >&2
    exit 2
fi

if [ -f .env ]; then
    set -a; . ./.env; set +a
fi

if [ -t 1 ]; then
    C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
    C_RED=$'\033[31m';  C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
else
    C_RESET=""; C_BOLD=""; C_DIM=""; C_RED=""; C_GREEN=""; C_YELLOW=""
fi
ok()    { printf '  %s✅%s %s\n' "${C_GREEN}"  "${C_RESET}" "$*"; }
bad()   { printf '  %s❌%s %s\n' "${C_RED}"    "${C_RESET}" "$*"; }
warn()  { printf '  %s⚠️%s  %s\n' "${C_YELLOW}" "${C_RESET}" "$*"; }
info()  { printf '     %s%s%s\n' "${C_DIM}" "$*" "${C_RESET}"; }

printf '\n%sLimpeza de archived redo logs (FRA)%s\n\n' "${C_BOLD}" "${C_RESET}"

ESTADO="$(docker inspect --format '{{.State.Status}}' projudi-oracle 2>/dev/null || echo ausente)"
if [ "${ESTADO}" != "running" ]; then
    bad "o container projudi-oracle não está em execução (estado: ${ESTADO})"
    info "rode 'make up' antes"
    exit 1
fi

ora_sys() {
    timeout 60 docker compose --env-file .env -f "${COMPOSE_FILE}" \
        exec -T oracle sqlplus -S "/ as sysdba" <<EOF 2>/dev/null
set heading off feedback off pagesize 0 verify off echo off linesize 200
$1
exit;
EOF
}

hum() {
    awk -v b="${1:-0}" 'BEGIN {
        split("B KB MB GB TB", u, " "); i = 1;
        while (b >= 1024 && i < 5) { b /= 1024; i++ }
        printf (i == 1 ? "%d %s" : "%.1f %s"), b, u[i]
    }'
}

# A medição principal é sobre v$archived_log, NÃO sobre a FRA.
#
# Motivo medido em 2026-08-05: sem db_recovery_file_dest definido, a imagem
# arquiva em $ORACLE_HOME/dbs/arch e `v$recovery_file_dest` devolve ZERO linhas.
# Um script que medisse só a FRA reportaria "nada a limpar" com o disco
# enchendo. v$archived_log enxerga os arquivos onde quer que eles estejam.
# Ver decisão 27.
medida() {
    ora_sys "select count(*) || '|' || nvl(round(sum(blocks*block_size)/1024/1024), 0)
               from v\$archived_log where deleted = 'NO';" \
        | tr -d '\r' | grep '|' | head -1
}

fra_usado() { ora_sys 'select space_used from v$recovery_file_dest;' | tr -d '\r' | tr -d '[:space:]'; }

LOG_MODE="$(ora_sys 'select log_mode from v$database;' | tr -d '\r' | tr -d '[:space:]')"
if [ "${LOG_MODE}" != "ARCHIVELOG" ]; then
    ok "banco em ${LOG_MODE} — não há archived logs para limpar"
    info "(o CDC exige ARCHIVELOG; ver 'make archivelog')"
    printf '\n'
    exit 0
fi

M_ANTES="$(medida)"
if [ -z "${M_ANTES}" ]; then
    bad "não foi possível ler v\$archived_log"
    info "o Oracle terminou de subir? veja: make logs s=oracle"
    exit 1
fi
N_ANTES="${M_ANTES%%|*}"
MB_ANTES="${M_ANTES##*|}"

info "antes: ${N_ANTES} archived log(s), ${MB_ANTES} MB"

# Onde eles estão é tão importante quanto quantos são: fora da FRA (ou seja,
# na camada gravável do container) eles somem quando o container é recriado —
# e o conector Debezium que dependia deles quebra com ORA-01291.
FORA="$(ora_sys "select count(*) from v\$archived_log
                  where deleted = 'NO' and name not like '/opt/oracle/oradata/%';" \
        | tr -d '\r' | tr -d '[:space:]')"
if [ "${FORA:-0}" -gt 0 ] 2>/dev/null; then
    warn "${FORA} archived log(s) estão FORA do volume oracle-data"
    info "eles não sobrevivem a uma recriação de container (mesma classe da decisão 18)"
    info "corrigido em ambientes criados pelo init atual; ver decisão 27"
fi
echo

# -----------------------------------------------------------------------------
# O que apagar
# -----------------------------------------------------------------------------
if [ "${TUDO}" -eq 1 ]; then
    FILTRO=""
    warn "modo --tudo: apagando TODOS os archived logs"
    info "se houver um conector Debezium registrado e atrasado, ele pode quebrar"
    info "com ORA-01291 (missing logfile) e precisar ser re-registrado."
else
    FILTRO="and completion_time < sysdate - ${HORAS}/24"
    info "apagando archived logs completados há mais de ${HORAS} hora(s)"
    info "(a janela recente fica preservada para o LogMiner/Debezium)"
fi
echo

SAIDA="$(timeout 300 docker compose --env-file .env -f "${COMPOSE_FILE}" \
    exec -T oracle sqlplus -S "/ as sysdba" <<EOF 2>&1
set serveroutput on
set feedback off
declare
    v_apagados number := 0;
    v_erros    number := 0;
begin
    for r in (select recid, stamp, name, thread#, sequence#,
                     resetlogs_change#, first_change#, block_size
                from v\$archived_log
               where deleted = 'NO'
                 and status <> 'D'
                 ${FILTRO}) loop
        begin
            sys.dbms_backup_restore.deleteArchivedLog(
                recid            => r.recid,
                stamp            => r.stamp,
                fname            => r.name,
                thread           => r.thread#,
                sequence         => r.sequence#,
                resetlogs_change => r.resetlogs_change#,
                first_change     => r.first_change#,
                blksize          => r.block_size);
            v_apagados := v_apagados + 1;
        exception
            -- Um arquivo que falha nao pode interromper a limpeza dos outros:
            -- o caso comum e o arquivo ja ter sumido do disco por fora, e
            -- abortar ali deixaria a FRA cheia por causa de um registro orfao.
            when others then
                v_erros := v_erros + 1;
                dbms_output.put_line('[erro] ' || r.name || ': ' || sqlerrm);
        end;
    end loop;
    dbms_output.put_line('[resultado] apagados=' || v_apagados || ' erros=' || v_erros);
end;
/
exit;
EOF
)"
RC_SQL=$?

TMP_SAIDA="$(mktemp)"
printf '%s\n' "${SAIDA}" > "${TMP_SAIDA}"

# 124 é o código do `timeout`. Distinguir isso de um erro do banco importa: um
# significa "o Oracle está lento ou travado", o outro "o comando foi recusado".
if [ "${RC_SQL}" -eq 124 ]; then
    bad "a limpeza não terminou em 300 s e foi interrompida"
    info "a FRA cheia pode ter suspendido as escritas; veja: make logs s=oracle"
    info "saída parcial em ${TMP_SAIDA}"
fi

if printf '%s' "${SAIDA}" | grep -q 'ORA-'; then
    warn "o banco reportou erros durante a limpeza:"
    printf '%s' "${SAIDA}" | grep -o 'ORA-[0-9]*[^ ]*.*' | sort -u | head -5 | sed 's/^/       /'
    info "saída completa em ${TMP_SAIDA}"
fi

# Sem esta checagem, um bloco que nem chegou a executar (pacote ausente,
# permissão negada, sqlplus não encontrado) seria indistinguível de uma limpeza
# que não achou nada para apagar — que foi exatamente como a versão anterior
# deste script, escrita para RMAN, reportou "nada a limpar" num banco com 12
# archived logs presentes. Ver decisão 27.
if ! printf '%s' "${SAIDA}" | grep -q '\[resultado\]'; then
    bad "o bloco de limpeza não chegou a reportar resultado"
    info "saída completa em ${TMP_SAIDA}"
    printf '%s' "${SAIDA}" | tail -5 | sed 's/^/       /'
    exit 1
fi

N_APAGADOS="$(printf '%s' "${SAIDA}" | sed -n 's/.*\[resultado\] apagados=\([0-9]*\).*/\1/p' | head -1)"
N_ERROS="$(printf '%s' "${SAIDA}"   | sed -n 's/.*erros=\([0-9]*\).*/\1/p' | head -1)"

if [ "${N_ERROS:-0}" -gt 0 ] 2>/dev/null; then
    warn "${N_ERROS} arquivo(s) não puderam ser apagados"
    printf '%s' "${SAIDA}" | grep '^\[erro\]' | head -3 | sed 's/^/       /'
fi

M_DEPOIS="$(medida)"

echo
if [ -n "${M_DEPOIS}" ]; then
    N_DEPOIS="${M_DEPOIS%%|*}"
    MB_DEPOIS="${M_DEPOIS##*|}"
    LIBERADO=$(( MB_ANTES - MB_DEPOIS ))
    [ "${LIBERADO}" -lt 0 ] && LIBERADO=0

    info "depois: ${N_DEPOIS} archived log(s), ${MB_DEPOIS} MB"

    FRA_DEPOIS="$(fra_usado)"
    if [ "${FRA_DEPOIS:-x}" -ge 0 ] 2>/dev/null; then
        info "FRA em uso: $(hum "${FRA_DEPOIS}")"
    else
        warn "não há Fast Recovery Area configurada (db_recovery_file_dest nulo)"
        info "os archived logs vão para \$ORACLE_HOME/dbs/arch, fora do volume."
        info "ambientes criados por 'make reset && make up' já nascem corrigidos."
    fi

    echo
    if [ "${LIBERADO}" -gt 0 ]; then
        ok "liberado: ${LIBERADO} MB  ($(( N_ANTES - N_DEPOIS )) arquivo(s) a menos)"
    else
        ok "nada a liberar — já estava limpo (${N_APAGADOS} arquivo(s) apagado(s) pelo RMAN)"
    fi
else
    warn "não foi possível medir os archived logs depois da limpeza"
fi

# O espaço volta para a FRA imediatamente, mas NÃO volta para o disco do
# Windows: o .vhdx do WSL2 só encolhe com compactação explícita. Dizer isso aqui
# evita a conclusão errada de que a limpeza não funcionou.
if [ -d /mnt/c ]; then
    echo
    info "no WSL2, o espaço volta para o Oracle mas não para o C: — o .vhdx não"
    info "encolhe sozinho. Para devolvê-lo ao Windows: 'wsl --shutdown' + compactação."
fi

printf '\n%sConfira com:%s  make disco\n\n' "${C_BOLD}" "${C_RESET}"
exit 0
