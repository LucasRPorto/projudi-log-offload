-- =============================================================================
-- Validação — Solução 2 (projudi_historico)
--
-- Como rodar:
--   make ch   e cole os blocos
--
-- Pré-requisito: conector registrado (./scripts/register-connector.sh) e algum
-- movimento gerado na PROJUDI.PROC (ver validacao/02_oracle_origem.sql, seção 5).
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Chegou alguma coisa?
-- ---------------------------------------------------------------------------
SELECT
    count()            AS eventos,
    uniqExact(ID_PROC) AS processos_distintos,
    min(ingestion_ts)  AS primeira_ingestao,
    max(ingestion_ts)  AS ultima_ingestao
FROM projudi_historico.proc_cdc;


-- ---------------------------------------------------------------------------
-- 2. Distribuição por tipo de operação.
--    r = snapshot inicial, c = insert, u = update, d = delete
-- ---------------------------------------------------------------------------
SELECT
    cdc_op,
    count()           AS eventos,
    min(cdc_ts_ms)    AS primeiro_ts,
    max(cdc_ts_ms)    AS ultimo_ts
FROM projudi_historico.proc_cdc
GROUP BY cdc_op
ORDER BY eventos DESC;


-- ---------------------------------------------------------------------------
-- 3. Linha do tempo de um processo — o produto central da Solução 2.
--    É o que hoje só existe reconstruindo à mão a partir da PROJUDI.LOG.
-- ---------------------------------------------------------------------------
SELECT
    fromUnixTimestamp64Milli(cdc_ts_ms) AS quando,
    cdc_op,
    cdc_scn,
    ID_PROC_FASE,
    ID_PROC_STATUS,
    VALOR,
    LOCALIZADOR
FROM projudi_historico.proc_cdc
WHERE ID_PROC = 1000
ORDER BY cdc_ts_ms;


-- ---------------------------------------------------------------------------
-- 4. Estado atual reconstruído.
--    A tabela é append-only de propósito; o "agora" é uma consulta, não um
--    estado armazenado. LIMIT 1 BY é a forma barata de fazer isso.
-- ---------------------------------------------------------------------------
SELECT *
FROM projudi_historico.proc_cdc
ORDER BY ID_PROC, cdc_ts_ms DESC
LIMIT 1 BY ID_PROC;

-- Excluindo os processos cujo último evento foi um delete:
SELECT ID_PROC, ID_PROC_FASE, ID_PROC_STATUS, VALOR, LOCALIZADOR
FROM (
    SELECT *
    FROM projudi_historico.proc_cdc
    ORDER BY ID_PROC, cdc_ts_ms DESC
    LIMIT 1 BY ID_PROC
)
WHERE cdc_op != 'd'
ORDER BY ID_PROC;


-- ---------------------------------------------------------------------------
-- 5. O que exatamente mudou entre um evento e o anterior?
--    Compara colunas de interesse com a versão anterior do mesmo ID_PROC.
-- ---------------------------------------------------------------------------
SELECT
    ID_PROC,
    fromUnixTimestamp64Milli(cdc_ts_ms)                                   AS quando,
    cdc_op,
    anyOrNull(ID_PROC_FASE)   OVER w                                      AS fase_anterior,
    ID_PROC_FASE                                                          AS fase_atual,
    anyOrNull(VALOR)          OVER w                                      AS valor_anterior,
    VALOR                                                                 AS valor_atual
FROM projudi_historico.proc_cdc
WINDOW w AS (PARTITION BY ID_PROC ORDER BY cdc_ts_ms
             ROWS BETWEEN 1 PRECEDING AND 1 PRECEDING)
ORDER BY ID_PROC, cdc_ts_ms;


-- ---------------------------------------------------------------------------
-- 6. Diagnóstico do consumo Kafka.
--    Se a contagem do item 1 estiver parada, o problema está aqui.
-- ---------------------------------------------------------------------------

-- Erros do Kafka engine (exceções de parse, broker fora do ar, etc.)
SELECT event_time, exception
FROM system.text_log
WHERE logger_name LIKE '%Kafka%' AND level <= 'Error'
ORDER BY event_time DESC
LIMIT 20;

-- Consumidores registrados e offsets vistos pelo ClickHouse
SELECT database, table, consumer_id, assignments.topic, assignments.partition_id,
       assignments.current_offset, num_messages_read, last_poll_time, exceptions.text
FROM system.kafka_consumers
WHERE database = 'projudi_historico';

-- Mensagens cruas que a MV rejeitou por não conterem before/after utilizável:
-- (leitura direta da tabela Kafka CONSOME do tópico — use apenas para depurar,
--  nunca com o pipeline em regime, porque avança o offset do grupo)
-- SELECT raw FROM projudi_historico.proc_cdc_kafka LIMIT 1;
