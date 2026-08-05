#!/usr/bin/env bash
# =============================================================================
# disco.sh — quanto espaço o ambiente está ocupando, e onde
#
# Existe por um motivo concreto: em 2026-08-05 o disco do host encheu e a
# máquina de desenvolvimento travou ao subir o Docker. A causa é estrutural, não
# acidental — com ARCHIVELOG ligado (pré-requisito do LogMiner/Debezium, ver
# decisão 5) o Oracle acumula archived redo logs indefinidamente e NADA os
# apaga. Em produção isso é rotina de DBA; em container, não há DBA.
#
# Rode antes e depois de qualquer bateria pesada de testes. Ver decisão 27.
#
# Uso:
#   ./scripts/disco.sh            (ou `make disco`)
#
# Variáveis:
#   FRA_ALERTA   percentual da FRA a partir do qual avisa (padrão: 70)
#   FRA_CRITICO  percentual a partir do qual marca como crítico (padrão: 85)
#
# Sai com 0 sempre que consegue medir: este script informa, não reprova. Quem
# reprova é o `make validate`.
# =============================================================================

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

COMPOSE_FILE="infra/docker-compose.yml"
FRA_ALERTA="${FRA_ALERTA:-70}"
FRA_CRITICO="${FRA_CRITICO:-85}"

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
title() { printf '\n%s%s%s\n\n' "${C_BOLD}" "$*" "${C_RESET}"; }

printf '\n%s══ Uso de disco — projudi-log-offload ══%s\n' "${C_BOLD}" "${C_RESET}"

# -----------------------------------------------------------------------------
title "1  Sistema de arquivos do host"

df -h / 2>/dev/null | awk 'NR==1 {printf "     %-22s %8s %8s %8s %6s\n", "MONTADO EM", "TAM", "USADO", "LIVRE", "USO%"}
                            NR>1  {printf "     %-22s %8s %8s %8s %6s\n", $6, $2, $3, $4, $5}'

# WSL2: o que importa de verdade é o espaço no disco do WINDOWS, porque os
# .vhdx das distros moram lá e crescem sem devolver espaço sozinhos. Apagar
# arquivo dentro do Linux NÃO encolhe o .vhdx — só `wsl --shutdown` seguido de
# compactação (Optimize-VHD / diskpart compact vdisk) devolve espaço ao host.
if [ -d /mnt/c ]; then
    echo
    df -h /mnt/c 2>/dev/null | awk 'NR>1 {printf "     %-22s %8s %8s %8s %6s\n", "C: (Windows)", $2, $3, $4, $5}'

    VHDX_ENCONTRADO=0
    while IFS= read -r arquivo; do
        [ -f "${arquivo}" ] || continue
        if [ "${VHDX_ENCONTRADO}" -eq 0 ]; then
            echo
            info "discos virtuais das distros WSL2 (ocupam espaço no C:):"
            VHDX_ENCONTRADO=1
        fi
        TAM="$(du -h --apparent-size "${arquivo}" 2>/dev/null | cut -f1)"
        printf '       %8s  %s\n' "${TAM:-?}" "${arquivo}"
    done < <(ls -1 /mnt/c/Users/*/AppData/Local/Docker/wsl/*/*.vhdx \
                   /mnt/c/Users/*/AppData/Local/wsl/*/*.vhdx 2>/dev/null)

    if [ "${VHDX_ENCONTRADO}" -eq 1 ]; then
        info "apagar arquivos dentro do Linux não encolhe estes .vhdx;"
        info "é preciso 'wsl --shutdown' + compactação no PowerShell."
    fi
fi

# -----------------------------------------------------------------------------
title "2  Docker — imagens, containers e volumes"

if ! docker info >/dev/null 2>&1; then
    warn "o daemon do Docker não respondeu — pulando as seções 2 e 3"
    info "no WSL2 com Docker Desktop, verifique se ele está iniciado"
    printf '\n'
    exit 0
fi

docker system df 2>/dev/null | sed 's/^/     /'

