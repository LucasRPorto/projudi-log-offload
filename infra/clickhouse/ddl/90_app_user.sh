#!/bin/sh
# =============================================================================
# 90 — Usuário de aplicação do ClickHouse
#
# Roda por último no /docker-entrypoint-initdb.d, quando os dois bancos já
# existem (os GRANTs precisam deles).
#
# É um .sh e não um .sql porque a senha vem do .env, e arquivo DDL versionado
# não pode carregar segredo. As variáveis CH_APP_USER / CH_APP_PASSWORD são
# injetadas pelo compose.
#
# O usuário `default` é preservado (sem senha, para uso interativo via
# `make ch`). Este script cria um segundo usuário, com escopo restrito aos dois
# bancos do projeto — é ele que a Frente B (log-writer) usa via JDBC.
#
# Nota: o entrypoint oficial executa este arquivo com `.` (source) quando ele
# não tem bit de execução, o que é o caso num checkout Windows. Por isso aqui
# não se usa `set -e` nem `exit`: um erro derrubaria o entrypoint inteiro sem
# mensagem útil. Falhas são reportadas explicitamente.
# =============================================================================

CH_APP_USER="${CH_APP_USER:-projudi_app}"
CH_APP_PASSWORD="${CH_APP_PASSWORD:-projudi_app_dev}"

echo "90_app_user.sh: criando usuário de aplicação '${CH_APP_USER}'"

clickhouse-client --host 127.0.0.1 --multiquery <<EOSQL
CREATE USER IF NOT EXISTS ${CH_APP_USER}
    IDENTIFIED WITH sha256_password BY '${CH_APP_PASSWORD}'
    HOST ANY
    DEFAULT DATABASE projudi_logs;

GRANT ALL    ON projudi_logs.*      TO ${CH_APP_USER};
GRANT ALL    ON projudi_historico.* TO ${CH_APP_USER};

-- Necessário para o log-writer inspecionar o próprio throughput e para os
-- scripts de validação consultarem parts/mutations sem usar o `default`.
GRANT SELECT ON system.*            TO ${CH_APP_USER};
EOSQL

if [ $? -eq 0 ]; then
    echo "90_app_user.sh: OK — usuário '${CH_APP_USER}' criado com acesso a projudi_logs e projudi_historico"
else
    echo "90_app_user.sh: ERRO ao criar o usuário '${CH_APP_USER}'." >&2
    echo "90_app_user.sh: verifique se users.d/10-access-management.xml foi montado" >&2
    echo "90_app_user.sh: (sem access_management=1 o usuário default não pode executar CREATE USER)." >&2
fi
