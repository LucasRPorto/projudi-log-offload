#!/bin/sh
# =============================================================================
# 90 — Usuário de aplicação do ClickHouse
# =============================================================================

CH_APP_USER="${CH_APP_USER:-projudi_app}"
CH_APP_PASSWORD="${CH_APP_PASSWORD:-projudi_app_dev}"

# Escapes para evitar quebra de SQL por caracteres especiais
CH_APP_USER_SQL="$(printf '%s' "$CH_APP_USER" | sed 's/`/``/g')"
CH_APP_PASSWORD_SQL="$(printf '%s' "$CH_APP_PASSWORD" | sed "s/'/''/g")"

echo "90_app_user.sh: criando usuário de aplicação '${CH_APP_USER}'"

clickhouse-client --host 127.0.0.1 --multiquery <<EOSQL
CREATE USER IF NOT EXISTS \`${CH_APP_USER_SQL}\`
    IDENTIFIED WITH sha256_password BY '${CH_APP_PASSWORD_SQL}'
    DEFAULT DATABASE projudi_logs;

GRANT ALL    ON projudi_logs.*      TO \`${CH_APP_USER_SQL}\`;
GRANT ALL    ON projudi_historico.* TO \`${CH_APP_USER_SQL}\`;
GRANT SELECT ON system.*            TO \`${CH_APP_USER_SQL}\`;
EOSQL

if [ $? -eq 0 ]; then
    echo "90_app_user.sh: OK — usuário '${CH_APP_USER}' criado com acesso a projudi_logs e projudi_historico"
else
    echo "90_app_user.sh: ERRO ao criar o usuário '${CH_APP_USER}'." >&2
    echo "90_app_user.sh: verifique se users.d/10-access-management.xml foi montado" >&2
    echo "90_app_user.sh: (sem access_management=1 o usuário default não pode executar CREATE USER)." >&2
fi