echo
info "volumes (do maior para o menor):"
docker system df -v 2>/dev/null \
    | awk '/^Local Volumes space usage:/{flag=1; next} /^Build cache usage:/{flag=0} flag' \
    | awk 'NF && $1 != "VOLUME" {printf "%s\t%s\n", $NF, $1}' \
    | sort -h -r \
    | awk '{printf "       %10s  %s\n", $1, $2}'

# -----------------------------------------------------------------------------
title "3  Oracle — Fast Recovery Area (onde os archived redo logs se acumulam)"

ESTADO="$(docker inspect --format '{{.State.Status}}' projudi-oracle 2>/dev/null || echo ausente)"
if [ "${ESTADO}" != "running" ]; then
    warn "projudi-oracle não está em execução (estado: ${ESTADO}) — FRA não medida"
    info "suba com 'make up' e rode de novo"
    printf '\n'
    exit 0
fi

# </dev/null é obrigatório: se o sqlplus resolver pedir alguma coisa
# interativamente, o prompt some junto com o redirecionamento e o script trava
# esperando uma digitação que ninguém sabe que precisa fazer. Ver validate.sh.
ora_sys() {
    timeout 60 docker compose --env-file .env -f "${COMPOSE_FILE}" \
        exec -T oracle sqlplus -S "/ as sysdba" <<EOF 2>/dev/null
set heading off feedback off pagesize 0 verify off echo off linesize 200
$1
exit;
EOF
}

LOG_MODE="$(ora_sys 'select log_mode from v$database;' | tr -d '\r' | tr -d '[:space:]')"

# Quantos archived logs existem e quanto ocupam — INDEPENDENTE de haver FRA.
# Sem db_recovery_file_dest definido, v$recovery_file_dest devolve ZERO linhas e
# uma medição baseada só nela reportaria "tudo bem" com o disco enchendo.
ARQ="$(ora_sys "select count(*) || '|' || nvl(round(sum(blocks*block_size)/1024/1024), 0)
                  from v\$archived_log where deleted = 'NO';" | tr -d '\r' | grep '|' | head -1)"
ARQ_N="${ARQ%%|*}"; ARQ_MB="${ARQ##*|}"

FRA="$(ora_sys "select name || '|' || space_limit || '|' || space_used || '|' || space_reclaimable || '|' || number_of_files from v\$recovery_file_dest;" \
       | tr -d '\r' | grep '|' | head -1)"

# -----------------------------------------------------------------------------
# Sem FRA configurada não é "erro de leitura": é uma configuração de risco.
#
# Medido em 2026-08-05: a imagem gvenzl/oracle-free sobe SEM Fast Recovery Area
# (db_recovery_file_dest nulo, db_recovery_file_dest_size = 0). Os archived logs
# vão para $ORACLE_HOME/dbs/arch — a camada gravável do container, FORA do
# volume oracle-data. Duas consequências: não há teto nenhum, e os arquivos
# somem quando o container é recriado, quebrando o conector Debezium que
# dependia deles (ORA-01291). Ver decisão 27.
# -----------------------------------------------------------------------------
if [ -z "${FRA}" ]; then
    printf '     %-18s %s\n' "log_mode" "${LOG_MODE}"
    printf '     %-18s %s archived log(s), %s MB\n' "arquivados" "${ARQ_N:-?}" "${ARQ_MB:-?}"
    echo
    DEST="$(ora_sys "select destination from v\$archive_dest where destination is not null and rownum = 1;" \
            | tr -d '\r' | tr -d '[:space:]')"
    if [ "${LOG_MODE}" = "ARCHIVELOG" ]; then
        bad "não há Fast Recovery Area configurada (db_recovery_file_dest nulo)"
        info "destino de arquivamento: ${DEST:-?}"
        info "sem FRA não existe teto: o arquivamento só para quando o disco acaba,"
        info "e o que está fora de /opt/oracle/oradata não sobrevive à recriação"
        info "do container. Ambientes novos já nascem corrigidos:"
        info "  make reset && make up      (aplica ORACLE_FRA_SIZE via 06_fra_size.sql)"
    else
        ok "banco em ${LOG_MODE}: não gera archived logs (mas o CDC não funciona)"
    fi
    printf '\n'
    exit 0
