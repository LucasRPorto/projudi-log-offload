-- =============================================================================
-- 02 — projudi_logs.log_raw : espelho fiel da tabela PROJUDI.LOG do Oracle
--
-- Premissa da Solução 1: o formato do log é PRESERVADO. O LogPs continua
-- montando VALOR_ATUAL / VALOR_NOVO exatamente como hoje; o ClickHouse apenas
-- troca o destino da gravação. Nenhum parsing é feito na ingestão.
--
-- -----------------------------------------------------------------------------
-- MAPEAMENTO DE TIPOS Oracle -> ClickHouse
-- -----------------------------------------------------------------------------
-- Regra geral: NUMBER(p) vira o menor inteiro sem sinal que cobre 10^p - 1.
-- Todas as colunas numéricas da LOG são identificadores ou contadores gerados
-- por sequence/aplicação, portanto nunca negativos — daí o uso de UInt*.
--
--   NUMBER(24)  -> UInt64
--       Formalmente NUMBER(24) (até 10^24-1) excede UInt64 (máx ~1,84x10^19).
--       Na prática os valores vêm de sequences e hoje estão na casa de
--       10^9-10^10; o teto só seria atingido com ~10^19 registros. A alternativa
--       exata seria Decimal(24,0) (16 bytes, aritmética mais lenta) ou String.
--       Optou-se por UInt64: 8 bytes, comparação nativa e uso direto na chave
--       de ordenação. Ver docs/decisoes.md.
--
--   NUMBER(10)  -> UInt64   (10^10-1 não cabe em UInt32)
--   NUMBER(6)   -> UInt32
--   DATE        -> DateTime
--       O tipo DATE do Oracle carrega data E hora (precisão de segundo), então
--       o equivalente correto é DateTime (não Date). As duas colunas DATA e
--       HORA são redundantes no schema legado (DATA = meia-noite do dia,
--       HORA = instante completo); ambas foram mantidas para fidelidade.
--
--   CLOB        -> String   (o ClickHouse não distingue texto curto de longo)
--   CHAR(32)    -> FixedString(32)  — hash MD5 hexadecimal, tamanho fixo
--   VARCHAR2(n) -> String / LowCardinality(String)
--
-- NULABILIDADE: DATA e HORA são NOT NULL porque HORA é a primeira coluna da
-- chave de ordenação (chaves Nullable degradam o índice esparso) e o LogPs
-- sempre as preenche. VALOR_ATUAL/VALOR_NOVO/IP_COMPUTADOR/TABELA usam String
-- não-Nullable com '' representando ausência: evita o bitmap de nulos, que
-- custaria mais que o próprio dado nessas colunas.
--
-- -----------------------------------------------------------------------------
-- ENGINE / CHAVES
-- -----------------------------------------------------------------------------
-- ORDER BY (HORA, ID_USU, ID_LOG) atende os três padrões reais de consulta:
--   1) range scan por período  -> HORA como primeira coluna elimina partes
--                                 inteiras já pelo índice esparso;
--   2) "o que o usuário X fez entre A e B" -> (HORA, ID_USU);
--   3) unicidade/desempate     -> ID_LOG fecha a chave.
-- PARTITION BY toYYYYMM(HORA) casa com o padrão de retenção/consulta mensal e
-- mantém a contagem de partes baixa (uma partição por mês).
--
-- O quarto padrão — "histórico da linha N da tabela T" — não cabe na chave de
-- ordenação sem prejudicar os outros três, então é atendido por data skipping
-- indexes (set/bloom_filter) sobre TABELA e ID_TABELA.
--
-- CODECS: Delta+ZSTD nos IDs e timestamps (sequenciais dentro da parte, o delta
-- fica quase constante); ZSTD(3) nos CLOBs, onde o ganho de compressão paga o
-- custo de CPU — são de longe as maiores colunas da tabela.
--
-- TTL: nenhum. A retenção segue indefinida, igual à política atual do Oracle.
-- =============================================================================

CREATE TABLE IF NOT EXISTS projudi_logs.log_raw
(
    ID_LOG          UInt64                      CODEC(Delta(8), ZSTD(1)),
    ID_LOG_TIPO     UInt64                      CODEC(Delta(8), ZSTD(1)),
    ID_USU          UInt64                      CODEC(Delta(8), ZSTD(1)),
    IP_COMPUTADOR   String                      CODEC(ZSTD(1)),
    DATA            DateTime                    CODEC(Delta(4), ZSTD(1)),
    HORA            DateTime                    CODEC(Delta(4), ZSTD(1)),
    TABELA          LowCardinality(String)      CODEC(ZSTD(1)),
    VALOR_ATUAL     String                      CODEC(ZSTD(3)),
    VALOR_NOVO      String                      CODEC(ZSTD(3)),
    CODIGO_TEMP     Nullable(UInt64)            CODEC(ZSTD(1)),
    ID_TABELA       Nullable(UInt64)            CODEC(ZSTD(1)),
    HASH            Nullable(FixedString(32))   CODEC(ZSTD(1)),
    QTD_ERROS_DIA   Nullable(UInt32)            CODEC(ZSTD(1)),

    -- "histórico da linha N da tabela T": TABELA tem cardinalidade baixa
    -- (centenas de nomes), ID_TABELA é alta e não correlacionada com a ordem
    -- física, por isso bloom_filter em vez de minmax.
    INDEX idx_tabela      TABELA      TYPE set(512)           GRANULARITY 4,
    INDEX idx_id_tabela   ID_TABELA   TYPE bloom_filter(0.01) GRANULARITY 4,
    INDEX idx_log_tipo    ID_LOG_TIPO TYPE set(256)           GRANULARITY 4
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(HORA)
ORDER BY (HORA, ID_USU, ID_LOG)
SETTINGS index_granularity = 8192
COMMENT 'Espelho da PROJUDI.LOG — destino da gravação direta do LogPs (Solução 1)';


-- -----------------------------------------------------------------------------
-- Dimensão PROJUDI.LOG_TIPO.
-- Não faz parte do caminho de escrita do LogPs, mas sem ela toda consulta de
-- validação precisaria decorar códigos numéricos. ReplacingMergeTree porque a
-- tabela é recarregada por inteiro quando muda, e não incrementada.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS projudi_logs.log_tipo
(
    ID_LOG_TIPO     UInt64,
    LOG_TIPO_CODIGO UInt64,
    LOG_TIPO        String,
    CODIGO_TEMP     Nullable(UInt64),
    STATUS          UInt8,
    updated_at      DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(updated_at)
ORDER BY ID_LOG_TIPO
COMMENT 'Dimensão de tipos de log (espelho da PROJUDI.LOG_TIPO)';
