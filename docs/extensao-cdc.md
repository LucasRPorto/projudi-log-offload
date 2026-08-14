# Como adicionar qualquer tabela ao pipeline CDC (Solução 2)

*Este documento é o entregável T8 do roteiro de testes. Prova que a Solução 2 é genérica: qualquer tabela do PROJUDI que exija histórico auditável pode entrar no pipeline com os quatro passos abaixo.*

---

## Visão geral do fluxo

```
Oracle (tabela X)
  └─ supplemental logging ALL COLUMNS
        └─ Debezium (LogMiner) captura evento
              └─ Kafka tópico projudi.PROJUDI.X
                    └─ ClickHouse Kafka engine  → tabela _kafka  (raw)
                          └─ Materialized View  → tabela _cdc    (estruturada)
```

---

## Passo 1 — Supplemental logging na tabela Oracle

Conecte como DBA (ou como usuário com `ALTER TABLE` na tabela-alvo) e execute:

```sql
-- No PDB (FREEPDB1), como DBA ou dono da tabela:
ALTER TABLE PROJUDI.<TABELA> ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS;

-- Garante leitura ao usuário do Debezium:
GRANT SELECT ON PROJUDI.<TABELA> TO C##DBZ_USER;
```

**Por que ALL COLUMNS?**  
Sem isso o Oracle grava no redo apenas as colunas alteradas.  
O Debezium consegue montar o campo `after` com o restante nulo, mas o histórico fica inutilizável para auditoria.  
Com `ALL COLUMNS` cada UPDATE contém uma imagem completa da linha — custo de redo maior, decisão consciente (ver `docs/decisoes.md`).

Verifique que foi criado:

```sql
SELECT log_group_name, always
FROM user_log_groups
WHERE table_name = '<TABELA>';
-- Deve retornar pelo menos 1 linha com ALWAYS = 'ALWAYS'
```

---

## Passo 2 — Adicionar a tabela ao `include.list` do conector

Edite `infra/debezium/connector-proc.json` (ou crie um arquivo separado para o novo conector):

```json
{
  "name": "projudi-<tabela>-connector",
  "config": {
    "connector.class": "io.debezium.connector.oracle.OracleConnector",
    "tasks.max": "1",

    "database.hostname": "oracle",
    "database.port": "1521",
    "database.user": "${ORACLE_DBZ_USER}",
    "database.password": "${ORACLE_DBZ_PASSWORD}",
    "database.dbname": "FREE",
    "database.pdb.name": "FREEPDB1",

    "topic.prefix": "${DEBEZIUM_TOPIC_PREFIX}",
    "schema.include.list": "PROJUDI",
    "table.include.list": "PROJUDI.<TABELA>",

    "snapshot.mode": "initial",
    "skipped.operations": "none",
    "tombstones.on.delete": "false",
    "heartbeat.interval.ms": "10000",

    "decimal.handling.mode": "double",
    "time.precision.mode": "connect",
    "lob.enabled": "false",

    "log.mining.strategy": "online_catalog",

    "schema.history.internal.kafka.bootstrap.servers": "kafka:9092",
    "schema.history.internal.kafka.topic": "schema-history.${DEBEZIUM_TOPIC_PREFIX}",

    "key.converter": "org.apache.kafka.connect.json.JsonConverter",
    "key.converter.schemas.enable": "false",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": "false"
  }
}
```

Registre o conector via API REST:

```bash
curl -X POST localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @infra/debezium/<tabela>-connector.json

# Confirme RUNNING:
curl -s localhost:8083/connectors/projudi-<tabela>-connector/status | python3 -m json.tool
```

> O tópico gerado será `projudi.PROJUDI.<TABELA>` (prefixo definido em `DEBEZIUM_TOPIC_PREFIX` no `.env`).

---

## Passo 3 — DDLs no ClickHouse

Crie três objetos dentro do banco `projudi_historico`, seguindo o padrão da PROC:

### 3a. Tabela de destino (`<tabela>_cdc`)

```sql
CREATE TABLE IF NOT EXISTS projudi_historico.<tabela>_cdc
(
    -- colunas de controle CDC (sempre as mesmas):
    cdc_op          LowCardinality(String),
    cdc_ts_ms       Int64,
    cdc_scn         String,
    ingestion_ts    DateTime DEFAULT now(),

    -- colunas do negócio (espelhar o DDL Oracle com tipos equivalentes):
    ID_<TABELA>     UInt64,
    -- ... demais colunas ...
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(ingestion_ts)
ORDER BY (ID_<TABELA>, cdc_ts_ms);
```

### 3b. Tabela Kafka engine (`<tabela>_cdc_kafka`)

