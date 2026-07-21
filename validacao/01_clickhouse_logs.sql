-- =============================================================================
-- Validação — Solução 1 (projudi_logs)
--
-- Como rodar:
--   make ch  < validacao/01_clickhouse_logs.sql
-- ou, interativamente:
--   make ch      e cole os blocos que interessarem
--
-- Estas consultas são os padrões de acesso que justificaram a chave de
-- ordenação de log_raw. Depois que a Frente B carregar volume de verdade, elas
-- viram a base da comparação de tempo de resposta contra o Oracle.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Sanidade: a tabela existe e está vazia/populada?
-- ---------------------------------------------------------------------------
SELECT
    count()                              AS linhas,
    uniqExact(ID_USU)                    AS usuarios_distintos,
    min(HORA)                            AS primeiro_evento,
    max(HORA)                            AS ultimo_evento
FROM projudi_logs.log_raw;


-- ---------------------------------------------------------------------------
-- 2. Padrão A — range scan por período.
--    Deve ler apenas as partições do intervalo (confira com EXPLAIN indexes=1).
-- ---------------------------------------------------------------------------
SELECT
    toStartOfHour(HORA)                  AS hora,
    count()                              AS eventos
FROM projudi_logs.log_raw
WHERE HORA >= now() - INTERVAL 30 DAY
GROUP BY hora
ORDER BY hora
LIMIT 50;


-- ---------------------------------------------------------------------------
-- 3. Padrão B — "o que o usuário X fez no período".
--    Usa as duas primeiras colunas da chave de ordenação.
-- ---------------------------------------------------------------------------
SELECT
    HORA,
    t.LOG_TIPO                           AS tipo,
    l.TABELA,
    l.ID_TABELA,
    l.IP_COMPUTADOR,
    substring(l.VALOR_NOVO, 1, 120)      AS valor_novo_inicio
FROM projudi_logs.log_raw AS l
LEFT JOIN projudi_logs.log_tipo AS t USING (ID_LOG_TIPO)
WHERE l.ID_USU = 5001
  AND l.HORA BETWEEN now() - INTERVAL 90 DAY AND now()
ORDER BY l.HORA DESC
LIMIT 100;


-- ---------------------------------------------------------------------------
-- 4. Padrão C — histórico de uma linha específica.
--    Não está na chave de ordenação: é aqui que os data skipping indexes
--    (idx_tabela, idx_id_tabela) precisam mostrar serviço.
-- ---------------------------------------------------------------------------
SELECT
    HORA,
    ID_USU,
    ID_LOG_TIPO,
    VALOR_ATUAL,
    VALOR_NOVO
FROM projudi_logs.log_raw
WHERE TABELA = 'PROC'
  AND ID_TABELA = 1000
ORDER BY HORA
LIMIT 100;

-- Confirmação de que o skip index foi usado (procure por "Skip" na saída):
EXPLAIN indexes = 1
SELECT count()
FROM projudi_logs.log_raw
WHERE TABELA = 'PROC' AND ID_TABELA = 1000;


-- ---------------------------------------------------------------------------
-- 5. Compressão obtida por coluna.
--    É a evidência quantitativa do ganho de armazenamento em relação ao Oracle:
--    VALOR_ATUAL/VALOR_NOVO (os CLOBs) são as colunas que mais pesam.
-- ---------------------------------------------------------------------------
SELECT
    name                                              AS coluna,
    formatReadableSize(sum(data_compressed_bytes))    AS comprimido,
    formatReadableSize(sum(data_uncompressed_bytes))  AS bruto,
    round(sum(data_uncompressed_bytes)
          / nullIf(sum(data_compressed_bytes), 0), 2) AS razao
FROM system.columns
WHERE database = 'projudi_logs' AND table = 'log_raw'
GROUP BY name
ORDER BY sum(data_compressed_bytes) DESC;


-- ---------------------------------------------------------------------------
-- 6. Saúde física da tabela: partições, partes e tamanho total.
-- ---------------------------------------------------------------------------
SELECT
    partition,
    count()                                 AS partes,
    sum(rows)                               AS linhas,
    formatReadableSize(sum(bytes_on_disk))  AS em_disco
FROM system.parts
WHERE database = 'projudi_logs' AND table = 'log_raw' AND active
GROUP BY partition
ORDER BY partition;