fi

IFS='|' read -r FRA_DEST FRA_LIMITE FRA_USADO FRA_RECUP FRA_ARQS <<< "${FRA}"

hum() {  # bytes -> humano
    awk -v b="${1:-0}" 'BEGIN {
        split("B KB MB GB TB", u, " "); i = 1;
        while (b >= 1024 && i < 5) { b /= 1024; i++ }
        printf (i == 1 ? "%d %s" : "%.1f %s"), b, u[i]
    }'
}

PCT=0
if [ "${FRA_LIMITE:-0}" -gt 0 ] 2>/dev/null; then
    PCT="$(awk -v u="${FRA_USADO}" -v l="${FRA_LIMITE}" 'BEGIN {printf "%d", (u*100)/l}')"
fi

# printf conta BYTES, não caracteres: "recuperável" tem 11 caracteres e 12
# bytes, e um %-18s desalinha a coluna. ${#var} no bash conta caracteres.
rot() {
    local rotulo="$1"; shift
    local pad=$(( 18 - ${#rotulo} ))
    [ "${pad}" -lt 1 ] && pad=1
    printf '     %s%*s%s\n' "${rotulo}" "${pad}" "" "$*"
}

rot "log_mode"    "${LOG_MODE}"
rot "destino"     "${FRA_DEST}"
rot "limite"      "$(hum "${FRA_LIMITE}")"
rot "em uso"      "$(hum "${FRA_USADO}") (${PCT}%)"
rot "recuperável" "$(hum "${FRA_RECUP}")"
rot "arquivos"    "${FRA_ARQS}"
rot "arquivados"  "${ARQ_N:-?} archived log(s), ${ARQ_MB:-?} MB"

# Archived log fora do volume não sobrevive à recriação do container — e o
# conector Debezium que dependia dele quebra com ORA-01291. Acontece em
# ambientes criados antes da decisão 27, que já tinham logs em dbs/arch.
FORA="$(ora_sys "select count(*) from v\$archived_log
                  where deleted = 'NO' and name not like '/opt/oracle/oradata/%';" \
        | tr -d '\r' | tr -d '[:space:]')"
if [ "${FORA:-0}" -gt 0 ] 2>/dev/null; then
    rot "fora do volume" "${FORA} (não sobrevivem à recriação do container)"
fi

echo
info "ocupação por tipo de arquivo:"
ora_sys "select rpad(file_type, 22) || lpad(percent_space_used, 6) || lpad(percent_space_reclaimable, 8) || lpad(number_of_files, 8) from v\$flash_recovery_area_usage where number_of_files > 0;" \
    | tr -d '\r' | grep -E '[A-Z]' \
    | awk 'BEGIN {printf "       %-22s %6s %8s %8s\n", "TIPO", "USO%", "RECUP%", "ARQS"} {print "       " $0}'

echo
if [ "${LOG_MODE}" != "ARCHIVELOG" ]; then
    ok "banco em ${LOG_MODE}: não gera archived logs (mas o CDC não funciona)"
elif [ "${PCT}" -ge "${FRA_CRITICO}" ]; then
    bad "FRA em ${PCT}% — crítico"
    info "com a FRA cheia o Oracle SUSPENDE as escritas (ORA-19809/ORA-19804)."
    info "rode agora:  make limpar-archivelog"
elif [ "${PCT}" -ge "${FRA_ALERTA}" ]; then
    warn "FRA em ${PCT}% (limite de alerta: ${FRA_ALERTA}%)"
    info "rode:  make limpar-archivelog"
else
    ok "FRA em ${PCT}% — dentro do limite de alerta (${FRA_ALERTA}%)"
fi

printf '\n'
exit 0
