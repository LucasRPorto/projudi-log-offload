-- =============================================================================
-- 03 — projudi_historico.proc_cdc : tabela final do pipeline CDC
--
-- Recebe, via MATERIALIZED VIEW (ver 05_cdc_mv.sql), cada mudança da tabela
-- PROJUDI.PROC capturada pelo Debezium a partir do redo log do Oracle.
--
-- É um histórico APPEND-ONLY: uma linha por evento (insert/update/delete/
-- snapshot), nunca um "estado atual". O estado atual se obtém com
-- argMax(...) ou LIMIT 1 BY ID_PROC ORDER BY cdc_ts_ms DESC — ver
-- validacao/03_consultas_cdc.sql. Por isso MergeTree puro, e não
-- ReplacingMergeTree: colapsar versões destruiria justamente o que a
-- Solução 2 existe para preservar.
--
-- -----------------------------------------------------------------------------
-- MAPEAMENTO DE TIPOS (mesma regra do 02_log_raw.sql)
--   NUMBER(1)     -> UInt8         NUMBER(10)    -> UInt64
--   NUMBER(3..4)  -> UInt16        NUMBER(12)    -> UInt64
--   NUMBER(5..6)  -> UInt32        NUMBER(24)    -> UInt64
--   NUMBER(20,2)  -> Decimal(20,2) (valores monetários: nunca ponto flutuante
--                                   no armazenamento final)
--   DATE          -> DateTime64(3) (o Debezium emite epoch em MILISSEGUNDOS
--                                   com time.precision.mode=connect)
--   VARCHAR2(n)   -> String
--
-- NULABILIDADE: todas as 43 colunas de negócio são Nullable, exceto ID_PROC.
-- Motivo: diferente da log_raw (onde o produtor é a nossa própria classe Java),
-- aqui o produtor é o LogMiner. Um evento com coluna ausente — supplemental
-- logging incompleto, coluna adicionada por DDL, before-image parcial — faria
-- a MATERIALIZED VIEW abortar o bloco inteiro se a coluna fosse NOT NULL.
-- Preferiu-se ingerir com NULL e detectar depois a inserir nada.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS projudi_historico.proc_cdc
(
    -- ---- 43 colunas da PROJUDI.PROC -----------------------------------------
    ID_PROC                        UInt64                    CODEC(Delta(8), ZSTD(1)),
    ID_PROC_DEPENDENTE             Nullable(UInt64)          CODEC(ZSTD(1)),
    PROC_NUMERO                    Nullable(UInt64)          CODEC(ZSTD(1)),
    DIGITO_VERIFICADOR             Nullable(UInt32)          CODEC(ZSTD(1)),
    FORUM_CODIGO                   Nullable(UInt64)          CODEC(ZSTD(1)),
    ANO                            Nullable(UInt32)          CODEC(ZSTD(1)),
    ID_PROC_TIPO                   Nullable(UInt64)          CODEC(ZSTD(1)),
    ID_PROC_PRIOR                  Nullable(UInt64)          CODEC(ZSTD(1)),
    ID_PROC_FASE                   Nullable(UInt64)          CODEC(ZSTD(1)),
    ID_PROC_STATUS                 Nullable(UInt64)          CODEC(ZSTD(1)),
    ID_SERV                        Nullable(UInt64)          CODEC(ZSTD(1)),
    ID_SERV_ORIGEM                 Nullable(UInt64)          CODEC(ZSTD(1)),
    ID_AREA                        Nullable(UInt32)          CODEC(ZSTD(1)),
    ID_OBJETO_PEDIDO               Nullable(UInt64)          CODEC(ZSTD(1)),
    ID_CLASSIFICADOR               Nullable(UInt64)          CODEC(ZSTD(1)),
    ID_PROC_SIT                    Nullable(UInt64)          CODEC(ZSTD(1)),
    TCO_NUMERO                     Nullable(String)          CODEC(ZSTD(1)),
    SEGREDO_JUSTICA                Nullable(UInt16)          CODEC(ZSTD(1)),
    APENSO                         Nullable(UInt16)          CODEC(ZSTD(1)),
    VALOR                          Nullable(Decimal(20, 2))  CODEC(ZSTD(1)),
    DATA_RECEBIMENTO               Nullable(DateTime64(3))   CODEC(ZSTD(1)),
    DATA_ARQUIVAMENTO              Nullable(DateTime64(3))   CODEC(ZSTD(1)),
    PROC_DIRETORIO                 Nullable(String)          CODEC(ZSTD(1)),
    CODIGO_TEMP                    Nullable(UInt64)          CODEC(ZSTD(1)),
    PROC_NUMERO_ANTIGO_TEMP        Nullable(UInt64)          CODEC(ZSTD(1)),
    CODRE_CURSO_TEMP               Nullable(UInt64)          CODEC(ZSTD(1)),
    TABELA_ORIGEM_TEMP             Nullable(String)          CODEC(ZSTD(1)),
    EFEITO_SUSPENSIVO              Nullable(UInt16)          CODEC(ZSTD(1)),
    PENHORA                        Nullable(UInt16)          CODEC(ZSTD(1)),
    DATA_TRANSITO_JULGADO          Nullable(DateTime64(3))   CODEC(ZSTD(1)),
    JULGADO_2_GRAU                 Nullable(UInt8)           CODEC(ZSTD(1)),
    VALOR_CONDENACAO               Nullable(Decimal(20, 2))  CODEC(ZSTD(1)),
    ID_CUSTA_TIPO                  Nullable(UInt16)          CODEC(ZSTD(1)),
    ID_AREA_DIST                   Nullable(UInt64)          CODEC(ZSTD(1)),
    DATA_DIGITALIZACAO             Nullable(DateTime64(3))   CODEC(ZSTD(1)),
    PROCESSO_FISICO_TIPO           Nullable(String)          CODEC(ZSTD(1)),
    PROCESSO_FISICO_NUMERO         Nullable(String)          CODEC(ZSTD(1)),
    PROCESSO_FISICO_COMARCA_CODIGO Nullable(UInt64)          CODEC(ZSTD(1)),
    LOCALIZADOR                    Nullable(String)          CODEC(ZSTD(1)),
    DATA_PRESCRICAO                Nullable(DateTime64(3))   CODEC(ZSTD(1)),
    DIGITAL100                     Nullable(UInt8)           CODEC(ZSTD(1)),
    ID_PROC_PRIOR_1                Nullable(UInt64)          CODEC(ZSTD(1)),
    ID_CLASSIFICADOR2              Nullable(UInt64)          CODEC(ZSTD(1)),

    -- ---- Metadados do evento CDC --------------------------------------------
    -- c = create, u = update, d = delete, r = read (linha lida no snapshot inicial)
    cdc_op          LowCardinality(String),
    -- Instante em que o Debezium processou o evento (epoch ms). Compõe a chave
    -- de ordenação: é o que define a ordem do histórico de um mesmo ID_PROC.
    cdc_ts_ms       UInt64        CODEC(Delta(8), ZSTD(1)),
    -- System Change Number do Oracle. String porque o SCN pode ultrapassar
    -- UInt64 em bancos antigos e porque só é usado para rastreabilidade/join
    -- com o alert log, nunca em aritmética.
    cdc_scn         String        CODEC(ZSTD(1)),
    ingestion_ts    DateTime      DEFAULT now() CODEC(Delta(4), ZSTD(1))
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(ingestion_ts)
ORDER BY (ID_PROC, cdc_ts_ms)
SETTINGS index_granularity = 8192
COMMENT 'Histórico append-only da PROJUDI.PROC capturado via Debezium/LogMiner';
