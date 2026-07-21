# Validação

Consultas de conferência do ambiente e evidências geradas a partir delas.

## Arquivos

| Arquivo | O que valida | Onde roda |
|---|---|---|
| `01_clickhouse_logs.sql` | Solução 1: `projudi_logs.log_raw`, padrões de consulta, compressão obtida | ClickHouse (`make ch`) |
| `02_oracle_origem.sql` | Schema de origem `PROJUDI` e pré-requisitos do CDC; gera movimento na `PROC` | Oracle (`make sql`) |
| `03_consultas_cdc.sql` | Solução 2: chegada dos eventos, linha do tempo de um processo, diagnóstico do consumo Kafka | ClickHouse (`make ch`) |

Para a checagem automatizada do ambiente inteiro use `make validate`
(`scripts/validate.sh`) — estes arquivos são para inspeção manual e para gerar
os números que vão para o relatório.

## Como rodar

Interativo, colando os blocos que interessarem:

```bash
make ch          # abre o clickhouse-client
make sql         # abre o sqlplus como PROJUDI no FREEPDB1
```

Ou de uma vez, redirecionando o arquivo:

```bash
docker compose --env-file .env -f infra/docker-compose.yml \
  exec -T clickhouse clickhouse-client --multiquery \
  < validacao/01_clickhouse_logs.sql
```

## Ordem sugerida

1. `make validate` — o ambiente está de pé?
2. `02_oracle_origem.sql`, seções 1 a 4 — o seed subiu?
3. `01_clickhouse_logs.sql`, seção 1 — o ClickHouse aceita escrita?
4. `./scripts/register-connector.sh` — sobe o conector Debezium
5. `02_oracle_origem.sql`, seção 5 — gera insert/update/delete na `PROC`
6. `03_consultas_cdc.sql` — os eventos chegaram?

## `evidencias/`

Diretório para as saídas capturadas que forem virar figura ou tabela no
relatório (`\| tee validacao/evidencias/nome.txt`). O conteúdo é ignorado pelo
git — só o diretório é versionado.