```sql
CREATE TABLE IF NOT EXISTS projudi_historico.<tabela>_cdc_kafka
(
    raw String
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list          = 'kafka:9092',
    kafka_topic_list           = 'projudi.PROJUDI.<TABELA>',
    kafka_group_name           = 'clickhouse-projudi-historico',
    kafka_format               = 'JSONAsString',
    kafka_num_consumers        = 1,
    kafka_max_block_size       = 65536,
    kafka_poll_max_batch_size  = 1000,
    kafka_flush_interval_ms    = 2000,
    kafka_handle_error_mode    = 'stream',
    kafka_skip_broken_messages = 100;
```

> **Por que `JSONAsString`?**  
> O envelope Debezium tem três níveis (`op`, `before`/`after`, `source`). Declarar 
> `before` + `after` como Tuple de N campos quebraria silenciosamente a cada coluna 
> nova ou ausente. Com `JSONAsString` a mensagem entra opaca e o desmembramento 
> acontece na MV campo a campo — campo ausente vira NULL, nunca perda de evento.

### 3c. Materialized View (`<tabela>_cdc_mv`)

```sql
CREATE MATERIALIZED VIEW IF NOT EXISTS projudi_historico.<tabela>_cdc_mv
TO projudi_historico.<tabela>_cdc
AS
WITH
    JSONExtractRaw(raw, 'payload')                  AS payload_raw,
    if(payload_raw != '', payload_raw, raw)         AS env,
    JSONExtractString(env, 'op')                    AS op,
    if(op = 'd', JSONExtractRaw(env, 'before'),
                 JSONExtractRaw(env, 'after'))      AS rec,
    JSONExtractRaw(env, 'source')                   AS src
SELECT
    op                                                              AS cdc_op,
    JSONExtractInt(env, 'ts_ms')                                    AS cdc_ts_ms,
    trim(BOTH '"' FROM JSONExtractRaw(src, 'scn'))                  AS cdc_scn,
    now()                                                           AS ingestion_ts,
    -- colunas do negócio (adaptar tipos conforme a tabela):
    JSONExtract(rec, 'ID_<TABELA>', 'UInt64')                       AS ID_<TABELA>
    -- ...
FROM projudi_historico.<tabela>_cdc_kafka
WHERE op IN ('c','u','d','r');
```

**Conversões importantes** (mesmas regras usadas na PROC):

| Tipo Oracle | Tipo ClickHouse | Conversão na MV |
|---|---|---|
| NUMBER(p,s) numérico | `Decimal(20,2)` | `accurateCastOrNull(JSONExtract(...,'Float64'), 'Decimal(20,2)')` |
| DATE / TIMESTAMP | `DateTime64(3)` | `toDateTime64(JSONExtract(...,'Int64') / 1000, 3)` |
| VARCHAR2 | `Nullable(String)` | `JSONExtract(rec, 'COL', 'Nullable(String)')` |
| NUMBER sem decimais | `Nullable(UInt64)` | `JSONExtract(rec, 'COL', 'Nullable(UInt64)')` |

---

## Passo 4 — Validação

```sql
-- 1. Snapshot inicial chegou?
SELECT cdc_op, count()
FROM projudi_historico.<tabela>_cdc
GROUP BY cdc_op;
-- Deve ter linhas com cdc_op = 'r'

-- 2. Gere um UPDATE no Oracle e confira a propagação:
-- (Oracle) UPDATE PROJUDI.<TABELA> SET <COL> = <VALOR> WHERE <PK> = <id>; COMMIT;
-- (ClickHouse, ~5–60s depois)
SELECT *
FROM projudi_historico.<tabela>_cdc
WHERE cdc_op = 'u'
ORDER BY ingestion_ts DESC
LIMIT 5;
```

---

## Checklist rápido

- [ ] `ALTER TABLE ... ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS` executado
- [ ] `GRANT SELECT ON PROJUDI.<TABELA> TO C##DBZ_USER` executado
- [ ] Conector registrado e com estado `RUNNING`
- [ ] Tabela `<tabela>_cdc` criada no ClickHouse
- [ ] Tabela Kafka `<tabela>_cdc_kafka` criada e consumindo o tópico correto
- [ ] Materialized View `<tabela>_cdc_mv` criada e apontando para `<tabela>_cdc`
- [ ] Snapshot inicial (`cdc_op = 'r'`) visível no ClickHouse
- [ ] UPDATE de teste propagado em < 60 s

---

## Referências internas

- `infra/debezium/connector-proc.json` — conector de referência (PROC)
- `infra/clickhouse/ddl/03_proc_cdc.sql` — tabela de destino de referência
- `infra/clickhouse/ddl/04_proc_cdc_kafka.sql` — Kafka engine de referência
- `infra/clickhouse/ddl/05_proc_cdc_mv.sql` — Materialized View de referência
- `infra/oracle/init/sql/60_pdb_supplemental_proc.sql` — supplemental logging de referência
- `docs/decisoes.md` — justificativa do `ALL COLUMNS` e do `JSONAsString`
