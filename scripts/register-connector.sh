#!/usr/bin/env bash
# =============================================================================
# register-connector.sh — registra o conector Debezium da PROJUDI.PROC
#
# Lê infra/debezium/connector-proc.json, substitui os placeholders pelos
# valores do .env e publica no Kafka Connect. Se o conector já existir, atualiza
# a configuração (PUT) em vez de falhar com 409.
#
# Uso:
#   ./scripts/register-connector.sh            registra/atualiza
#   ./scripts/register-connector.sh --status   só mostra o estado atual
#   ./scripts/register-connector.sh --delete   remove o conector
#
# O caminho fim a fim do CDC (Oracle -> Kafka -> ClickHouse) é responsabilidade
# da Frente C; este script existe para que ela não precise mexer em infra.
# =============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

COMPOSE_FILE="infra/docker-compose.yml"
TEMPLATE="infra/debezium/connector-proc.json"

if [ ! -f .env ]; then
    echo "ERRO: .env não encontrado. Rode ./scripts/setup.sh primeiro." >&2
    exit 1
fi
set -a; . ./.env; set +a

ORACLE_DBZ_USER="${ORACLE_DBZ_USER:-c##dbzuser}"
ORACLE_DBZ_PASSWORD="${ORACLE_DBZ_PASSWORD:-dbz_dev}"
DEBEZIUM_TOPIC_PREFIX="${DEBEZIUM_TOPIC_PREFIX:-projudi}"

dc() { docker compose --env-file .env -f "${COMPOSE_FILE}" "$@"; }

# curl roda DENTRO do container do Connect: evita depender de curl no host e
# funciona igual no Windows, no macOS e no Linux.
api() { dc exec -T connect curl -sS "$@"; }

CONNECTOR_NAME="$(grep -o '"name"[[:space:]]*:[[:space:]]*"[^"]*"' "${TEMPLATE}" \
                  | head -n1 | sed 's/.*:[[:space:]]*"\(.*\)"/\1/')"

case "${1:-}" in
    --status)
        echo "Conector: ${CONNECTOR_NAME}"
        api "http://localhost:8083/connectors/${CONNECTOR_NAME}/status" || true
        echo
        exit 0
        ;;
    --delete)
        echo "Removendo conector ${CONNECTOR_NAME} ..."
        api -X DELETE -o /dev/null -w '%{http_code}\n' \
            "http://localhost:8083/connectors/${CONNECTOR_NAME}"
        exit 0
        ;;
esac

# -----------------------------------------------------------------------------
# Renderiza o template.
# Delimitador '|' no sed para não colidir com as barras das URLs; o '#' de
# c##dbzuser passa intacto. Senhas com '&' ou '|' quebrariam a substituição —
# se precisar de uma, troque a abordagem por envsubst.
# -----------------------------------------------------------------------------
render() {
    sed -e "s|\${ORACLE_DBZ_USER}|${ORACLE_DBZ_USER}|g" \
        -e "s|\${ORACLE_DBZ_PASSWORD}|${ORACLE_DBZ_PASSWORD}|g" \
        -e "s|\${DEBEZIUM_TOPIC_PREFIX}|${DEBEZIUM_TOPIC_PREFIX}|g" \
        "${TEMPLATE}"
}

if render | grep -q '\${'; then
    echo "ERRO: sobraram placeholders não substituídos em ${TEMPLATE}:" >&2
    render | grep -o '\${[A-Z_]*}' | sort -u >&2
    exit 1
fi

echo "Kafka Connect: aguardando a REST API ..."
for _ in $(seq 1 30); do
    if api -o /dev/null "http://localhost:8083/" >/dev/null 2>&1; then break; fi
    sleep 2
done

JA_EXISTE=0
if api -o /dev/null -w '%{http_code}' \
      "http://localhost:8083/connectors/${CONNECTOR_NAME}" 2>/dev/null | grep -q '^200$'; then
    JA_EXISTE=1
fi

if [ "${JA_EXISTE}" -eq 1 ]; then
    # Recriar é mais simples e mais confiável que fatiar o JSON para alimentar o
    # endpoint /config (que aceita só o objeto "config", sem o envelope).
    # Efeito colateral: os offsets do conector são descartados e o Debezium
    # refaz o snapshot inicial. Aceitável num ambiente de desenvolvimento — em
    # produção o caminho seria PUT /connectors/<nome>/config.
    echo "Conector '${CONNECTOR_NAME}' já existe — recriando (o snapshot será refeito) ..."
    api -X DELETE -o /dev/null "http://localhost:8083/connectors/${CONNECTOR_NAME}"
    sleep 3
fi

echo "Registrando conector '${CONNECTOR_NAME}' ..."
render | dc exec -T connect curl -sS -X POST \
             -H "Content-Type: application/json" --data @- \
             "http://localhost:8083/connectors"

echo
echo
echo "Estado do conector:"
sleep 3
api "http://localhost:8083/connectors/${CONNECTOR_NAME}/status" || true
echo
echo
echo "Tópicos disponíveis:"
dc exec -T kafka kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null | sed 's/^/  - /'
echo
echo "O tópico da PROC será: ${DEBEZIUM_TOPIC_PREFIX}.PROJUDI.PROC"
echo "Ele precisa bater com o kafka_topic_list de infra/clickhouse/ddl/04_proc_cdc_kafka.sql"
