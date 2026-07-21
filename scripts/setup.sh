#!/usr/bin/env bash
# =============================================================================
# setup.sh — preparação do ambiente local
#
# Verifica pré-requisitos, cria o .env a partir do .env.example, baixa as
# imagens e constrói a imagem do Kafka Connect (que embute o driver da Oracle).
#
# Uso:  ./scripts/setup.sh        (ou `make setup`)
# =============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

COMPOSE_FILE="infra/docker-compose.yml"

# ---- cosmética -------------------------------------------------------------
if [ -t 1 ]; then
    C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'
    C_RED=$'\033[31m';  C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
else
    C_RESET=""; C_BOLD=""; C_RED=""; C_GREEN=""; C_YELLOW=""
fi

ok()    { printf '  %s✅%s %s\n' "${C_GREEN}" "${C_RESET}" "$*"; }
warn()  { printf '  %s⚠️%s  %s\n' "${C_YELLOW}" "${C_RESET}" "$*"; }
err()   { printf '  %s❌%s %s\n' "${C_RED}" "${C_RESET}" "$*"; }
title() { printf '\n%s%s%s\n' "${C_BOLD}" "$*" "${C_RESET}"; }

FATAL=0

# -----------------------------------------------------------------------------
title "1/5  Pré-requisitos"

if command -v docker >/dev/null 2>&1; then
    ok "docker encontrado — $(docker --version)"
else
    err "docker não encontrado no PATH."
    err "Instale o Docker Desktop (Windows/macOS) ou o Docker Engine (Linux)."
    FATAL=1
fi

if [ "${FATAL}" -eq 0 ]; then
    if docker compose version >/dev/null 2>&1; then
        ok "docker compose (v2) encontrado — $(docker compose version --short 2>/dev/null || echo v2)"
    else
        err "'docker compose' (plugin v2) não encontrado."
        err "O 'docker-compose' v1 legado NÃO serve: este projeto usa 'depends_on.condition'."
        FATAL=1
    fi

    if ! docker info >/dev/null 2>&1; then
        err "O daemon do Docker não está respondendo. Inicie o Docker e rode de novo."
        FATAL=1
    fi
fi

if [ "${FATAL}" -ne 0 ]; then
    printf '\n%sAbortando: corrija os itens acima antes de continuar.%s\n\n' "${C_RED}" "${C_RESET}"
    exit 1
fi

# -----------------------------------------------------------------------------
title "2/5  Recursos disponíveis"

# MemTotal do daemon é o número que importa: no Windows/macOS é a RAM da VM do
# Docker, não a da máquina física.
MEM_BYTES="$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 0)"
if [ "${MEM_BYTES}" -gt 0 ] 2>/dev/null; then
    MEM_GB=$(( MEM_BYTES / 1024 / 1024 / 1024 ))
    if [ "${MEM_GB}" -ge 12 ]; then
        ok "RAM disponível para o Docker: ${MEM_GB} GB"
    else
        warn "RAM disponível para o Docker: ${MEM_GB} GB (recomendado: 12 GB ou mais)."
        warn "Oracle + Kafka + Connect + ClickHouse juntos pedem ~8-10 GB."
        warn "Docker Desktop: Settings > Resources > Memory. No Linux, feche outras cargas."
    fi
else
    warn "Não foi possível medir a RAM do daemon Docker; siga com atenção ao consumo."
fi

CPUS="$(docker info --format '{{.NCPU}}' 2>/dev/null || echo 0)"
if [ "${CPUS}" -ge 4 ] 2>/dev/null; then
    ok "CPUs disponíveis para o Docker: ${CPUS}"
else
    warn "Apenas ${CPUS} CPU(s) para o Docker. O primeiro start do Oracle vai demorar."
fi

# Portas
check_port() {
    local port="$1" servico="$2"
    if (exec 3<>"/dev/tcp/127.0.0.1/${port}") 2>/dev/null; then
        exec 3<&- 2>/dev/null || true
        warn "porta ${port} (${servico}) já está em uso — ajuste o .env ou libere a porta"
    else
        ok "porta ${port} livre (${servico})"
    fi
}
check_port 8123  "ClickHouse HTTP"
check_port 9000  "ClickHouse native"
check_port 1521  "Oracle"
check_port 29092 "Kafka externo"
check_port 8083  "Kafka Connect"
check_port 8080  "Kafka UI"

# -----------------------------------------------------------------------------
title "3/5  Arquivo .env"

if [ -f .env ]; then
    ok ".env já existe — mantido como está"
else
    cp .env.example .env
    ok ".env criado a partir de .env.example"
    warn "as senhas são as de desenvolvimento; troque antes de expor a rede"
fi

# -----------------------------------------------------------------------------
title "4/5  Download das imagens"

echo "  (a primeira execução baixa ~3 GB e pode levar vários minutos)"

if docker compose --env-file .env -f "${COMPOSE_FILE}" pull --ignore-buildable 2>/dev/null; then
    ok "imagens baixadas"
elif docker compose --env-file .env -f "${COMPOSE_FILE}" pull --ignore-pull-failures; then
    ok "imagens baixadas"
else
    err "falha no download das imagens — verifique a conexão e tente de novo"
    exit 1
fi

# -----------------------------------------------------------------------------
title "5/5  Build da imagem do Kafka Connect"

echo "  (baixa o driver JDBC da Oracle do Maven Central e o instala no classpath)"

if docker compose --env-file .env -f "${COMPOSE_FILE}" build connect; then
    ok "imagem do Kafka Connect construída com o ojdbc11 embutido"
else
    err "falha ao construir a imagem do Kafka Connect."
    err "Causa mais comum: sem acesso ao repo1.maven.org (proxy/rede corporativa)."
    err "Alternativa: baixe o ojdbc11.jar manualmente e ajuste infra/debezium/Dockerfile"
    err "para copiá-lo do diretório local em vez de baixá-lo (ver README, Troubleshooting)."
    exit 1
fi

# -----------------------------------------------------------------------------
cat <<EOF

${C_BOLD}Ambiente preparado.${C_RESET}

Próximos passos:

  make up          sobe todos os serviços e espera ficarem saudáveis
                   (o Oracle leva de 2 a 5 minutos no PRIMEIRO start)
  make validate    roda a bateria de validação do ambiente
  make status      mostra o estado dos containers

Sem make? Os equivalentes estão no README, seção "Sem o make".

EOF
