-- =============================================================================
-- 01 — Bancos de dados
--
-- projudi_logs      : Solução 1 — offload direto do LogPs (JDBC) para o ClickHouse
-- projudi_historico : Solução 2 — histórico da PROJUDI.PROC capturado via CDC
--
-- Executado automaticamente pelo entrypoint da imagem oficial
-- (/docker-entrypoint-initdb.d) no PRIMEIRO start, quando o volume
-- clickhouse-data ainda está vazio. Todos os DDLs são idempotentes, então
-- podem ser reaplicados à mão com `make ch` sem efeito colateral.
-- =============================================================================

CREATE DATABASE IF NOT EXISTS projudi_logs
    COMMENT 'Solução 1: logs de auditoria do Projudi gravados direto pelo LogPs';

CREATE DATABASE IF NOT EXISTS projudi_historico
    COMMENT 'Solução 2: histórico de mudanças da PROJUDI.PROC via Debezium/Kafka';
