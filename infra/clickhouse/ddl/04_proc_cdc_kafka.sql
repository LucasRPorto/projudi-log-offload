-- =============================================================================
-- 04 — projudi_historico.proc_cdc_kafka : consumidor Kafka do tópico da PROC
--
-- -----------------------------------------------------------------------------
-- POR QUE JSONAsString E NÃO JSONEachRow
-- -----------------------------------------------------------------------------
-- O que chega no tópico não é a linha da PROC: é o "envelope" do Debezium, que
-- embrulha a linha em três níveis —
--
--   { "op": "u",
--     "ts_ms": 1721570000123,
--     "before":  { ...43 colunas... },
--     "after":   { ...43 colunas... },
--     "source":  { "scn": "...", "table": "PROC", ... } }
--
-- (e, se schemas.enable=true no converter, tudo isso ainda vem dentro de
-- {"schema":{...},"payload":{...}}).
--
-- Para ler isso com JSONEachRow seria preciso declarar `before` e `after` como
-- Tuple de 43 campos cada, replicados na definição da tabela. Qualquer coluna
-- nova na PROC, ou qualquer campo ausente numa mensagem, quebraria o parse do
-- BLOCO INTEIRO — o Kafka engine descarta o bloco, e o offset avança: perda
-- silenciosa de eventos, exatamente o que a Solução 2 não pode ter.
--
-- Com JSONAsString a mensagem entra como texto opaco numa única coluna e o
-- desmembramento acontece na MATERIALIZED VIEW, com JSONExtract campo a campo.
-- Campo ausente vira NULL naquela coluna, não erro. O custo é reparsear o JSON
-- na MV — aceitável, e o ClickHouse deduplica as subexpressões idênticas
-- (`payload`, `after`, `source`) num único nó do DAG de execução.
--
-- kafka_handle_error_mode='stream' completa a proteção: mensagem ilegível vai
-- para as colunas virtuais _error/_raw_message em vez de derrubar o consumo.
--
-- -----------------------------------------------------------------------------
-- ATENÇÃO — valores fixos
-- -----------------------------------------------------------------------------
-- DDL não lê variáveis do .env. Estes dois valores precisam bater com o compose
-- e com infra/debezium/connector-proc.json:
--   kafka_broker_list = 'kafka:9092'            (nome do serviço no compose)
--   kafka_topic_list  = 'projudi.PROJUDI.PROC'  (<topic.prefix>.<schema>.<tabela>)
-- Se DEBEZIUM_TOPIC_PREFIX mudar no .env, mude o tópico aqui também.
-- =============================================================================

CREATE TABLE IF NOT EXISTS projudi_historico.proc_cdc_kafka
(
    raw String
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list         = 'kafka:9092',
    kafka_topic_list          = 'projudi.PROJUDI.PROC',
    kafka_group_name          = 'clickhouse-projudi-historico',
    kafka_format              = 'JSONAsString',
    kafka_num_consumers       = 1,
    kafka_max_block_size      = 65536,
    kafka_poll_max_batch_size = 1000,
    kafka_flush_interval_ms   = 2000,
    kafka_handle_error_mode   = 'stream',
    kafka_skip_broken_messages = 100;
